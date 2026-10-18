package care.solace.core.reviews;

import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Service;

/**
 * The safety monitor in front of the moderation queue (D37, D39): each written review is checked
 * once; a flag takes it out of the queue for good, and the crisis alert follows from {@code
 * ai.risk.flagged}.
 */
@Service
class ReviewSafety implements FlaggedReviews {

  private final ReviewModeration moderation;
  private final Ratings ratings;
  private final JdbcClient db;

  ReviewSafety(ReviewModeration moderation, Ratings ratings, JdbcClient db) {
    this.moderation = moderation;
    this.ratings = ratings;
    this.db = db;
  }

  @ApplicationModuleListener
  void check(ReviewSubmitted submitted) {
    var review = ratings.find(submitted.reviewId());
    if (review.isEmpty() || review.get().text() == null) {
      return;
    }
    var r = review.get();
    moderation
        .flagged(r.id(), r.subjectId(), r.text(), r.sessionLanguage())
        .ifPresent(
            flagged -> {
              if (flagged) {
                flag(r.id());
              } else {
                db.sql(
                        "UPDATE rating SET safety = 'CLEAR' WHERE id = :id AND safety = 'NOT_CHECKED'")
                    .param("id", r.id())
                    .update();
              }
            });
  }

  @Override
  public void flag(UUID reviewId) {
    db.sql(
            """
            UPDATE rating SET safety = 'FLAGGED',
                   status = CASE WHEN status IN ('PENDING', 'PUBLISHED') THEN 'FLAGGED' ELSE status END,
                   published_text = NULL
             WHERE id = :id
            """)
        .param("id", reviewId)
        .update();
  }

  @Override
  public Optional<Flagged> find(UUID reviewId) {
    return ratings
        .find(reviewId)
        .map(
            r ->
                new Flagged(
                    r.id(),
                    r.bookingId(),
                    r.subjectId(),
                    r.guest(),
                    r.text(),
                    r.sessionLanguage()));
  }
}
