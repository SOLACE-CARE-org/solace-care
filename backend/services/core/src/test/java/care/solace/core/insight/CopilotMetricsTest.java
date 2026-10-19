package care.solace.core.insight;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import care.solace.contracts.core.v1.Metric;
import care.solace.contracts.core.v1.Period;
import care.solace.contracts.core.v1.QueryMetricRequest;
import care.solace.contracts.core.v1.QueryMetricResponse;
import care.solace.core.specialists.SpecialistFlowTest;
import io.grpc.stub.StreamObserver;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

/** The admin copilot's metrics and anomaly alerts (Phase 4; D58): aggregates only. */
class CopilotMetricsTest extends SpecialistFlowTest {

  @Autowired MetricsEndpoint metrics;
  @Autowired Anomalies anomalies;

  @Test
  void metricsAreGroupedCountsWithSmallCellsHidden() throws Exception {
    String specialist =
        approvedSpecialist("+237696000411", "Metric Keeper", staff("ops-m", "M", "OPERATIONS"))
            .id();
    String seeker = seekerId("+237670009401");
    // Seven invites from a module only this test uses; two were answered by a booking.
    List<UUID> invites = new ArrayList<>();
    for (int i = 0; i < 7; i++) {
      UUID id = UUID.randomUUID();
      invites.add(id);
      jdbc.update(
          """
          INSERT INTO tracking_invite (recommendation_id, account_id, rule_code, specialty_codes,
                                       status, created_at)
          VALUES (?, ?::uuid, 'copilottest.rule', ARRAY['psychology'], 'OPEN', now())
          """,
          id,
          seeker);
    }
    for (int i = 0; i < 2; i++) {
      booking(seeker, specialist, Instant.now(), invites.get(i));
    }

    var reply = query(Metric.METRIC_INVITES_BY_MODULE, Period.PERIOD_LAST_7_DAYS, "fr");
    assertThat(reply.getColumnsList())
        .containsExactly("Module", "Invitations affichées", "Réservations");
    var row =
        reply.getRowsList().stream()
            .filter(r -> r.getCells(0).equals("copilottest"))
            .findFirst()
            .orElseThrow();
    assertThat(row.getCellsList()).containsExactly("copilottest", "7", "<5");
    assertThat(reply.getSuppressed()).isTrue();
    assertThat(reply.getSource()).startsWith("Source :");
    // Nothing that names a person comes back.
    assertThat(reply.toString()).doesNotContain(seeker).doesNotContain("Metric Keeper");
  }

  @Test
  void aBookingDropRaisesOneAlertADayForAnalyticsStaff() throws Exception {
    String specialist =
        approvedSpecialist("+237696000412", "Alert Keeper", staff("ops-a", "A", "OPERATIONS")).id();
    String seeker = seekerId("+237670009402");
    for (int i = 0; i < 140; i++) {
      booking(seeker, specialist, Instant.now().minus(Duration.ofDays(2 + i % 5)), null);
    }
    anomalies.checkNow();
    anomalies.checkNow();
    mvc.perform(get("/admin/v1/insight/alerts").with(staff("fin", "F", "FINANCE")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[?(@.kind == 'BOOKING_DROP')]").isNotEmpty())
        .andExpect(jsonPath("$.length()").value(1));
    mvc.perform(get("/admin/v1/insight/alerts").with(staff("cr", "C", "CRISIS_RESPONDER")))
        .andExpect(status().isForbidden());
  }

  // --- Helpers ----------------------------------------------------------------------------------

  private QueryMetricResponse query(Metric metric, Period period, String locale) {
    List<QueryMetricResponse> out = new ArrayList<>();
    metrics.queryMetric(
        QueryMetricRequest.newBuilder()
            .setMetric(metric)
            .setPeriod(period)
            .setLocale(locale)
            .build(),
        new StreamObserver<>() {
          @Override
          public void onNext(QueryMetricResponse value) {
            out.add(value);
          }

          @Override
          public void onError(Throwable t) {
            throw new AssertionError(t);
          }

          @Override
          public void onCompleted() {}
        });
    return out.getFirst();
  }

  private void booking(String seeker, String specialist, Instant confirmed, UUID recommendation) {
    Instant start = confirmed.plus(Duration.ofDays(1));
    jdbc.update(
        """
        INSERT INTO booking (id, seeker_account_id, specialist_id, modality, duration_min, price,
                             session_language, seeker_locale, starts_at, ends_at, status, source,
                             policy_version, confirmed_at, recommendation_id)
        VALUES (?, ?::uuid, ?::uuid, 'AUDIO', 50, 15000, 'en', 'en', ?, ?, 'CONFIRMED', ?, 1, ?, ?)
        """,
        UUID.randomUUID(),
        seeker,
        specialist,
        Timestamp.from(start),
        Timestamp.from(start.plus(Duration.ofMinutes(50))),
        recommendation == null ? "SEARCH" : "TRACKING_RECOMMENDATION",
        Timestamp.from(confirmed),
        recommendation);
  }

  private String seekerId(String phone) throws Exception {
    mvc.perform(
            post("/v1/signups")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    JSON.writeValueAsString(
                        Map.of(
                            "displayName", "Counter",
                            "phone", phone,
                            "dateOfBirth", "1990-01-01",
                            "termsVersion", "v1",
                            "privacyNoticeVersion", "v1",
                            "appLanguage", "en"))))
        .andExpect(status().isAccepted());
    String token = signIn(phone);
    return json(mvc.perform(get("/v1/me").headers(bearer(token)))).path("id").asText();
  }
}
