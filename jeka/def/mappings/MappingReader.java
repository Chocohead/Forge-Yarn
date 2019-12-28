package mappings;

import java.io.BufferedReader;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.AbstractMap.SimpleImmutableEntry;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;
import java.util.zip.GZIPInputStream;

import net.fabricmc.tinyremapper.IMappingProvider.MappingAcceptor;
import net.fabricmc.tinyremapper.IMappingProvider.Member;
import net.fabricmc.tinyremapper.TinyUtils;
import net.fabricmc.tinyremapper.asm.commons.Remapper;

public class MappingReader {
	static class Mapping {
		final String yarn;
		String notch, intermediary, srg, mcp;

		Mapping(String yarn) {
			this.yarn = yarn;
		}
	}

	static class DescriptedMapping extends Mapping {
		final String yarnDesc;

		DescriptedMapping(Entry<String, String> combo) {
			this(combo.getKey(), combo.getValue());
		}

		DescriptedMapping(String yarnName, String yarnDesc) {
			super(yarnName);

			this.yarnDesc = yarnDesc;
		}
	}

	static class MethodMapping extends DescriptedMapping {
		private String[] yarnParams/*, srgParams, mcpParams*/;

		MethodMapping(Entry<String, String> combo) {
			super(combo);
		}

		void giveYarnParameters(String[] parameters) {
			yarnParams = parameters;
		}

		String getYarnParameter(int index) {
			assert index >= 0;
			return yarnParams == null || index >= yarnParams.length ? null : yarnParams[index];
		}

		String[] getYarnParameters() {
			return yarnParams;
		}

		/*void addSRGParameter(int index, String name) {
			String[] params;
			if (srgParams == null) {
				srgParams = params = new String[index + 1];
			} else if (srgParams.length <= index) {
				srgParams = params = Arrays.copyOf(srgParams, index + 1);
			} else {
				params = srgParams;
			}

			params[index] = name;
		}

		String[] getSRGParameters() {
			return srgParams;
		}

		void addMCPParameter(int index, String name) {
			String[] params;
			if (mcpParams == null) {
				mcpParams = params = new String[index + 1];
			} else if (mcpParams.length <= index) {
				mcpParams = params = Arrays.copyOf(mcpParams, index + 1);
			} else {
				params = mcpParams;
			}

			params[index] = name;
		}

		String[] getMCPParameters() {
			return mcpParams;
		}*/
	}

	static class ClassMapping extends Mapping {
		private final Map<Entry<String, String>, MethodMapping> methods = new HashMap<>();
		private final Map<Entry<String, String>, DescriptedMapping> fields = new HashMap<>();

		ClassMapping(String yarn) {
			super(yarn);
		}

		MethodMapping addMethod(String name, String desc) {
			return methods.computeIfAbsent(new SimpleImmutableEntry<>(name, desc), MethodMapping::new);
		}

		Collection<MethodMapping> getMethods() {
			return methods.values();
		}

		DescriptedMapping addField(String name, String desc) {
			return fields.computeIfAbsent(new SimpleImmutableEntry<>(name, desc), DescriptedMapping::new);
		}

		Collection<DescriptedMapping> getFields() {
			return fields.values();
		}
	}

	private abstract static class MappingMapper implements MappingAcceptor {
		protected final Map<String, ClassMapping> mappings;

		protected MappingMapper(Map<String, ClassMapping> mappings) {
			this.mappings = mappings;
		}

		private ClassMapping getMapping(String className) {
			return mappings.computeIfAbsent(className, ClassMapping::new);
		}

		@Override
		public void acceptClass(String srcName, String dstName) {
			acceptClass(getMapping(srcName), dstName);
		}

		protected abstract void acceptClass(ClassMapping mapping, String name);

		@Override
		public void acceptMethod(Member method, String dstName) {
			acceptMethod(getMapping(method.owner).addMethod(method.name, method.desc), dstName);
		}

		protected abstract void acceptMethod(MethodMapping mapping, String name);

		@Override
		public void acceptMethodArg(Member method, int lvIndex, String dstName) {
			assert false;
		}

		@Override
		public void acceptMethodVar(Member method, int lvIndex, int startOpIdx, int asmIndex, String dstName) {
			assert false;
		}

		@Override
		public void acceptField(Member field, String dstName) {
			acceptField(getMapping(field.owner).addField(field.name, field.desc), dstName);
		}

		protected abstract void acceptField(DescriptedMapping mapping, String name);
	}

