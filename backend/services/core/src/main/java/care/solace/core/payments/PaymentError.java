package care.solace.core.payments;

import care.solace.errors.ErrorCode;

/** Errors of payments; titles in messages.properties and messages_fr.properties. */
enum PaymentError implements ErrorCode {
  /** The booking is not waiting for payment, or its hold window has ended. */
  NOT_PAYABLE("payment.not-payable", 409),
  /** The aggregator could not be reached or refused the request; try again. */
  PROVIDER_UNAVAILABLE("payment.provider-unavailable", 503),
  /** The webhook's signature does not verify. */
  SIGNATURE_INVALID("payment.signature-invalid", 401),
  /** Only specialists have earnings. */
  NOT_A_SPECIALIST("payment.not-a-specialist", 403),
  /** The booking has no completed payment to refund. */
  NOTHING_TO_REFUND("refund.nothing-to-refund", 409),
  /** More than was paid and not yet refunded. */
  REFUND_TOO_LARGE("refund.too-large", 400),
  /** Another refund for this payment is still open. */
  REFUND_OPEN("refund.already-open", 409),
  /** The refund is not in a state that allows this. */
  REFUND_STATE("refund.wrong-state", 409),
  /** The person who proposed or prepared it cannot also approve it (D14). */
  SAME_PERSON("money.same-person", 403),
  /** Only a drafted run can be changed or submitted. */
  RUN_NOT_DRAFT("payout-run.not-draft", 409),
  /** Only a submitted run can be approved. */
  RUN_NOT_SUBMITTED("payout-run.not-submitted", 409),
  /** A provider with this code exists (D61). */
  PROVIDER_EXISTS("payment-provider.exists", 409),
  /** The provider's version is not the one read. */
  PROVIDER_CHANGED("payment-provider.changed", 409),
  /** DISABLED while payments, refunds or payouts are still on their way at it. */
  PROVIDER_IN_FLIGHT("payment-provider.in-flight", 409),
  /** ACTIVE without every key, or with a failing connection test. */
  PROVIDER_NOT_READY("payment-provider.not-ready", 409),
  /** A LIVE provider made ACTIVE without every activation check confirmed. */
  PROVIDER_CHECKS("payment-provider.checks-missing", 400),
  /** A sandbox in LIVE mode, an unknown adapter or a bad address. */
  PROVIDER_INVALID("payment-provider.invalid", 400),
  /** A route to an unknown or disabled provider, or a purpose and method left without one. */
  ROUTES_INVALID("payment-route.invalid", 400),
  /** The routes' version is not the one read. */
  ROUTES_CHANGED("payment-route.changed", 409);

  private final String code;
  private final int status;

  PaymentError(String code, int status) {
    this.code = code;
    this.status = status;
  }

  @Override
  public String code() {
    return code;
  }

  @Override
  public int status() {
    return status;
  }

  @Override
  public String area() {
    return "PAY";
  }
}
