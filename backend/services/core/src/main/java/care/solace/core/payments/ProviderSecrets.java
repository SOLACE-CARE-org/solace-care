package care.solace.core.payments;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * A provider's keys, found by its {@code secret_ref} (D61). Keys never live in the database: they
 * come from Vault as files mounted in {@code solace.payments.secrets-dir}, which Kubernetes updates
 * without a restart ({@code <secret_ref>.public-key}, {@code .private-key}, {@code
 * .webhook-secret}). The ref {@code config} reads {@code solace.payments.<adapter>.*} instead: the
 * environment variables Core had before providers were rows.
 */
@Component
class ProviderSecrets {

  static final String CONFIG = "config";

  /** Blank strings where a key is not set; a blank webhook secret never verifies. */
  record Keys(String publicKey, String privateKey, String webhookSecret) {

    boolean complete(ProviderStore.Adapter adapter) {
      return switch (adapter) {
        case NOTCHPAY -> !publicKey.isBlank() && !privateKey.isBlank() && !webhookSecret.isBlank();
        case SANDBOX -> !webhookSecret.isBlank();
      };
    }
  }

  private final PaymentSettings settings;

  ProviderSecrets(PaymentSettings settings) {
    this.settings = settings;
  }

  Keys keys(ProviderStore.Adapter adapter, String secretRef) {
    if (CONFIG.equals(secretRef)) {
      return switch (adapter) {
        case NOTCHPAY ->
            new Keys(
                settings.notchpay().publicKey(),
                settings.notchpay().privateKey(),
                settings.notchpay().webhookSecret());
        case SANDBOX -> new Keys("", "", settings.sandbox().webhookSecret());
      };
    }
    return new Keys(
        file(secretRef, "public-key").orElse(""),
        file(secretRef, "private-key").orElse(""),
        file(secretRef, "webhook-secret").orElse(""));
  }

  private Optional<String> file(String secretRef, String key) {
    Path path = Path.of(settings.secretsDir()).resolve(secretRef + "." + key);
    if (!Files.isRegularFile(path)) {
      return Optional.empty();
    }
    try {
      return Optional.of(Files.readString(path, StandardCharsets.UTF_8).strip());
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
