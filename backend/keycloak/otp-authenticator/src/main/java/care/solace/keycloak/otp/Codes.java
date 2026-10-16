package care.solace.keycloak.otp;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.HexFormat;

/** Generates numeric one-time codes and hashes them, so a code is never stored in clear. */
public final class Codes {

  private static final SecureRandom RANDOM = new SecureRandom();

  private Codes() {}

  /** A code of {@code length} random digits; leading zeros are allowed. */
  public static String generate(int length) {
    StringBuilder code = new StringBuilder(length);
    for (int i = 0; i < length; i++) {
      code.append(RANDOM.nextInt(10));
    }
    return code.toString();
  }

  /** SHA-256 of the code, salted with the user id so equal codes for two users differ. */
  public static String hash(String userId, String code) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      byte[] bytes = digest.digest((userId + ":" + code).getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(bytes);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is always available", e);
    }
  }

  /** Compares a submitted code with a stored hash in constant time. */
  public static boolean matches(String userId, String code, String expectedHash) {
    if (code == null || expectedHash == null) {
      return false;
    }
    return MessageDigest.isEqual(
        hash(userId, code).getBytes(StandardCharsets.US_ASCII),
        expectedHash.getBytes(StandardCharsets.US_ASCII));
  }
}
