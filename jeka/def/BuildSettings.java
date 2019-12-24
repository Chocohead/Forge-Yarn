import java.io.BufferedReader;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Collectors;

public class BuildSettings {
	public final Set<Path> classpath;
	public final Path mappingFile, mcFile;
	public final String mcVersion, sourceNs, targetNs;

	public BuildSettings(Path file) {
		Set<Path> classpath = null;
		Path mappingFile = null, mcFile = null;
		String mcVersion = null, sourceNs = null, targetNs = null;

		try (BufferedReader reader = Files.newBufferedReader(file)) {
			boolean readOn = false;
			String rolledName = null;
			StringBuilder rollOn = new StringBuilder();

			for (String line = reader.readLine(); line != null; line = reader.readLine()) {
				boolean carryOn = line.charAt(line.length() - 1) == '\\';

				String name, contents;
				if (!readOn) {
					int split = line.indexOf('=');
					name = line.substring(0, split);

					if (carryOn) {
						readOn = true;

						assert rolledName == null;
						rolledName = name;

						assert rollOn.length() == 0;
						rollOn.append(line, split + 1, line.length() - 1);
						continue;
					}

					contents = line.substring(split + 1);
				} else {
					assert rolledName != null;
					assert rollOn.length() > 0;
					rollOn.append(line);

					if (carryOn) {
						rollOn.setLength(rollOn.length() - 1);
						continue;
					}
					readOn = false;

					name = rolledName;
					rolledName = null;

					contents = rollOn.toString();
					rollOn.setLength(0);
				}

				switch (name) {
				case "classPath": //Should be File#pathSeparator but the files always use Unix endings
					classpath = Arrays.stream(contents.split(":(?=[^\\\\/])")).map(BuildSettings::asPath).collect(Collectors.toSet());
					assert classpath.stream().allMatch(Files::isRegularFile);
					break;

				case "mappingFile":
					mappingFile = asPath(contents);
					assert Files.isRegularFile(mappingFile);
					break;

				case "mcFile":
					mcFile = asPath(contents);
					assert Files.isRegularFile(mcFile);
					break;

				case "mcVersion":
					mcVersion = stripQuotes(contents);
					break;

				case "sourceNs":
					sourceNs = stripQuotes(contents);
					break;

				case "targetNs":
					targetNs = stripQuotes(contents);
					break;
				}
			}
		} catch (IOException e) {
			throw new UncheckedIOException("Error reading build settings: " + file, e);
		}

		assert classpath != null;
		this.classpath = Collections.unmodifiableSet(classpath);
		assert mappingFile != null;
		this.mappingFile = mappingFile;
		assert mcFile != null;
		this.mcFile = mcFile;
		assert mcVersion != null;
		this.mcVersion = mcVersion;
		assert sourceNs != null;
		this.sourceNs = sourceNs;
		assert targetNs != null;
		this.targetNs = targetNs;
	}

	private static String stripQuotes(String text) {
		assert text.length() > 2: "Stripped everything from " + text;
		return text.substring(1, text.length() - 1);
	}

	private static Path asPath(String path) {
		return Paths.get(stripQuotes(path));
	}

	public Set<Path> libraries() {
		Set<Path> out = new HashSet<>(classpath);
		out.remove(mcFile);
		return out;
	}
}