package care.solace.core.admin;

/** Feature flags (admin screen 20; D42); an unknown flag is off. */
public interface FeatureFlags {

  boolean enabled(String key);
}
