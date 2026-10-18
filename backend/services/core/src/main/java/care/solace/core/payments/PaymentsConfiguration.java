package care.solace.core.payments;

import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.web.client.RestClient;

/** The provider adapter chosen by {@code solace.payments.provider}, and the payment jobs. */
@Configuration
@EnableConfigurationProperties(PaymentSettings.class)
class PaymentsConfiguration {

  private static final Logger LOG = LoggerFactory.getLogger(PaymentsConfiguration.class);

  @Bean
  PaymentProvider paymentProvider(
      PaymentSettings settings, Clock clock, RestClient.Builder restClients) {
    String name = settings.provider() == null ? "" : settings.provider();
    return switch (name) {
      case "notchpay" -> new NotchPayProvider(settings.notchpay(), restClients);
      case "sandbox" -> {
        LOG.warn("Payments use the sandbox provider: no real money moves");
        yield new SandboxProvider(settings.sandbox(), clock);
      }
      default ->
          throw new IllegalStateException(
              "solace.payments.provider must be notchpay or sandbox (D35), not '" + name + "'");
    };
  }

  @Bean
  Jobs paymentJobs(Payments payments, PayoutRuns payouts, Reconciliation reconciliation) {
    return new Jobs(payments, payouts, reconciliation);
  }

  /** Checks with the provider besides its webhooks, drafts payout runs and reconciles daily. */
  static class Jobs {

    private final Payments payments;
    private final PayoutRuns payouts;
    private final Reconciliation reconciliation;

    Jobs(Payments payments, PayoutRuns payouts, Reconciliation reconciliation) {
      this.payments = payments;
      this.payouts = payouts;
      this.reconciliation = reconciliation;
    }

    @Scheduled(
        fixedDelayString = "${solace.payments.poll-interval:PT20S}",
        initialDelayString = "PT20S")
    void poll() {
      try {
        payments.poll();
        payouts.poll();
      } catch (RuntimeException e) {
        LOG.warn("Payment check failed", e);
      }
    }

    @Scheduled(fixedDelayString = "PT1H", initialDelayString = "PT1M")
    void draftPayoutRuns() {
      try {
        payouts.draftDue();
      } catch (RuntimeException e) {
        LOG.warn("Drafting the payout run failed", e);
      }
    }

    @Scheduled(
        cron = "${solace.payments.reconciliation-cron:0 0 3 * * *}",
        zone = "${solace.payments.time-zone:Africa/Douala}")
    void reconcile() {
      try {
        reconciliation.yesterday();
      } catch (RuntimeException e) {
        LOG.warn("Reconciliation failed", e);
      }
    }
  }
}
