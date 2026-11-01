package care.solace.core.admin;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class StaffRolesTest {

  @Test
  void keycloaksOwnRolesAreLeftOut() {
    assertThat(
            StaffRoles.only(
                List.of(
                    "SUPER_ADMIN",
                    "offline_access",
                    "uma_authorization",
                    "default-roles-solace-staff",
                    "FINANCE")))
        .containsExactly("SUPER_ADMIN", "FINANCE");
  }
}
