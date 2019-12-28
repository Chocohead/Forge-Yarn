package mappings;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
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
				if (classMapping.mcp == null) {
					assert classMapping.yarn.equals(classMapping.srg);
					classMapping.mcp = classMapping.srg;
				}

				assert allPresent(classMapping): asString(classMapping);

				writer.write("c\t");
				writeAll(writer, escapedNames, classMapping);

				for (MethodMapping methodMapping : classMapping.getMethods()) {
					boolean isConstructor;
					if (isConstructor = "<init>".equals(methodMapping.yarn)) {
						assert methodMapping.yarn.equals(methodMapping.intermediary) && methodMapping.yarn.equals(methodMapping.notch);
						methodMapping.srg = methodMapping.mcp = methodMapping.yarn;
					} else if (methodMapping.intermediary.equals(methodMapping.notch) && !methodMapping.yarn.equals(methodMapping.intermediary)) {
						System.out.println(new StringJoiner(", ", "Invalid Yarn entry: ", "").add(classMapping.intermediary + '/' + methodMapping.notch)
								.add(methodMapping.intermediary).add(methodMapping.yarn)); //This appears to come from bridge methods
						continue;
					} else if (methodMapping.mcp == null) {
						methodMapping.mcp = methodMapping.srg;
					}

					assert allPresent(methodMapping): asString(methodMapping);

					writer.write("\tm\t");
					writeAll(writer, escapedNames, methodMapping);

					if (isConstructor) {
						assert "<init>".equals(methodMapping.mcp) && "<init>".equals(methodMapping.srg);
						assert classMapping.srg.equals(classMapping.mcp);
						methodMapping.srg = methodMapping.mcp += classMapping.srg;
					}

					//The descriptor mappings don't especially matter, it's just for spreading the parameter indexes
					String[] srgArgs = srgParameterFactory.apply(methodMapping.srg, methodMapping.yarnDesc);
					if (srgArgs.length > 0) {
						String[] mcpArgs = mcpParameterFactory.apply(methodMapping.srg, methodMapping.yarnDesc);
						assert srgArgs.length == mcpArgs.length || allowInvalidMCPParam(mcpArgs):
							"Unequal arg lengths for " + classMapping.yarn + '/' + methodMapping.yarn + ": " + Arrays.toString(srgArgs) + " and " + Arrays.toString(mcpArgs);

						for (int arg = 0; arg < srgArgs.length; arg++) {
							String srgArg = srgArgs[arg];
							out: if (srgArg == null) {
								if (classMapping.yarn.startsWith("com/mojang/realmsclient/") || classMapping.yarn.startsWith("com/mojang/blaze3d/")) {
									String yarnArg = methodMapping.getYarnParameter(arg);
									assert yarnArg == null || arg == 0:
										classMapping.yarn + '/' + methodMapping.yarn + " arg " + arg + " (" + isConstructor + "), had " + Arrays.toString(srgArgs);

									if (yarnArg != null) {
										assert srgArgs.length > arg + 1;
										mcpArgs[arg] = srgArg = srgArgs[arg + 1].substring(0, srgArgs[arg + 1].length() - 2) + arg + '_';
										break out;
									}
								} else {
									assert methodMapping.getYarnParameter(arg) == null:
										"No SRG arg for " + classMapping.yarn + '/' + methodMapping.yarn + " but Yarn arg was " + methodMapping.getYarnParameter(arg);
								}
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

					writer.write("\tf\t");
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

	private static boolean allowInvalidMCPParam(String[] mcpArgs) {
		switch (mcpArgs.length) {
		case 2:
			return "p_71377_0_".equals(mcpArgs[0]) && "crashReportIn".equals(mcpArgs[1]) || "p_178304_0_".equals(mcpArgs[0]) && "hash".equals(mcpArgs[1])
					|| "p_181679_0_".equals(mcpArgs[0]) && "bufferBuilderIn".equals(mcpArgs[1]) || "p_148075_0_".equals(mcpArgs[0]) && "manager".equals(mcpArgs[1])
					|| "p_148077_0_".equals(mcpArgs[0]) && "manager".equals(mcpArgs[1]);

		case 3:
			return "p_180438_0_".equals(mcpArgs[0]) && "entitylivingbaseIn".equals(mcpArgs[1]) && "partialTicks".equals(mcpArgs[2]);

		case 5:
			return "p_189553_0_".equals(mcpArgs[0]) && "blockaccessIn".equals(mcpArgs[1]) && "x".equals(mcpArgs[2]) && "y".equals(mcpArgs[3]) && "z".equals(mcpArgs[4]);

		case 6:
			return "p_193578_0_".equals(mcpArgs[0]) && "blockaccessIn".equals(mcpArgs[1]) && "x".equals(mcpArgs[2]) &&
					"y".equals(mcpArgs[3]) && "z".equals(mcpArgs[4]) && "nodeType".equals(mcpArgs[5]);

		default:
			return false;
		}
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