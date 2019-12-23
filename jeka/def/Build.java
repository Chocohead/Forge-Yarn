import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.function.Function;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import dev.jeka.core.api.file.JkPathMatcher;
import dev.jeka.core.api.file.JkPathTree;
import dev.jeka.core.api.java.JkJavaProcess;
import dev.jeka.core.api.java.project.JkJavaProject;
import dev.jeka.core.api.system.JkLog;
import dev.jeka.core.api.system.JkLog.Verbosity;
import dev.jeka.core.api.utils.JkUtilsPath;
import dev.jeka.core.api.utils.JkUtilsString;
import dev.jeka.core.api.utils.JkUtilsXml;
import dev.jeka.core.tool.JkCommands;
import dev.jeka.core.tool.JkConstants;
import dev.jeka.core.tool.JkDoc;
import dev.jeka.core.tool.JkImport;
import dev.jeka.core.tool.JkImportRepo;

@JkImport("com.github.Chocohead:Mercury:2306992") //net.fabricmc:tiny-remapper:0.2.1.61 and org.cadixdev:mercury:0.1.1.fabric-SNAPSHOT
@JkImportRepo("https://jitpack.io") //From https://maven.fabricmc.net
class Build extends JkCommands {
	private static final String SETUP_DIR = JkConstants.JEKA_DIR + "/setup";
	private static final String FORGE_PATH = "*/.gradle/caches/forge_gradle/minecraft_user_repo/net/minecraftforge/forge/*_mapped_*/forge-*_mapped_*-recomp.jar";
	@JkDoc("Avoid increasing the logging level from mute when running Gradle")
	public boolean quietGradle; //Run with -quietGradle=true

	@Override
	protected void setup() {
		Path setupDir = getBaseDir().resolve(SETUP_DIR);

		boolean didFabric = doGradlePart(setupDir, merge -> {
			return JkPathTree.of(merge.resolve("includes")).andMatching("build-*-fabric.sh", "proguard-*-fabric.pro");
		}, 2, "Fabric", false, "eclipseClasspath", "--no-daemon");
		boolean didForge = doGradlePart(setupDir, merge -> {
			return JkPathTree.of(merge).andMatching("includes/build-*-forge.sh", "includes/build-*-forge-yarn.sh", "includes/proguard-*-forge.pro",
					"mappings/*-mcp-yarn.tiny", "mappings/*-yarn-srg.tiny", "remapped/mc-*-forge-srg.jar", "remapped/mc-*-forge-yarn.jar", "Forge.classpath");
		}, 8, "Forge", didFabric, "eclipseClasspath", "--no-daemon");

		Path classpath = setupDir.resolve("Merge/.classpath");
		if (Files.notExists(classpath) || didForge) {
			JkUtilsPath.copy(classpath.resolveSibling("Forge.classpath"), classpath, StandardCopyOption.REPLACE_EXISTING);

			Document xml = JkUtilsXml.documentFrom(classpath);
			NodeList classpathEntries = xml.getElementsByTagName("classpathentry");
			JkPathMatcher forgeMatcher = JkPathMatcher.of(FORGE_PATH);

			for (int i = 0; i < classpathEntries.getLength(); i++) {
				Node node = classpathEntries.item(i);
				assert node instanceof Element: "Unexpected node: " + node;

				Element classpathEntry = (Element) node;
				if ("lib".equals(classpathEntry.getAttribute("kind"))) {
					String path = classpathEntry.getAttribute("path");
					assert path != null;

					Path jar;
					if (forgeMatcher.matches(jar = Paths.get(path))) {
						String sources = classpathEntry.getAttribute("sourcepath");
						assert sources != null;

						String jarName = jar.getFileName().toString();
						assert JkUtilsString.countOccurence(jarName, '-') == 4;
						String version = jarName.substring(6, jarName.indexOf('-', 7));

						Path remappedJar = classpath.resolveSibling("remapped/mc-" + version + "-forge-yarn.jar");
						Path mappings = classpath.resolveSibling("mappings/" + version + "-mcp-yarn.tiny");

						Path remappedSources = remappedJar.resolveSibling("mc-" + version + "-forge-yarn-sources.jar");
						remappedSources(jar, mappings, remappedSources);

						classpathEntry.setAttribute("path", remappedJar.toAbsolutePath().toString());
						classpathEntry.setAttribute("sourcepath", remappedSources.toAbsolutePath().toString());
						break;
					}
				}
			}
		}
	}

