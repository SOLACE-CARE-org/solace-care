package care.solace.core.admin;

import care.solace.errors.CommonError;
import care.solace.errors.SolaceException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Service;

/**
 * Team and roles (admin screen 19; D7, D42). Only a Super admin adds staff, through Keycloak's
 * admin API; nobody signs themselves up. A temporary password works once and expires after 72 hours
 * (the staff realm's authenticator enforces it). A role change, a new password and deactivation
 * each end the person's sessions.
 */
@Service
class Team {

  /** A decision (D7), enforced in Keycloak; shown here so the screen can say when it expires. */
  static final Duration PASSWORD_VALIDITY = Duration.ofHours(72);

  record Password(StaffDirectory.Member member, String temporaryPassword, Instant expiresAt) {}

  private final KeycloakStaff keycloak;
  private final StaffDirectory directory;
  private final Clock clock;
  private final org.springframework.context.ApplicationEventPublisher events;

  Team(
      KeycloakStaff keycloak,
      StaffDirectory directory,
      Clock clock,
      org.springframework.context.ApplicationEventPublisher events) {
    this.keycloak = keycloak;
    this.directory = directory;
    this.clock = clock;
    this.events = events;
  }

  List<StaffDirectory.Member> members() {
    return directory.all();
  }

  boolean hasSecondFactor(String id) {
    return keycloak.hasSecondFactor(id);
  }

  Password add(String name, String email, List<String> roles, StaffAccess.Staff by) {
    String password = KeycloakStaff.temporaryPassword();
    String normalised = email.trim().toLowerCase(Locale.ROOT);
    String id = keycloak.create(name.trim(), normalised, roles, password);
    Instant now = clock.instant();
    directory.add(id, name.trim(), normalised, roles, by.id(), now);
    events.publishEvent(new StaffAccountCreated(normalised));
    return new Password(member(id), password, now.plus(PASSWORD_VALIDITY));
  }

  StaffDirectory.Member changeRoles(String id, List<String> roles, StaffAccess.Staff by) {
    var member = member(id);
    if (member.status().equals("DEACTIVATED")) {
      throw new SolaceException(AdminError.STAFF_DEACTIVATED);
    }
    if (id.equals(by.id()) && !roles.contains("SUPER_ADMIN")) {
      throw new SolaceException(AdminError.STAFF_SELF);
    }
    keycloak.setRoles(id, roles);
    keycloak.logout(id);
    directory.endSessions(id, roles, null, clock.instant());
    return member(id);
  }

  Password newPassword(String id) {
    var member = member(id);
    if (member.status().equals("DEACTIVATED")) {
      throw new SolaceException(AdminError.STAFF_DEACTIVATED);
    }
    String password = KeycloakStaff.temporaryPassword();
    keycloak.resetPassword(id, password);
    keycloak.logout(id);
    Instant now = clock.instant();
    directory.endSessions(id, null, null, now);
    return new Password(member(id), password, now.plus(PASSWORD_VALIDITY));
  }

  StaffDirectory.Member deactivate(String id, StaffAccess.Staff by) {
    member(id);
    if (id.equals(by.id())) {
      throw new SolaceException(AdminError.STAFF_SELF);
    }
    keycloak.disable(id);
    keycloak.logout(id);
    directory.endSessions(id, null, "DEACTIVATED", clock.instant());
    return member(id);
  }

  private StaffDirectory.Member member(String id) {
    return directory.find(id).orElseThrow(() -> new SolaceException(CommonError.NOT_FOUND));
  }
}
