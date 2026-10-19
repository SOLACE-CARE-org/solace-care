package care.solace.ai.gateway;

/** One embedding model behind the gateway, for the companion's vetted corpus (D57). */
public interface EmbeddingRoute {

  String name();

  boolean local();

  float[] embed(String text);
}
