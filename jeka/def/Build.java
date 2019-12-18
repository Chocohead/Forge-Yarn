import java.io.BufferedReader;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

import dev.jeka.core.api.file.JkPathTree;
import dev.jeka.core.api.java.JkJavaProcess;
import dev.jeka.core.api.utils.JkUtilsPath;
import dev.jeka.core.tool.JkCommands;
import dev.jeka.core.tool.JkConstants;
import dev.jeka.core.tool.JkImport;

@JkImport("libs/*jar")
class Build extends JkCommands {
	private static final String SETUP_DIR = JkConstants.JEKA_DIR + "/setup";

	@Override
	protected void setup() {
		Path setupDir = getBaseDir().resolve(SETUP_DIR);

		doGradlePart(setupDir, "Fabric", "eclipse", "--no-daemon");
		//doGradlePart(setupDir, "Forge", "setup", "eclipse", "--no-daemon");
	}

	private static void doGradlePart(Path setupDir, String name, String... args) {
		Path classpath = setupDir.resolve(name + ".classpath");
		Path settings = setupDir.resolve(name);
		Path hashes = setupDir.resolve(name + "-hashes.txt");

		if (Files.notExists(classpath) || !checkHashes(settings, hashes)) {
			JkUtilsPath.deleteIfExists(hashes);
			Path build = setupDir.resolve("Build");

			//Move the stuff over needed for building
			JkPathTree.of(settings).copyTo(build, StandardCopyOption.COPY_ATTRIBUTES);

			//Invoke Gradle, hope it does what it's meant to
			JkJavaProcess.of().withWorkingDir(build).withClasspath(build.resolve("gradle/wrapper/gradle-wrapper.jar"))
			.andOptions("-Dorg.gradle.appname=Build").runClassSync("org.gradle.wrapper.GradleWrapperMain", args);

			Path merge = setupDir.resolve("Merge");
			ClassPathExtractor.main(new String[] {build.toAbsolutePath().toString(),
					merge.resolve("remap.jar").toAbsolutePath().toString(), merge.toAbsolutePath().toString()});
		}
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
}