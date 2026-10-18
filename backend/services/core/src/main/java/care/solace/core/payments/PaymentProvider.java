package care.solace.core.payments;

import java.util.Optional;
import java.util.UUID;

/**
 * The aggregator port (System Design, Payments: provider-agnostic with adapters). Collections ask a
 * wallet for money; transfers send money to a wallet, for refunds and payouts (D35). Every call
 * carries Solace's own reference so a provider's notification can be matched either way.
 */
interface PaymentProvider {

  /** The name in webhook paths and the payment table. */
  String name();

  /** Asks the wallet holder to approve a payment on their phone. */
  Started charge(ChargeRequest request);

  Outcome chargeStatus(String providerRef);

  /** Sends money to a wallet. */
  Started transfer(TransferRequest request);

  Outcome transferStatus(String providerRef);

  /** True when the webhook body carries a valid signature in its headers. */
  boolean verify(String signature, byte[] body);

  /** What a verified webhook says, or empty for an event Solace does not act on. */
  Optional<Notification> parse(byte[] body);

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

  /** The provider could not be reached or refused the request. */
  final class Unavailable extends RuntimeException {
    Unavailable(String message, Throwable cause) {
      super(message, cause);
    }
  }
}
