package care.solace.lists;

import care.solace.audit.AuditLog;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.autoconfigure.jdbc.JdbcTemplateAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Reviewed lists for a service with a {@link ListAccess} bean (D61), from its {@link ReviewedList}
 * and {@link ReviewedListGroup} beans. The service's own migration creates the table.
 */
@AutoConfiguration(after = JdbcTemplateAutoConfiguration.class)
@ConditionalOnBean(ListAccess.class)
public class ReviewedListsAutoConfiguration {

  @Bean
  ReviewedLists reviewedLists(
      ObjectProvider<ReviewedList> lists,
      ObjectProvider<ReviewedListGroup> groups,
      JdbcTemplate jdbc,
      TransactionTemplate transactions,
      ObjectProvider<Clock> clock) {
    List<ReviewedList> all = new ArrayList<>(lists.orderedStream().toList());
    groups.orderedStream().forEach(g -> all.addAll(g.lists()));
    return new ReviewedLists(all, jdbc, transactions, clock.getIfAvailable(Clock::systemUTC));
  }

  /** The live versions are in force once the service is up. */
  @Bean
  ApplicationRunner reviewedListsFirstLoad(ReviewedLists lists) {
    return args -> lists.reload();
  }

  @Configuration(proxyBeanMethods = false)
  @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
  @ConditionalOnBean(AuditLog.class)
  static class Admin {
    @Bean
    ListsController listsController(ListAccess access, ReviewedLists lists, AuditLog audit) {
      return new ListsController(access, lists, audit);
    }
  }
}
