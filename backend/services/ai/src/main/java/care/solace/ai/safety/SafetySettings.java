package care.solace.ai.safety;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * The safety monitor ({@code solace.safety.*}, D39).
 *
 * @param policyVersion recorded with each decision (AI-9)
 * @param model the self-hosted model, off until approved
 */
@ConfigurationProperties("solace.safety")
record SafetySettings(@DefaultValue("safety-1") String policyVersion, @DefaultValue Model model) {

  /**
   * @param enabled off until the model passes the 99% crisis-recall gate in EN and FR
   * @param url an OpenAI-compatible endpoint inside Solace's hosting
   */
  record Model(
      @DefaultValue("false") boolean enabled,
      @DefaultValue("http://localhost:8000") String url,
      @DefaultValue("") String name) {}
}