	public static Collection<ClassMapping> buildTable(Path allYarn, Path yarnSrg, Path mcpYarn, AtomicBoolean escapedNames) {
		Map<String, ClassMapping> out = new HashMap<>();

		TinyUtils.createTinyMappingProvider(allYarn, "named", "official").load(new MappingMapper(out) {
			@Override
			protected void acceptClass(ClassMapping mapping, String name) {
				mapping.notch = name;
			}

			@Override
			protected void acceptMethod(MethodMapping mapping, String name) {
				mapping.notch = name;
			}

			@Override
			protected void acceptField(DescriptedMapping mapping, String name) {
				mapping.notch = name;
			}
		});
		TinyUtils.createTinyMappingProvider(allYarn, "named", "intermediary").load(new MappingMapper(out) {
			@Override
			protected void acceptClass(ClassMapping mapping, String name) {
				mapping.intermediary = name;
			}

			@Override
			protected void acceptMethod(MethodMapping mapping, String name) {
				mapping.intermediary = name;
			}

			@Override
			protected void acceptField(DescriptedMapping mapping, String name) {
				mapping.intermediary = name;
			}
		});
		escapedNames.set(readParameters(allYarn, "named", (method, locals) -> {
			out.computeIfAbsent(method.owner, ClassMapping::new).addMethod(method.name, method.desc).giveYarnParameters(locals);
		}));

		try (BufferedReader reader = new BufferedReader(new InputStreamReader(mcpYarn.getFileName().toString().endsWith(".gz") ?
				new GZIPInputStream(Files.newInputStream(mcpYarn)) : Files.newInputStream(mcpYarn), StandardCharsets.UTF_8)) {
			@Override
			public String readLine() throws IOException {
				String line = super.readLine();

				if (line != null && !line.isEmpty() && line.charAt(0) == 'c') {
					String[] bits = splitAtTab(line, 2, 2);

					assert bits.length == 2: "Unexpected number of bits: " + Arrays.toString(bits);
					if (!bits[0].isEmpty() && bits[1].isEmpty()) {
						assert ("c\t" + bits[0] + '\t').equals(line): "Unexpected line contents: " + line;
						line += bits[0];
					}
				}

				return line;
			}
		}) {
			TinyUtils.createTinyMappingProvider(reader, "named", "mcp").load(new MappingMapper(out) {
				@Override
				protected void acceptClass(ClassMapping mapping, String name) {
					mapping.mcp = name;
				}

				@Override
				protected void acceptMethod(MethodMapping mapping, String name) {
					mapping.mcp = name;
				}

				@Override
				protected void acceptField(DescriptedMapping mapping, String name) {
					mapping.mcp = name;
				}
			});
		} catch (IOException e) {
			throw new RuntimeException("Error reading " + mcpYarn, e);
		}

		TinyUtils.createTinyMappingProvider(yarnSrg, "named", "srg").load(new MappingMapper(out) {
			@Override
			protected void acceptClass(ClassMapping mapping, String name) {
				mapping.srg = name;
			}

			@Override
			protected void acceptMethod(MethodMapping mapping, String name) {
				mapping.srg = name;
			}

			@Override
			protected void acceptField(DescriptedMapping mapping, String name) {
				mapping.srg = name;
			}
		});

		return out.values();
	}

	private static boolean readParameters(Path file, String paramNamespace, BiConsumer<Member, String[]> localMappingConsumer) {
		try (BufferedReader reader = file.getFileName().toString().endsWith(".gz") ?
				new BufferedReader(new InputStreamReader(new GZIPInputStream(Files.newInputStream(file)), StandardCharsets.UTF_8)) : Files.newBufferedReader(file)) {
			return readParameters(reader, paramNamespace, localMappingConsumer);
		} catch (IOException e) {
			throw new RuntimeException("Error reading " + file, e);
		}
	}

