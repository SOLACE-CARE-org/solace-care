package care.solace.core.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;

class TestCodesTest {

  private final Settings settings = mock(Settings.class);

  @Test
  void noCodeIsAcceptedWithoutTheDeploymentsCode() {
    when(settings.enabled(TestCodes.SIGN_IN_FLAG)).thenReturn(true);
    when(settings.enabled(TestCodes.STAFF_FLAG)).thenReturn(true);

    TestCodes codes = new TestCodes(settings, "");

    assertThat(codes.signInOn()).isFalse();
    assertThat(codes.staffOn()).isFalse();
    assertThat(codes.acceptsSignIn("")).isFalse();
    assertThat(codes.acceptsSignIn("123456")).isFalse();
  }

  @Test
  void noCodeIsAcceptedUntilTheFlagIsOn() {
    TestCodes codes = new TestCodes(settings, "123456");

    assertThat(codes.acceptsSignIn("123456")).isFalse();
    assertThat(codes.staffOn()).isFalse();
  }

  @Test
  void onlyTheRightCodeIsAcceptedWhileItsFlagIsOn() {
    when(settings.enabled(TestCodes.SIGN_IN_FLAG)).thenReturn(true);

    TestCodes codes = new TestCodes(settings, "123456");

    assertThat(codes.acceptsSignIn(" 123456 ")).isTrue();
    assertThat(codes.acceptsSignIn("654321")).isFalse();
    assertThat(codes.acceptsSignIn(null)).isFalse();
    assertThat(codes.staffOn()).isFalse();
  }
}
