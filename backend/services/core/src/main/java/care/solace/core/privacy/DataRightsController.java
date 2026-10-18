package care.solace.core.privacy;

import care.solace.core.identity.Accounts;
import care.solace.errors.CommonError;
import care.solace.errors.SolaceException;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Download a copy of my data, and Delete my account and data (Privacy & data; D40). */
@RestController
class DataRightsController {

  private final DataRights rights;
  private final Accounts accounts;

  DataRightsController(DataRights rights, Accounts accounts) {
    this.rights = rights;
    this.accounts = accounts;
  }

  @JsonInclude(JsonInclude.Include.NON_NULL)
  record DataExport(
      UUID id, String status, Instant requestedAt, Instant readyAt, Instant expiresAt) {

    static DataExport of(DataRights.Request r) {
      return new DataExport(r.id(), r.status(), r.requestedAt(), r.readyAt(), r.expiresAt());
    }
  }

  record Erasure(Boolean confirm) {}

  record Outcome(String outcome) {}

  @PostMapping("/v1/me/data-exports")
  @ResponseStatus(HttpStatus.ACCEPTED)
  DataExport request(@AuthenticationPrincipal Jwt token) {
    return DataExport.of(rights.requestExport(accounts.require(token).id()));
  }

  @GetMapping("/v1/me/data-exports/latest")
  DataExport latest(@AuthenticationPrincipal Jwt token) {
    return rights
        .latestExport(accounts.require(token).id())
        .map(DataExport::of)
        .orElseThrow(() -> new SolaceException(CommonError.NOT_FOUND));
  }

  @GetMapping("/v1/me/data-exports/{exportId}/file")
  ResponseEntity<byte[]> file(@AuthenticationPrincipal Jwt token, @PathVariable UUID exportId) {
    byte[] bytes = rights.download(accounts.require(token).id(), exportId);
    return ResponseEntity.ok()
        .contentType(MediaType.APPLICATION_JSON)
        .header("Cache-Control", "no-store")
        .header("Content-Disposition", "attachment; filename=\"solace-data.json\"")
        .body(bytes);
  }

  @PostMapping("/v1/me/erasure")
  @ResponseStatus(HttpStatus.ACCEPTED)
  Outcome erase(@AuthenticationPrincipal Jwt token, @RequestBody Erasure body) {
    if (body == null || !Boolean.TRUE.equals(body.confirm())) {
      throw new SolaceException(CommonError.REQUEST_INVALID);
    }
    return new Outcome(rights.erase(accounts.require(token)).name());
  }
}
