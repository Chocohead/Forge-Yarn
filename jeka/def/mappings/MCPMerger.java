package mappings;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.AbstractMap.SimpleImmutableEntry;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiFunction;
import java.util.stream.Collectors;

import dev.jeka.core.api.file.JkPathTree;

import net.fabricmc.tinyremapper.asm.Type;
import net.fabricmc.tinyremapper.asm.commons.Remapper;

import mappings.MappingReader.ClassMapping;

public class MCPMerger {
	static int getMethodIndex(String function) {
		assert function.startsWith("func_"): "Unexpected function start " + function;
		assert function.indexOf('_', 5) == function.lastIndexOf('_', function.length() - 1);
		return Integer.parseUnsignedInt(function.substring(5, function.lastIndexOf('_', function.length() - 1)));
	}

	public static void mergeFrom(Path allYarn, String mcpConfig, String mcp, Path yarnSrg, Path mcpYarn, Path output) {
		Path mcpCache = Paths.get(System.getProperty("user.home"), ".gradle", "caches", "forge_gradle", "maven_downloader", "de", "oceanlabs", "mcp");
		if (!Files.isReadable(mcpCache) || !Files.isDirectory(mcpCache)) {
			throw new RuntimeException("Unable to find mcpCache (wasn't at " + mcpCache + ')');
		}

		Path mcpConfigZip = mcpCache.resolve("mcp_config/" + mcpConfig + "/mcp_config-" + mcpConfig + ".zip");
		assert Files.isRegularFile(mcpConfigZip);

		int split = mcp.lastIndexOf('_');
		if (split < 1) throw new IllegalArgumentException("Invalid MCP version: " + mcp);
		String mcpChannel = mcp.substring(0, split);
		String mcpVersion = mcp.substring(split + 1);

		Path mcpZip;
		switch (mcpChannel) {//Support list based on MCPRepo#findNames(String)
		case "official": //Technically we could, it just doesn't make much sense to
			throw new UnsupportedOperationException("Can't map Forge when using Mojang names!");

		case "snapshot":
		case "snapshot_nodoc":
		case "stable":
		case "stable_nodoc":
			mcpZip = mcpCache.resolve("mcp_" + mcpChannel + '/' + mcpVersion + "/mcp_" + mcpChannel + '-' + mcpVersion + ".zip");
			break;

		default:
			//https://github.com/MinecraftForge/ForgeGradle/blob/FG_3.0/src/mcp/java/net/minecraftforge/gradle/mcp/MCPRepo.java#L364-L377
			throw new IllegalStateException("Unexpected MCP mappings channel: " + mcpChannel + " (with version " + mcpVersion + ')');
		}

		AtomicBoolean escapedNames = new AtomicBoolean();
		Collection<ClassMapping> table = MappingReader.buildTable(allYarn, yarnSrg, mcpYarn, escapedNames);

		Remapper srgToYarn = new Remapper() {
			private final Map<String, String> yarnToSRG = table.stream().collect(Collectors.toMap(mapping -> mapping.srg, mapping -> mapping.yarn));

			@Override
			public String map(String internalName) {
				return yarnToSRG.getOrDefault(internalName, internalName);
			}
		};

		Entry<BiFunction<String, String, String[]>, BiFunction<String, String, String[]>> factories = extractParameters(mcpConfigZip, mcpZip, srgToYarn);
		BiFunction<String, String, String[]> srgParameterFactory = factories.getKey();
		BiFunction<String, String, String[]> mcpParameterFactory = factories.getValue();

		MappingWriter.writeTable(output, table, escapedNames.get(), srgParameterFactory, mcpParameterFactory);
	}

