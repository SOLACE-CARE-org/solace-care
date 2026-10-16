package care.solace.keycloak.staff;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class TemporaryPasswordPolicyTest {

  private static final Instant SET_AT = Instant.parse("2026-10-16T08:00:00Z");

  @Test
  void temporaryPasswordWorksForJustUnder72Hours() {
    Instant now = SET_AT.plus(Duration.ofHours(72)).minusSeconds(1);

    assertThat(TemporaryPasswordPolicy.isExpired(true, SET_AT, now)).isFalse();
  }

  @Test
  void temporaryPasswordExpiresAt72Hours() {
    Instant now = SET_AT.plus(Duration.ofHours(72));

    assertThat(TemporaryPasswordPolicy.isExpired(true, SET_AT, now)).isTrue();
  }

  @Test
  void ownPasswordNeverExpiresHere() {
    Instant now = SET_AT.plus(Duration.ofDays(400));

    assertThat(TemporaryPasswordPolicy.isExpired(false, SET_AT, now)).isFalse();
  }

  @Test
  void temporaryPasswordWithUnknownAgeIsRefused() {
    assertThat(TemporaryPasswordPolicy.isExpired(true, null, SET_AT)).isTrue();
  }
}
