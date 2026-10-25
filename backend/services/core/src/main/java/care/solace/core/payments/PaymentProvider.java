package care.solace.core.payments;

import java.net.ConnectException;
import java.net.UnknownHostException;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.HttpHeaders;

/**
 * The aggregator port (System Design, Payments: provider-agnostic with adapters). Collections ask a
 * wallet for money; transfers send money to a wallet, for refunds and payouts (D35). Every call
 * carries Solace's own reference so a provider's notification can be matched either way. One
 * instance serves one {@code payment_provider} row (D61).
 */
interface PaymentProvider {

  /** The provider's code: in webhook paths, and in the payment, refund and payout tables. */
  String name();

  /** Asks the wallet holder to approve a payment on their phone. */
  Started charge(ChargeRequest request);

  Outcome chargeStatus(String providerRef);

  /** Sends money to a wallet. */
  Started transfer(TransferRequest request);

  Outcome transferStatus(String providerRef);

  /** True when the webhook body carries a valid signature in the headers this provider uses. */
  boolean verify(HttpHeaders headers, byte[] body);

  /** What a verified webhook says, or empty for an event Solace does not act on. */
  Optional<Notification> parse(byte[] body);

  /** A call that moves no money, to show staff that the keys and the address work. */
  void test();

  enum Method {
    MTN_MOMO,
    ORANGE_MONEY
  }

  enum Status {
    PENDING,
    COMPLETE,
    FAILED
  }

  record ChargeRequest(
      UUID reference, long amount, Method method, String phone, String description) {}

  record TransferRequest(
      UUID reference, long amount, Method method, String phone, String name, String description) {}

  record Started(String providerRef, Status status) {}

  /** The provider's view; {@code reason} only when FAILED, in the provider's words. */
  record Outcome(Status status, String reason) {}

  enum Kind {
    PAYMENT,
    TRANSFER
  }

  record Notification(
      String eventId,
      Kind kind,
      String providerRef,
      String reference,
      Status status,
      String reason) {}

  /**
   * The provider could not be reached or refused the request. {@code notSent} is true only when the
   * request certainly did not reach the provider, so asking another provider cannot ask the seeker
   * twice; a timeout is never {@code notSent}.
   */
  final class Unavailable extends RuntimeException {

    private final boolean notSent;

    Unavailable(String message, Throwable cause) {
      this(message, cause, unreached(cause));
    }

    Unavailable(String message, Throwable cause, boolean notSent) {
      super(message, cause);
      this.notSent = notSent;
    }

    boolean notSent() {
      return notSent;
    }

    private static boolean unreached(Throwable cause) {
      for (Throwable t = cause; t != null; t = t.getCause()) {
        if (t instanceof ConnectException || t instanceof UnknownHostException) {
          return true;
        }
      }
      return false;
    }
  }
}
