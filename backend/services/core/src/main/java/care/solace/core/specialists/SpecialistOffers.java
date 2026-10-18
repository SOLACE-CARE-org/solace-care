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

  record Offer(String modality, int durationMinutes, long price) {}

  record Summary(String fullName, String professionLabel) {}
}
