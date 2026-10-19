package care.solace.ai.gateway;

/**
 * One model behind the gateway. Feature code never calls a vendor; it asks the gateway, which picks
 * a route, falls back and keeps the books (System Design, Model gateway).
 */
public interface ModelRoute {

  /** A stable name for logs and the AI controls screen, e.g. "local-llm". */
  String name();

  /**
   * True for a model hosted by Solace. Health data goes only to these unless a transfer
   * authorisation is recorded (System Design, Data rules).
   */
  boolean local();

  Reply call(String system, String user);

  record Reply(String text, int tokensIn, int tokensOut) {}
}
