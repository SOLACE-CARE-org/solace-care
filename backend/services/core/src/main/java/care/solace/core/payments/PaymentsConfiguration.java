package care.solace.core.payments;

import care.solace.jobs.Job;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Payment settings and the payment jobs; providers are rows (PaymentProviders, D61). */
@Configuration
@EnableConfigurationProperties(PaymentSettings.class)
class PaymentsConfiguration {

  private static final Logger LOG = LoggerFactory.getLogger(PaymentsConfiguration.class);

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

    @Job(
        name = "core.payments.poll",
        description =
            "Asks the payment providers about payments, refunds and payouts still on their way",
        every = "${solace.payments.poll-interval:PT20S}",
        initialDelay = "PT20S",
        caution = true)
    void poll() {
      try {
        payments.poll();
        payouts.poll();
      } catch (RuntimeException e) {
        LOG.warn("Payment check failed", e);
      }
    }

    @Job(
        name = "core.payments.draft-payout-runs",
        description = "Drafts the twice-monthly payout run when its period closes",
        every = "PT1H",
        initialDelay = "PT1M")
    void draftPayoutRuns() {
      try {
        payouts.draftDue();
      } catch (RuntimeException e) {
        LOG.warn("Drafting the payout run failed", e);
      }
    }

    @Job(
        name = "core.payments.reconcile",
        description =
            "Compares yesterday's payments, refunds and payouts with the providers' records",
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
