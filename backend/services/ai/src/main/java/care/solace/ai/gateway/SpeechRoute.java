package care.solace.ai.gateway;

/** One speech-to-text model behind the gateway, for the scribe (D57). */
public interface SpeechRoute {

  String name();

  /** True for a model hosted by Solace; session audio goes only to these until authorised. */
  boolean local();

  /**
   * @param language the session language's ISO 639 code, a hint for the model
   */
  String transcribe(byte[] audio, String filename, String language);
}
