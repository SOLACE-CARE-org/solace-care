package care.solace.core.healthcheck;

import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Seals health check answers (System Design, Security: health check answers are encrypted like
 * notes; D55). AES-256-GCM with a key from configuration, never stored with the data; a leading key
 * version byte lets the key rotate to the KMS envelope keys later.
 */
@Component
class Sealer {

  private static final byte KEY_VERSION = 1;
  private static final int IV_BYTES = 12;
  private static final int TAG_BITS = 128;

  private final SecretKeySpec key;
  private final SecureRandom random = new SecureRandom();

  Sealer(@Value("${solace.health-check.answers-key}") String base64Key) {
    byte[] bytes = Base64.getDecoder().decode(base64Key);
    if (bytes.length != 32) {
      throw new IllegalStateException("solace.health-check.answers-key must be 32 bytes, base64");
    }
    this.key = new SecretKeySpec(bytes, "AES");
  }

  byte[] seal(byte[] plain) {
    try {
      byte[] iv = new byte[IV_BYTES];
      random.nextBytes(iv);
      Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
      c.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
      byte[] sealed = c.doFinal(plain);
      return ByteBuffer.allocate(1 + IV_BYTES + sealed.length)
          .put(KEY_VERSION)
          .put(iv)
          .put(sealed)
          .array();
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("Could not seal health check answers", e);
    }
  }

  byte[] open(byte[] sealed) {
    try {
      ByteBuffer in = ByteBuffer.wrap(sealed);
      if (in.get() != KEY_VERSION) {
        throw new IllegalStateException("Unknown health check key version");
      }
      byte[] iv = new byte[IV_BYTES];
      in.get(iv);
      byte[] body = new byte[in.remaining()];
      in.get(body);
      Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
      c.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
      return c.doFinal(body);
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("Could not open health check answers", e);
    }
  }
}
