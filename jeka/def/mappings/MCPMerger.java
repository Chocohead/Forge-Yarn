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
	static String getMethodIndex(String function) {
		if (!function.startsWith("func_")) {
			assert !"<init>".equals(function);
			return "p_" + function;
		}

		//assert function.indexOf('_', 5) == function.lastIndexOf('_', function.length() - 2): function; //Breaks with Notch names which end in _
		String index = function.substring(5, function.indexOf('_', 5));
		return (Integer.parseUnsignedInt(index) < 70000 ? "p_i" : "p_") + index;
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
		Map<String, List<String>> parameters = extractParameters(mcpZip);
		Set<String> staticMethods = new HashSet<>();
		Map<String, String> constructors = new HashMap<>();

		try (JkPathTree tree = JkPathTree.ofZip(mcpConfig)) {
			try (BufferedReader reader = Files.newBufferedReader(tree.get("config/static_methods.txt"))) {
				for (String line = reader.readLine(); line != null; line = reader.readLine()) {
					staticMethods.add(line);
				}

				staticMethods.add("main"); //net/minecraft/server/MinecraftServer#main and net/minecraft/client/main/Main#main
				staticMethods.add("valueOf"); //net/minecraft/client/util/TextFormat#valueOf
				staticMethods.add("wrapScreenError"); //net/minecraft/client/gui/screen/Screen#wrapScreenError
				staticMethods.add("isCut"); //net/minecraft/client/gui/screen/Screen#isCut
				staticMethods.add("isPaste"); //net/minecraft/client/gui/screen/Screen#isPaste
				staticMethods.add("isSelectAll"); //net/minecraft/client/gui/screen/Screen#isSelectAll
				staticMethods.add("isCopy"); //net/minecraft/client/gui/screen/Screen#isCopy
				staticMethods.add("innerBlit"); //net/minecraft/client/gui/DrawableHelper#innerBlit
				staticMethods.add("blit"); //net/minecraft/client/gui/DrawableHelper#blit
				staticMethods.add("fill"); //net/minecraft/client/gui/DrawableHelper#fill
				staticMethods.add("getErrorString"); //com/mojang/blaze3d/platform/GLX#getErrorString
				staticMethods.add("_shouldClose"); //com/mojang/blaze3d/platform/GLX#_shouldClose
				staticMethods.add("make"); //com/mojang/blaze3d/platform/GLX#make
				staticMethods.add("_init"); //com/mojang/blaze3d/platform/GLX#_init
				staticMethods.add("_getRefreshRate"); //com/mojang/blaze3d/platform/GLX#_getRefreshRate
				staticMethods.add("_renderCrosshair"); //com/mojang/blaze3d/platform/GLX#_renderCrosshair
				staticMethods.add("_setGlfwErrorCallback"); //com/mojang/blaze3d/platform/GLX#_setGlfwErrorCallback

				staticMethods.add("ortho"); //com/mojang/blaze3d/systems/RenderSystem#ortho
				staticMethods.add("translated"); //com/mojang/blaze3d/systems/RenderSystem#translated
				staticMethods.add("scaled"); //com/mojang/blaze3d/systems/RenderSystem#scaled
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

					assert index.chars().allMatch(Character::isDigit);
					String existing = constructors.put(nameDesc, index);
					assert existing == null: "Duplicate for " + nameDesc + ": " + existing + " and " + index;
				}
			} catch (IOException e) {
				throw new RuntimeException("Error reading static methods file from " + mcpConfig, e);
			}
		}

		return new SimpleImmutableEntry<>((name, desc) -> {
			Type[] types = Type.getArgumentTypes(desc);
			if (types.length < 1) return new String[0];

			String index = name.startsWith("<init>") ? constructors.get(name.substring(6) + desc) : getMethodIndex(name);
			assert index != null;
			List<String> params = new ArrayList<>();

			for (int i = 0, arg = staticMethods.contains(name) ? 0 : 1; i < types.length; arg += types[i++].getSize()) {
				assert arg == params.size() || params.size() + 1 == arg;
				if (params.size() < arg) params.add(null);

				params.add(arg, index + '_' + arg + '_');
			}

			return params.toArray(new String[0]);
		}, (name, desc) -> {
			String index = name.startsWith("<init>") ? constructors.get(name.substring(6) + desc) : getMethodIndex(name);
			assert index != null;

			Type[] types = Type.getArgumentTypes(desc);
			List<String> params = parameters.getOrDefault(index, new ArrayList<>());

			for (int i = 0, arg = staticMethods.contains(name) ? 0 : 1; i < types.length; arg += types[i++].getSize()) {
				while (params.size() <= arg) params.add(null);

				if (params.get(arg) == null) {
					params.set(arg, index + '_' + arg + '_');
				}
			}

			return params.toArray(new String[0]);
		});
	}

	private static Map<String, List<String>> extractParameters(Path mcpZip) {
		Map<String, List<String>> out = new HashMap<>();

		try (JkPathTree tree = JkPathTree.ofZip(mcpZip); BufferedReader reader = Files.newBufferedReader(tree.get("params.csv"))) {
			String header = reader.readLine();
			assert "param,name,side".equals(header);

			for (String line = reader.readLine(); line != null; line = reader.readLine()) {
				int split = line.indexOf(',');
				assert split > 0;

				String parameterName = line.substring(0, split++);
				String name = line.substring(split, line.indexOf(',', split));

				assert parameterName.startsWith("p_") && parameterName.endsWith("_");
				split = parameterName.indexOf('_', 2);
				assert split > 0;

				String srgIndex = parameterName.substring(0, split); //Assert might not hold in future if deobf'd SRG names gain MCP parameter names
				assert srgIndex.chars().skip(srgIndex.charAt(2) == 'i' ? 3 : 2).allMatch(Character::isDigit): "Unexpected non-numerical digit in " + srgIndex;
				List<String> args = out.computeIfAbsent(srgIndex, k -> new ArrayList<>());

				String argIndex = parameterName.substring(split + 1, parameterName.length() - 1);
				assert argIndex.chars().allMatch(Character::isDigit): "Unexpected non-numerical digit in " + argIndex;
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