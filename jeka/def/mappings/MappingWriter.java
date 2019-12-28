package mappings;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.StringJoiner;
import java.util.function.BiFunction;

import mappings.MappingReader.ClassMapping;
import mappings.MappingReader.DescriptedMapping;
import mappings.MappingReader.Mapping;
import mappings.MappingReader.MethodMapping;

public class MappingWriter {
	public static void writeTable(Path to, Collection<ClassMapping> table, boolean escapedNames,
			BiFunction<String, String, String[]> srgParameterFactory, BiFunction<String, String, String[]> mcpParameterFactory) {
		try (BufferedWriter writer = Files.newBufferedWriter(to)) {
			writer.write("tiny\t2\t0\tnamed\tintermediary\tofficial\tsrg\tmcp");
			writer.newLine();

			if (escapedNames) {
				writer.write("\tescaped-names");
				writer.newLine();
			}

			for (ClassMapping classMapping : table) {
				assert allPresent(classMapping): asString(classMapping);

				writer.write('c');
				writeAll(writer, escapedNames, classMapping);

				for (MethodMapping methodMapping : classMapping.getMethods()) {
					boolean isConstructor;
					if (isConstructor = "<init>".equals(methodMapping.yarn)) {
						assert methodMapping.yarn.equals(methodMapping.intermediary) && methodMapping.yarn.equals(methodMapping.notch);
						methodMapping.srg = methodMapping.mcp = methodMapping.yarn;
					} else if (methodMapping.mcp == null) {
						methodMapping.mcp = methodMapping.srg;
					}

					assert allPresent(methodMapping): asString(methodMapping);

					writer.write("\tm");
					writeAll(writer, escapedNames, methodMapping);

					if (isConstructor) {
						assert "<init>".equals(methodMapping.mcp) && "<init>".equals(methodMapping.srg);
						assert classMapping.srg.equals(classMapping.mcp);
						methodMapping.srg = methodMapping.mcp += classMapping.srg;
					}

					//The descriptor mappings don't especially matter, it's just for spreading the parameter indexes
					String[] srgArgs = srgParameterFactory.apply(methodMapping.srg, methodMapping.yarnDesc);
					if (srgArgs.length > 0) {
						String[] mcpArgs = mcpParameterFactory.apply(methodMapping.mcp, methodMapping.yarnDesc);
						assert srgArgs.length == mcpArgs.length;

						for (int arg = 0; arg < srgArgs.length; arg++) {
							String srgArg = srgArgs[arg];
							if (srgArg == null) {
								assert methodMapping.getYarnParameter(arg) == null;
								continue;
							}

							String yarnArg = methodMapping.getYarnParameter(arg);

							writer.write("\t\tp\t");
							writer.write(Integer.toString(arg));
							writer.write('\t');
							if (yarnArg != null) escapedWrite(writer, escapedNames, yarnArg);
							writer.write("\t\t\t");
							escapedWrite(writer, escapedNames, srgArg);
							writer.write('\t');
							escapedWrite(writer, escapedNames, mcpArgs[arg]);
							writer.newLine();
						}
					}
				}

				for (DescriptedMapping fieldMapping : classMapping.getFields()) {
					if (fieldMapping.mcp == null) fieldMapping.mcp = fieldMapping.srg;
					assert allPresent(fieldMapping): asString(fieldMapping);

					writer.write("\tf");
					writeAll(writer, escapedNames, fieldMapping);
				}
			}
		} catch (IOException e) {
			throw new RuntimeException("Error writing to " + to, e);
		}
	}

	private static boolean allPresent(Mapping mapping) {
		return mapping.yarn != null && mapping.intermediary != null && mapping.notch != null && mapping.srg != null && mapping.mcp != null;
	}

	private static String asString(Mapping mapping) {
		return new StringJoiner(", ").add(mapping.yarn).add(mapping.intermediary).add(mapping.notch).add(mapping.srg).add(mapping.mcp).toString();
	}

	private static void writeAll(BufferedWriter writer, boolean escape, Mapping mapping) throws IOException {
		escapedWrite(writer, escape, mapping.yarn);
		writer.write('\t');
		escapedWrite(writer, escape, mapping.intermediary);
		writer.write('\t');
		escapedWrite(writer, escape, mapping.notch);
		writer.write('\t');
		escapedWrite(writer, escape, mapping.srg);
		writer.write('\t');
		escapedWrite(writer, escape, mapping.mcp);
		writer.newLine();
	}

	private static void escapedWrite(BufferedWriter writer, boolean escape, String text) throws IOException {
		if (escape) {
			escapedWrite(writer, text);
		} else {
			writer.write(text);
		}
	}

	private static void escapedWrite(BufferedWriter writer, String text) throws IOException {
		int len = text.length();
		int start = 0;

		for (int pos = 0; pos < len; pos++) {
			int index = MappingReader.TO_ESCAPE.indexOf(text.charAt(pos));

			if (index >= 0) {
				writer.write(text, start, pos - start);
				writer.write('\\');
				writer.write(MappingReader.ESCAPED.charAt(index));

				start = pos + 1;
			}
		}

		writer.write(text, start, len - start);
	}
}