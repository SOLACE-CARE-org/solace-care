package care.solace.core.system;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** {@code GET /v1/system/ping}, reached through the gateway as {@code /api/core/v1/system/ping}. */
@RestController
class PingController {

  private final Pinger pinger;

  PingController(Pinger pinger) {
    this.pinger = pinger;
  }

  @GetMapping("/v1/system/ping")
  PingResult ping() {
    return pinger.ping();
  }
}