	private static boolean readParameters(BufferedReader reader, String paramNamespace, BiConsumer<Member, String[]> localMappingConsumer) throws IOException {
		String headerLine = reader.readLine();

		if (headerLine == null) {
			throw new EOFException();
		} else if (headerLine.startsWith("v1\t")) {
			return false; //V1 Tiny (Yarn) files don't have parameters so there's nothing to read
		} else if (!headerLine.startsWith("tiny\t2\t")) {
			throw new IOException("Invalid mapping version: \"" + headerLine + '"');
		}

		String[] parts;
		if ((parts = splitAtTab(headerLine, 0, 5)).length < 5) { //min. tiny + major version + minor version + 2 name spaces
			throw new IOException("Invalid/unsupported tiny file (incorrect header)");
		}

		List<String> namespaces = Arrays.asList(parts).subList(3, parts.length);
		int ns = namespaces.indexOf(paramNamespace);
		assert ns >= 0;
		Map<String, String> obfFrom = ns != 0 ? new HashMap<>() : null;

		Map<Member, String[]> locals = new HashMap<>();

		int partCountHint = 2 + namespaces.size(); // suitable for members, which should be the majority
		boolean escapedNames = false;

		boolean inHeader = true;
		boolean inClass = false;
		boolean inMethod = false;

		String className = null;
		Member member = null;

		int lineNumber = 1;
		for (String line = reader.readLine(); line != null; line = reader.readLine(), lineNumber++) {
			if (line.isEmpty()) continue;

			int indent = 0;
			while (indent < line.length() && line.charAt(indent) == '\t') {
				indent++;
			}

			parts = splitAtTab(line, indent, partCountHint);
			String section = parts[0];

			if (indent == 0) {
				inHeader = inClass = inMethod = false;

				if ("c".equals(section)) { // class: c <names>...
					if (parts.length != namespaces.size() + 1) throw new IOException("Invalid class declaration on line " + lineNumber);

					className = unescapeOpt(parts[1 + ns], escapedNames);

					if (obfFrom != null) {
						obfFrom.put(unescapeOpt(parts[1], escapedNames), className);
					}

					inClass = true;
				}
			} else if (indent == 1) {
				inMethod = false;

				if (inHeader) { // header k/v
					if ("escaped-names".equals(section)) {
						escapedNames = true;
					}
				} else if (inClass && ("m".equals(section) || "f".equals(section))) { // method/field: m/f <descA> <names>...
					boolean isMethod = "m".equals(section);
					if (parts.length != namespaces.size() + 2) throw new IOException("Invalid " + (isMethod ? "metho" : "fiel") + "d declaration on line " + lineNumber);

					String memberDesc = unescapeOpt(parts[1], escapedNames);
					String memberName = unescapeOpt(parts[2 + ns], escapedNames);
					member = new Member(className, memberName, memberDesc);
					inMethod = isMethod;
				}
			} else if (indent == 2) {
				if (inMethod && "p".equals(section)) { // method parameter: p <lv-index> <names>...
					if (parts.length != namespaces.size() + 2) throw new IOException("Invalid method parameter declaration on line " + lineNumber);

					String mappedName = unescapeOpt(parts[2 + ns], escapedNames);
					if (!mappedName.isEmpty()) {
						int varLvIndex = Integer.parseInt(parts[1]);

						String[] methodLocals = locals.get(member);
						if (methodLocals == null || methodLocals.length <= varLvIndex) {
							String[] longerLocals = new String[varLvIndex + 1];
							if (methodLocals != null) System.arraycopy(methodLocals, 0, longerLocals, 0, methodLocals.length);
							locals.put(member, methodLocals = longerLocals);
						}

						assert methodLocals[varLvIndex] == null;
						methodLocals[varLvIndex] = mappedName;
					}
				} else if (inMethod && "v".equals(section)) { // method variable: v <lv-index> <lv-start-offset> <optional-lvt-index> <names>...
					if (parts.length != namespaces.size() + 4) throw new IOException("Invalid method variable declaration on line " + lineNumber);

					String mappedName = unescapeOpt(parts[4 + ns], escapedNames);
					if (!mappedName.isEmpty()) {
						int varLvIndex = Integer.parseInt(parts[1]);
						int varStartOpIdx = Integer.parseInt(parts[2]);
						int varLvtIndex = Integer.parseInt(parts[3]);

						//Don't currently support this as it stands, neither does Yarn so it could be worse
						throw new UnsupportedOperationException(String.format("%1$s local %2$d: %5$s, start @ %3$d, index %4$d", member, varLvIndex, varStartOpIdx, varLvtIndex, mappedName));
					}
				}
			}
		}

		Remapper remapper = new Remapper() {
			@Override
			public String map(String typeName) {
				return obfFrom.getOrDefault(typeName, typeName);
			}
		};

		for (Entry<Member, String[]> entry : locals.entrySet()) {
			Member mapping = entry.getKey();

			assert mapping.owner.equals(remapper.map(mapping.owner)); //Owner shouldn't be wrong
			mapping.desc = remapper.mapDesc(mapping.desc);

			localMappingConsumer.accept(mapping, entry.getValue());
		}

		return escapedNames;
	}

	static String[] splitAtTab(String s, int offset, int partCountHint) {
		String[] out = new String[Math.max(1, partCountHint)];
		int partCount = 0;

		int pos;
		while ((pos = s.indexOf('\t', offset)) >= 0) {
			if (partCount == out.length) out = Arrays.copyOf(out, out.length * 2);
			out[partCount++] = s.substring(offset, pos);
			offset = pos + 1;
		}

		if (partCount == out.length) out = Arrays.copyOf(out, out.length + 1);
		out[partCount++] = s.substring(offset);

		return partCount == out.length ? out : Arrays.copyOf(out, partCount);
	}

	private static String unescapeOpt(String str, boolean escapedNames) {
		return escapedNames ? unescape(str) : str;
	}

	private static String unescape(String str) {
		int pos = str.indexOf('\\');
		if (pos < 0) return str;

		StringBuilder out = new StringBuilder(str.length() - 1);
		int start = 0;

		do {
			out.append(str, start, pos++);

			int type;
			if (pos >= str.length()) {
				throw new RuntimeException("Incomplete escape sequence at the end");
			} else if ((type = ESCAPED.indexOf(str.charAt(pos))) < 0) {
				throw new RuntimeException("Invalid escape character: \\" + str.charAt(pos));
			} else {
				out.append(TO_ESCAPE.charAt(type));
			}

			start = pos + 1;
		} while ((pos = str.indexOf('\\', start)) >= 0);

		return out.append(str, start, str.length()).toString();
	}

	static final String TO_ESCAPE = "\\\n\r\0\t";
	static final String ESCAPED = "\\nr0t";
}