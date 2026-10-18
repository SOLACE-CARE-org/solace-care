package care.solace.core.specialists;

import care.solace.errors.ErrorCode;

/** Errors of the specialists module; titles in messages.properties and messages_fr.properties. */
enum SpecialistError implements ErrorCode {
  /** A seeker, or a member without an application, on a specialist endpoint. */
  NOT_A_SPECIALIST("specialist.not-a-specialist", 403),
  /** The Specialist terms or Code of conduct version is not the current one: update the app. */
  OUTDATED_TERMS("specialist.outdated-terms", 400),
  /** A profession, help topic or language that is not in the catalogue. */
  UNKNOWN_CHOICE("specialist.unknown-choice", 400),
  /** A price outside the allowed range, or a session length that is not offered. */
  PRICE_OUT_OF_RANGE("specialist.price-out-of-range", 400),
  /**
   * Bank transfer while the payout-bank-transfer flag is off, or a mobile money method with no
   * number.
   */
  PAYOUT_METHOD_UNAVAILABLE("specialist.payout-method-unavailable", 400),
  /** Not a PDF, JPEG or PNG, or not an image for the profile photo. */
  DOCUMENT_TYPE("specialist.document-type", 400),
  /** The application has been submitted: it can change only when a reviewer asks for changes. */
  APPLICATION_LOCKED("specialist.application-locked", 409),
  /** A step is missing; the application says which. */
  APPLICATION_INCOMPLETE("specialist.application-incomplete", 409),
  /** A review action on an application that is not submitted or under review. */
  NOT_UNDER_REVIEW("specialist.not-under-review", 409),
  /**
   * An item cannot be verified yet: its document, payout details or second-language bio is missing.
   */
  ITEM_NOT_READY("specialist.item-not-ready", 409),
  /** Approval needs all six items verified. */
  REVIEW_INCOMPLETE("specialist.review-incomplete", 409),
  /** Suspending needs an approved specialist; reinstating, a suspended one. */
  NOT_APPROVED("specialist.not-approved", 409),
  /** An approval is already waiting for its second person (D29). */
  APPROVAL_PENDING("specialist.approval-pending", 409),
  /** Confirming or withdrawing an approval nobody proposed. */
  APPROVAL_NOT_PROPOSED("specialist.approval-not-proposed", 409),
  /** The person who proposed an approval cannot also confirm it (D29). */
  SAME_APPROVER("specialist.same-approver", 409),
  /** There is no Solace-drafted bio to confirm (D30). */
  NO_BIO_DRAFT("specialist.no-bio-draft", 409);

  private final String code;
  private final int status;

  SpecialistError(String code, int status) {
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
    return "SPE";
  }
}