	private boolean doGradlePart(Path setupDir, Function<Path, JkPathTree> expectedResult, int results, String name, boolean force, String... args) {
		Path merge = setupDir.resolve("Merge");
		JkUtilsPath.createDirectories(merge.resolve("includes"));
		JkUtilsPath.createDirectories(merge.resolve("remapped"));
		JkPathTree expectedResults = expectedResult.apply(merge);

		Path settings = setupDir.resolve(name);
		Path hashes = setupDir.resolve(name + "-hashes.txt");

		if (force || expectedResults.count(results, false) != results || !checkHashes(settings, hashes)) {
			expectedResults.deleteContent();
			JkUtilsPath.deleteIfExists(hashes);

			Path build = setupDir.resolve("Build");

			//Move the stuff over needed for building
			JkPathTree.of(settings).copyTo(build, StandardCopyOption.COPY_ATTRIBUTES);

			boolean increasedLogging = false;
			if (!quietGradle && JkLog.verbosity() == Verbosity.MUTE) {
				//When running without -LH (log headers) nothing will get logged from Gradle's output
				increasedLogging = true;
				JkLog.setVerbosity(Verbosity.NORMAL);
			}

			//Invoke Gradle, hope it does what it's meant to
			JkJavaProcess.of().withWorkingDir(build).withClasspath(build.resolve("gradle/wrapper/gradle-wrapper.jar"))
			.andOptions("-Dorg.gradle.appname=Build").runClassSync("org.gradle.wrapper.GradleWrapperMain", args);

			JkLog.startTask("Starting extractor");
			ClassPathExtractor.main(name, build.toAbsolutePath(), merge.toAbsolutePath());
			JkLog.endTask();

			//Remember to set the logging back if it was changed
			if (increasedLogging) JkLog.setVerbosity(Verbosity.MUTE);

			//Save the hashes now the build has completed
			saveHashes(settings, hashes);

			//Clean up the stuff used for building
			JkPathTree.of(build).andMatching(false, "gradle/**").deleteContent();

			return true;
		}

		return false;
	}

	private static boolean checkHashes(Path directory, Path hashSave) {
		if (Files.notExists(hashSave)) return false;

		assert Files.isReadable(hashSave);
		assert Files.isDirectory(directory);

		try (BufferedReader reader = Files.newBufferedReader(hashSave)) {
			for (String line = reader.readLine(); line != null; line = reader.readLine()) {
				int split = line.lastIndexOf(':');
				assert split > 0;

				String resource = line.substring(0, split);
				String hash = line.substring(split + 1);

				Path file = directory.resolve(resource);
				if (Files.notExists(file)) return false; //It's vanished

				assert Files.isReadable(file);
				if (!hash.equals(Hashing.SHA1(file))) return false; //Hash change
			}
		} catch (IOException | UncheckedIOException e) {
			throw new RuntimeException("Error reading hash save file at " + hashSave + " for " + directory, e);
		}

		return true;
	}

	private static void saveHashes(Path directory, Path hashSave) {
		assert Files.notExists(hashSave);
		assert Files.isDirectory(directory);

		try (BufferedWriter writer = Files.newBufferedWriter(hashSave)) {
			for (Path file : JkPathTree.of(directory).getFiles()) {
				assert Files.isReadable(file);

				writer.write(directory.relativize(file).toString());
				writer.write(':');
				writer.write(Hashing.SHA1(file));
				writer.newLine();
			}
		} catch (IOException | UncheckedIOException e) {
			throw new RuntimeException("Error writing hash save file at " + hashSave + " for " + directory, e);
		}
	}

	private static void remappedSources(Path input, Path mappings, Path output) {
		JkUtilsPath.deleteIfExists(output);


	}
}