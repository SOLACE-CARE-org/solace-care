package care.solace.ai.gateway;

import java.util.List;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;

/** A route through Spring AI to an OpenAI-compatible endpoint (System Design, Runtime). */
final class SpringAiRoute implements ModelRoute {

  private final GatewaySettings.Model settings;
  private final OpenAiChatModel model;

  SpringAiRoute(GatewaySettings.Model settings) {
    this.settings = settings;
    OpenAiApi api =
        OpenAiApi.builder()
            .baseUrl(settings.url())
            .apiKey(settings.apiKey().isBlank() ? "none" : settings.apiKey())
            .build();
    this.model =
        OpenAiChatModel.builder()
            .openAiApi(api)
            .defaultOptions(
                OpenAiChatOptions.builder().model(settings.model()).temperature(0.2).build())
            .build();
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
  public Reply call(String system, String user) {
    ChatResponse response =
        model.call(new Prompt(List.of(new SystemMessage(system), new UserMessage(user))));
    String text = response.getResult() == null ? "" : response.getResult().getOutput().getText();
    var usage = response.getMetadata() == null ? null : response.getMetadata().getUsage();
    int in = usage == null || usage.getPromptTokens() == null ? 0 : usage.getPromptTokens();
    int out =
        usage == null || usage.getCompletionTokens() == null ? 0 : usage.getCompletionTokens();
    return new Reply(text == null ? "" : text.strip(), in, out);
  }
}
