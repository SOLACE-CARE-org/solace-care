package care.solace.core.admin;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Default codes for testing without an SMS provider or a phone (D63). A code works only where the
 * deployment sets {@code solace.identity.test-code} and a Super admin has turned its flag on, so
 * the flags do nothing in an environment that sets no code.
 */
@Component
public class TestCodes {

  static final String SIGN_IN_FLAG = "testSignInCode";
  static final String STAFF_FLAG = "testStaffCode";

  private final Settings settings;
  private final String code;

  TestCodes(Settings settings, @Value("${solace.identity.test-code:}") String code) {
    this.settings = settings;
    this.code = code == null ? "" : code.strip();
  }

  public boolean signInOn() {
    return !code.isEmpty() && settings.enabled(SIGN_IN_FLAG);
  }

  public boolean staffOn() {
    return !code.isEmpty() && settings.enabled(STAFF_FLAG);
  }

  public boolean acceptsSignIn(String candidate) {
    return signInOn()
        && candidate != null
        && MessageDigest.isEqual(
            code.getBytes(StandardCharsets.UTF_8),
            candidate.strip().getBytes(StandardCharsets.UTF_8));
  }
}
