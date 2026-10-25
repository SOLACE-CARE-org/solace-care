package care.solace.jobs;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.EmbeddedValueResolverAware;
import org.springframework.core.MethodIntrospector;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.config.CronTask;
import org.springframework.scheduling.config.FixedDelayTask;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.util.ReflectionUtils;
import org.springframework.util.StringValueResolver;

/**
 * Registers every {@link Job} from its {@code job_trigger} row when the service starts (D61): the
 * row is added with the code's defaults if missing; an ACTIVE row is scheduled as the row says; a
 * PAUSED row is not registered, so the job does not run until a restart after it is resumed. Any
 * job can be paused, crisis escalation included (D61). Each run records when it started, how it
 * ended and when it runs next.
 */
public class JobTriggers implements SchedulingConfigurer {

  private static final Logger LOG = LoggerFactory.getLogger(JobTriggers.class);

  private final Collector collector;
  private final JobTriggerStore store;
  private final Clock clock;
  private final boolean scheduleFromCode;

  /**
   * @param scheduleFromCode tests only ({@code solace.jobs.schedule-from-code}): each job runs on
   *     the schedule its annotation resolves to in this context, not its row's. Status still counts
   */
  public JobTriggers(
      Collector collector, JobTriggerStore store, Clock clock, boolean scheduleFromCode) {
    this.collector = collector;
    this.store = store;
    this.clock = clock;
    this.scheduleFromCode = scheduleFromCode;
  }

  /** A job found on a bean, with its defaults resolved. */
  record Found(
      Job job,
      Object bean,
      Method method,
      JobTriggerStore.Kind kind,
      String schedule,
      String zone,
      Duration initialDelay) {}

  @Override
  public void configureTasks(ScheduledTaskRegistrar registrar) {
    Instant now = clock.instant();
    for (Found f : collector.found()) {
      store.ensure(
          f.job().name(),
          f.job().description(),
          f.job().caution(),
          f.kind(),
          f.schedule(),
          f.zone(),
          now);
      var row = store.find(f.job().name()).orElseThrow();
      if (row.status() == JobTriggerStore.Status.PAUSED) {
        store.registered(row.name(), null);
        LOG.warn("Job {} is paused: not registered", row.name());
        continue;
      }
      var kind = scheduleFromCode ? f.kind() : row.kind();
      var schedule = scheduleFromCode ? f.schedule() : row.schedule();
      var zone = scheduleFromCode ? f.zone() : row.zone();
      Runnable run = () -> run(f, row);
      if (kind == JobTriggerStore.Kind.CRON) {
        registrar.addCronTask(new CronTask(run, cronTrigger(schedule, zone)));
      } else {
        registrar.addFixedDelayTask(
            new FixedDelayTask(run, Schedules.interval(schedule), f.initialDelay()));
      }
      store.registered(row.name(), row.version());
    }
  }

  private void run(Found f, JobTriggerStore.Trigger row) {
    boolean ok = true;
    try {
      store.started(row.name(), clock.instant());
    } catch (RuntimeException e) {
      LOG.warn("Could not record the start of job {}", row.name(), e);
    }
    try {
      ReflectionUtils.makeAccessible(f.method());
      f.method().invoke(f.bean());
    } catch (InvocationTargetException e) {
      ok = false;
      // The class only: a message could carry data the logs must not hold.
      LOG.warn("Job {} failed: {}", row.name(), e.getCause().getClass().getName());
    } catch (IllegalAccessException e) {
      ok = false;
      LOG.warn("Job {} could not be called", row.name(), e);
    }
    try {
      Instant done = clock.instant();
      store.finished(row.name(), ok, done, next(row, done));
    } catch (RuntimeException e) {
      LOG.warn("Could not record the end of job {}", row.name(), e);
    }
  }

  private static Instant next(JobTriggerStore.Trigger row, Instant after) {
    if (row.kind() == JobTriggerStore.Kind.INTERVAL) {
      return after.plus(Schedules.interval(row.schedule()));
    }
    ZoneId zone = row.zone() == null ? ZoneId.systemDefault() : ZoneId.of(row.zone());
    ZonedDateTime next = CronExpression.parse(row.schedule()).next(after.atZone(zone));
    return next == null ? null : next.toInstant();
  }

  private static org.springframework.scheduling.support.CronTrigger cronTrigger(
      String cron, String zone) {
    return zone == null
        ? new org.springframework.scheduling.support.CronTrigger(cron)
        : new org.springframework.scheduling.support.CronTrigger(cron, ZoneId.of(zone));
  }

  /**
   * Finds {@link Job} methods on every bean. A post-processor with no dependencies, so it does not
   * create the database beans early.
   */
  public static class Collector implements BeanPostProcessor, EmbeddedValueResolverAware {

    private final List<Found> found = new ArrayList<>();
    private final Set<String> names = new HashSet<>();
    private StringValueResolver values;

    @Override
    public void setEmbeddedValueResolver(StringValueResolver resolver) {
      this.values = resolver;
    }

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) {
      Class<?> type = AopUtils.getTargetClass(bean);
      var methods =
          MethodIntrospector.selectMethods(
              type,
              (MethodIntrospector.MetadataLookup<Job>)
                  m -> AnnotatedElementUtils.findMergedAnnotation(m, Job.class));
      methods.forEach(
          (method, job) -> {
            if (method.getParameterCount() != 0) {
              throw new IllegalStateException("@Job " + job.name() + " must take no arguments");
            }
            if (!names.add(job.name())) {
              throw new IllegalStateException("Two jobs are named " + job.name());
            }
            found.add(found(job, bean, AopUtils.selectInvocableMethod(method, bean.getClass())));
          });
      return bean;
    }

    private Found found(Job job, Object bean, Method method) {
      String every = resolve(job.every());
      String cron = resolve(job.cron());
      if (every.isEmpty() == cron.isEmpty()) {
        throw new IllegalStateException(
            "@Job " + job.name() + " needs exactly one of every or cron");
      }
      String delay = resolve(job.initialDelay());
      Duration initial = delay.isEmpty() ? Duration.ZERO : Schedules.interval(delay);
      if (cron.isEmpty()) {
        return new Found(
            job,
            bean,
            method,
            JobTriggerStore.Kind.INTERVAL,
            Schedules.interval(every).toString(),
            null,
            initial);
      }
      CronExpression.parse(cron);
      String zone = resolve(job.zone());
      if (!zone.isEmpty()) {
        ZoneId.of(zone);
      }
      return new Found(
          job,
          bean,
          method,
          JobTriggerStore.Kind.CRON,
          cron,
          zone.isEmpty() ? null : zone,
          initial);
    }

    private String resolve(String value) {
      if (value.isEmpty() || values == null) {
        return value;
      }
      String resolved = values.resolveStringValue(value);
      return resolved == null ? "" : resolved.strip();
    }

    List<Found> found() {
      return List.copyOf(found);
    }
  }
}
