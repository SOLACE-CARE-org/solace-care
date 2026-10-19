package care.solace.ai.gateway;

import java.util.Map;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.RestClient;

/**
 * Speech to text through an OpenAI-compatible {@code /audio/transcriptions} endpoint, such as a
 * self-hosted Whisper server (System Design, Models: a multilingual speech-to-text model).
 */
final class HttpSpeechRoute implements SpeechRoute {

  private final GatewaySettings.Model settings;
  private final RestClient http;

  HttpSpeechRoute(GatewaySettings.Model settings) {
    this.settings = settings;
    var builder = RestClient.builder().baseUrl(settings.url());
    if (!settings.apiKey().isBlank()) {
      builder.defaultHeader("Authorization", "Bearer " + settings.apiKey());
    }
    this.http = builder.build();
  }

  @Override
  public String name() {
    return settings.name();
  }

  @Override
  public boolean local() {
    return settings.local();
  }

  @Override
  public String transcribe(byte[] audio, String filename, String language) {
    var form = new LinkedMultiValueMap<String, Object>();
    form.add(
        "file",
        new ByteArrayResource(audio) {
          @Override
          public String getFilename() {
            return filename;
          }
        });
    form.add("model", settings.model());
    form.add("language", language);
    form.add("response_format", "json");
    Map<?, ?> body =
        http.post()
            .uri("/audio/transcriptions")
            .contentType(MediaType.MULTIPART_FORM_DATA)
            .body(form)
            .retrieve()
            .body(Map.class);
    Object text = body == null ? null : body.get("text");
    return text == null ? "" : text.toString().strip();
  }
}
