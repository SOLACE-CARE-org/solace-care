package care.solace.core.payments;

import java.net.URI;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Payment settings ({@code solace.payments.*}, D35). None of these numbers is in the specs.
 *
 * @param provider the provider Core adds, with routes for every method, when no payment route
 *     exists yet: {@code notchpay}, or {@code sandbox} for local runs and tests. Providers are rows
 *     after that and this is ignored (D61). Core does not start with neither routes nor this
 * @param secretsDir where providers' key files are mounted (D61)
 * @param pollInterval how often pending payments and sent transfers are checked with the provider,
 *     besides its webhooks
 * @param pendingTimeout a payment still unapproved after this fails; the slot hold is 10 minutes
 * @param timeZone payout periods and reconciliation days follow this zone
 * @param reconciliationCron when yesterday is reconciled
 * @param notchpay the Notch Pay adapter's default address, and the keys of a provider whose
 *     secret_ref is {@code config}
 * @param sandbox the sandbox adapter
 */
@ConfigurationProperties("solace.payments")
record PaymentSettings(
    String provider,
    @DefaultValue("/etc/solace/payment-providers") String secretsDir,
    @DefaultValue("PT20S") Duration pollInterval,
    @DefaultValue("PT10M") Duration pendingTimeout,
    @DefaultValue("Africa/Douala") String timeZone,
    @DefaultValue("0 0 3 * * *") String reconciliationCron,
    @DefaultValue NotchPay notchpay,
    @DefaultValue Sandbox sandbox) {

  /**
   * @param baseUrl the API
   * @param publicKey sent as Authorization on every call
   * @param privateKey sent as X-Grant on transfers
   * @param webhookSecret signs webhooks (HMAC-SHA256 of the raw body, hex, in X-Notch-Signature)
   */
  record NotchPay(
      @DefaultValue("https://api.notchpay.co") URI baseUrl,
      @DefaultValue("") String publicKey,
      @DefaultValue("") String privateKey,
      @DefaultValue("") String webhookSecret) {}

  /**
   * @param approveAfter a charge counts as approved this long after it was asked for; a phone
   *     ending in 99 always declines, and a transfer to one ending in 98 always fails
   * @param webhookSecret signs the sandbox's webhooks, like Notch Pay's
   */
  record Sandbox(
      @DefaultValue("PT5S") Duration approveAfter,
      @DefaultValue("sandbox-webhook-secret") String webhookSecret) {}
}
