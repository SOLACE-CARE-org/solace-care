package care.solace.gateway;

import care.solace.errors.ErrorCode;
import care.solace.errors.Problems;
import care.solace.locale.SupportedLocales;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Locale;
import java.util.Optional;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * Writes the gateway's own answers (401, 403, 429) in the same problem format as the services,
 * titled in the caller's language from {@code Accept-Language}.
 */
@Component
class ProblemWriter {

  private final Problems problems;
  private final ObjectMapper json;

  ProblemWriter(Problems problems, ObjectMapper json) {
    this.problems = problems;
    this.json = json;
  }

  Mono<Void> write(ServerWebExchange exchange, ErrorCode error) {
    ProblemDetail problem = problems.of(error, locale(exchange));
    ServerHttpResponse response = exchange.getResponse();
    response.setStatusCode(HttpStatusCode.valueOf(error.status()));
    response.getHeaders().setContentType(MediaType.APPLICATION_PROBLEM_JSON);
    try {
      DataBuffer body = response.bufferFactory().wrap(json.writeValueAsBytes(problem));
      return response.writeWith(Mono.just(body));
    } catch (JsonProcessingException e) {
      return Mono.error(e);
    }
  }

  private static Locale locale(ServerWebExchange exchange) {
    return exchange.getRequest().getHeaders().getAcceptLanguage().stream()
        .map(range -> SupportedLocales.match(range.getRange()))
        .flatMap(Optional::stream)
        .findFirst()
        .orElse(SupportedLocales.DEFAULT);
  }
}