	private static Entry<BiFunction<String, String, String[]>, BiFunction<String, String, String[]>> extractParameters(Path mcpConfig, Path mcpZip, Remapper remapper) {
		Map<Integer, List<String>> parameters = extractParameters(mcpZip);
		/*Set<String> methods = new HashSet<>();

		try (JkPathTree tree = JkPathTree.ofZip(mcpZip); BufferedReader reader = Files.newBufferedReader(tree.get("methods.csv"))) {
			String header = reader.readLine();
			assert "searge,name,side,desc".equals(header);

			for (String line = reader.readLine(); line != null; line = reader.readLine()) {
				methods.add(line.substring(0, line.indexOf(',')));
			}
		} catch (IOException e) {
			throw new RuntimeException("Error reading methods file from " + mcpZip, e);
		}*/

		Set<String> staticMethods = new HashSet<>();
		Map<String, Integer> constructors = new HashMap<>();

		try (JkPathTree tree = JkPathTree.ofZip(mcpConfig)) {
			try (BufferedReader reader = Files.newBufferedReader(tree.get("config/static_methods.txt"))) {
				for (String line = reader.readLine(); line != null; line = reader.readLine()) {
					staticMethods.add(line);
				}
			} catch (IOException e) {
				throw new RuntimeException("Error reading static methods file from " + mcpConfig, e);
			}

			try (BufferedReader reader = Files.newBufferedReader(tree.get("config/constructors.txt"))) {
				for (String line = reader.readLine(); line != null; line = reader.readLine()) {
					int split = line.indexOf(' ');
					assert split > 0;

					String index = line.substring(0, split++);
					assert index.chars().allMatch(Character::isDigit);

					int descSplit = line.indexOf(' ', split);
					String nameDesc = line.substring(split, descSplit++) + remapper.mapMethodDesc(line.substring(descSplit));

					Integer existing = constructors.put(nameDesc, Integer.parseUnsignedInt(index));
					assert existing == null: "Duplicate for " + nameDesc + ": " + existing + " and " + index;
				}
			} catch (IOException e) {
				throw new RuntimeException("Error reading static methods file from " + mcpConfig, e);
			}
		}

		return new SimpleImmutableEntry<>((name, desc) -> {
			Type[] types = Type.getArgumentTypes(desc);
			if (types.length < 1) return new String[0];

			int index = name.startsWith("<init>") ? constructors.get(name.substring(6) + desc) : getMethodIndex(name);
			List<String> params = new ArrayList<>();

			for (int i = 0, arg = staticMethods.contains(name) ? 0 : 1; i < types.length; arg += types[i++].getSize()) {
				assert arg == params.size() || params.size() + 1 == arg;
				if (params.size() < arg) params.add(null);

				params.add(arg, index < 70000 ? "p_i" : "p_" + index + '_' + arg + '_');
			}

			return params.toArray(new String[0]);
		}, (name, desc) -> {
			int index = name.startsWith("<init>") ? constructors.get(name.substring(6) + desc) : getMethodIndex(name);

			Type[] types = Type.getArgumentTypes(desc);
			List<String> params = parameters.getOrDefault(index, new ArrayList<>());

			for (int i = 0, arg = staticMethods.contains(name) ? 0 : 1; i < types.length; arg += types[i++].getSize()) {
				while (params.size() <= arg) params.add(null);

				if (params.get(arg) == null) {
					params.set(arg, index < 70000 ? "p_i" : "p_" + index + '_' + arg + '_');
				}
			}

			return params.toArray(new String[0]);
		});
	}

	private static Map<Integer, List<String>> extractParameters(Path mcpZip) {
		Map<Integer, List<String>> out = new HashMap<>();

		try (JkPathTree tree = JkPathTree.ofZip(mcpZip); BufferedReader reader = Files.newBufferedReader(tree.get("params.csv"))) {
			String header = reader.readLine();
			assert "param,name,side".equals(header);

			for (String line = reader.readLine(); line != null; line = reader.readLine()) {
				int split = line.indexOf(',');
				assert split > 0;

				String parameterName = line.substring(0, split++);
				String name = line.substring(split, line.indexOf(',', split));

				assert parameterName.startsWith("p_") && parameterName.endsWith("_");
				parameterName = parameterName.substring(2, parameterName.length() - 1);

				split = parameterName.indexOf('_');
				assert split > 0;

				String srgIndex = parameterName.substring(parameterName.charAt(0) == 'i' ? 1 : 0, split);
				assert srgIndex.chars().allMatch(Character::isDigit): "Unexpected non-numerical digit in " + srgIndex;
				String argIndex = parameterName.substring(split + 1);
				assert argIndex.chars().allMatch(Character::isDigit);

				int method = Integer.parseUnsignedInt(/*"func_" + */srgIndex);
				List<String> args = out.computeIfAbsent(method, k -> new ArrayList<>());

				int index = Integer.parseUnsignedInt(argIndex);
				while (args.size() <= index) args.add(null);
				String existingName = args.set(index, name);
				assert existingName == null;
			}
		} catch (UncheckedIOException e) {
			throw new RuntimeException("Error reading params file from " + mcpZip, e.getCause());
		} catch (IOException e) {
			throw new RuntimeException("Error reading params file from " + mcpZip, e);
		}

		return out;
	}


}