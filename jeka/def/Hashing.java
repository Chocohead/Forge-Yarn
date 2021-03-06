import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

import dev.jeka.core.api.utils.JkUtilsString;

public class Hashing {
	private static MessageDigest getSHA1() {
		try {
			return MessageDigest.getInstance("SHA-1");
		} catch (NoSuchAlgorithmException e) {
			throw new AssertionError("Unable to find SHA-1 hasher?", e);
		}
	}

	public static String SHA1(Path file) {
		MessageDigest hasher = getSHA1();

		try (InputStream in = Files.newInputStream(file)) {
			byte[] buffer = new byte[8192];

			int read;
			while ((read = in.read(buffer)) > 0) {
				hasher.update(buffer, 0, read);
			}
		} catch (IOException e) {
			throw new UncheckedIOException("Error reading " + file, e);
		}

		return JkUtilsString.toHexString(hasher.digest());
	}
}