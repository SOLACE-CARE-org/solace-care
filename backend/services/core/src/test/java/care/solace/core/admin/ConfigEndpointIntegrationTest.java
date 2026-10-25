package care.solace.core.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import care.solace.contracts.core.v1.GetPlatformConfigRequest;
import care.solace.contracts.core.v1.GetPlatformConfigResponse;
import care.solace.core.specialists.SpecialistFlowTest;
import io.grpc.stub.StreamObserver;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

/** Tracking's and AI's settings live in Core (D61): set on screen 20, served over gRPC. */
class ConfigEndpointIntegrationTest extends SpecialistFlowTest {

  @Autowired ConfigEndpoint endpoint;

  @Test
  void aTrackingSettingSetOnTheScreenReachesTrackingWithItsUnit() throws Exception {
    mvc.perform(
            put("/admin/v1/settings/solace.tracking.snooze-days")
                .with(staff("sa-c", "C. Super", "SUPER_ADMIN"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"value\":\"12\",\"reason\":\"Longer snooze\"}"))
        .andExpect(status().isOk());

    var answer = config();

    var snooze =
        answer.getSettingsList().stream()
            .filter(s -> s.getKey().equals("solace.tracking.snooze-days"))
            .findFirst()
            .orElseThrow();
    assertThat(snooze.getValue()).isEqualTo("12");
    assertThat(snooze.getUnit()).isEqualTo("DAYS");
    assertThat(answer.getFlagsList())
        .anyMatch(f -> f.getKey().equals("aiTransferAuthorised") && !f.getEnabled());
    // Fixed decisions are not settings anyone can set, so they are never served.
    assertThat(answer.getSettingsList())
        .noneMatch(s -> s.getKey().equals("solace.scheduling.hold"));
  }

  private GetPlatformConfigResponse config() {
    var result = new AtomicReference<GetPlatformConfigResponse>();
    endpoint.getPlatformConfig(
        GetPlatformConfigRequest.getDefaultInstance(),
        new StreamObserver<>() {
          @Override
          public void onNext(GetPlatformConfigResponse value) {
            result.set(value);
          }

          @Override
          public void onError(Throwable t) {
            throw new AssertionError(t);
          }

          @Override
          public void onCompleted() {}
        });
    return result.get();
  }
}
