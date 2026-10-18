package care.solace.core.insight;

import care.solace.core.specialists.CatalogueSearched;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** Counts catalogue searches per Douala day for the booking funnel (D46). */
@Component
class SearchCounter {

  private static final Logger LOG = LoggerFactory.getLogger(SearchCounter.class);
  static final ZoneId DOUALA = ZoneId.of("Africa/Douala");

  private final JdbcClient db;
  private final Clock clock;

  SearchCounter(JdbcClient db, Clock clock) {
    this.db = db;
    this.clock = clock;
  }

  @EventListener
  void on(CatalogueSearched searched) {
    try {
      db.sql(
              """
              INSERT INTO search_day (day, searches) VALUES (:d, 1)
              ON CONFLICT (day) DO UPDATE SET searches = search_day.searches + 1
              """)
          .param("d", LocalDate.now(clock.withZone(DOUALA)))
          .update();
    } catch (RuntimeException e) {
      // A lost count never fails a search.
      LOG.warn("Could not count a search");
    }
  }
}
