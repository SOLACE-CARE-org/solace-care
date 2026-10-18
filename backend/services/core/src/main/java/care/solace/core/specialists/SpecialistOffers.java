package care.solace.core.specialists;

import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/** What bookings may ask about a listed specialist (D31: listed, bookable, confirmed bio). */
public interface SpecialistOffers {

  /** The listed specialist's enabled session in this format, or empty. */
  Optional<Offer> offer(UUID specialistId, String modality);

  /** True when the specialist is listed and verified at a bookable level in the language. */
  boolean bookableIn(UUID specialistId, String language);

  /** Name and profession label as seekers see them, listed or not (for past bookings). */
  Optional<Summary> summary(UUID specialistId, Locale viewer);

  /** Solace's fee on each completed session, in percent (D28). */
  java.math.BigDecimal feePercent();

  /** Where the specialist is paid; empty until set, or for a method payouts cannot use yet. */
  Optional<PayoutDetails> payoutDetails(UUID specialistId);

  /**
   * @param method MTN_MOMO or ORANGE_MONEY
   */
  record PayoutDetails(String method, String phone, java.time.Instant savedAt) {}

  /**
   * Listed specialists of any of these specialties, bookable in any of these languages, soonest
   * available first (tracking invites, D52; the health check's matches).
   */
  java.util.List<Match> matches(
      java.util.Collection<String> specialties,
      java.util.Collection<String> languages,
      Locale viewer,
      int limit);

  record Match(
      UUID id,
      String fullName,
      String professionLabel,
      java.util.List<String> languages,
      java.time.Instant nextAvailable) {}

  /**
   * Health check matches (System Design, Health check, Matching): listed specialists of these
   * specialties in any of these languages, with this format and at most this price, each with its
   * focus area codes, price and rating so the caller can rank them.
   */
  java.util.List<Candidate> candidates(
      java.util.Collection<String> specialties,
      java.util.Collection<String> languages,
      String modality,
      Long maxPrice,
      Locale viewer,
      int limit);

  record Candidate(
      UUID id,
      String fullName,
      String professionLabel,
      String specialty,
      java.util.List<String> languages,
      java.util.List<String> focusCodes,
      java.time.Instant nextAvailable,
      long price,
      double rating) {}

  record Offer(String modality, int durationMinutes, long price) {}

  record Summary(String fullName, String professionLabel) {}
}
