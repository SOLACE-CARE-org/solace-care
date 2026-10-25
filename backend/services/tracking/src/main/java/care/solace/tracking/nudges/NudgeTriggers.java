package care.solace.tracking.nudges;

import care.solace.coresettings.CoreSettings;
import care.solace.events.EventEnvelope;
import care.solace.events.IdempotentConsumer;
import care.solace.jobs.Job;
import care.solace.tracking.rules.PatternCheck;
import java.time.Clock;
import java.time.Duration;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.EventListener;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/** What makes a nudge go out, or stop. */
@Component
class NudgeTriggers {

  static final String RISK_CONSUMER = "tracking.risk-flagged";

  private final CoreSettings core;
  private final Nudges nudges;
  private final IdempotentConsumer once;
  private final Clock clock;
  private final Duration riskPause;

  NudgeTriggers(
      CoreSettings core,
      Nudges nudges,
      IdempotentConsumer once,
      Clock clock,
      @Value("${solace.tracking.nudges.risk-pause:P14D}") Duration riskPause) {
    this.core = core;
    this.nudges = nudges;
    this.once = once;
    this.clock = clock;
    this.riskPause = riskPause;
  }

  @Job(
      name = "tracking.nudges.reminders",
      description = "Sends daily tracking reminders and invite pushes",
      every = "${solace.tracking.nudges.every:PT1M}",
      initialDelay = "${solace.tracking.nudges.every:PT1M}")
  void reminders() {
    nudges.sendDueReminders();
  }

  /** An invite from the pattern check, inside the push transaction. A pattern sends nothing. */
  @EventListener
  void on(EventEnvelope event) {
    if (!"tracking.recommendation.created".equals(event.type())) {
      return;
    }
    var created = event.payloadAs(PatternCheck.RecommendationCreated.class);
    if ("INVITE".equals(created.outcome())) {
      nudges.invite(UUID.fromString(event.subjectId()));
    }
  }

  /** The safety monitor flagged the person: no tracking pushes for a while (System Design). */
  @KafkaListener(topics = "ai.risk")
  void onRisk(EventEnvelope event) {
    if (!"ai.risk.flagged".equals(event.type()) || event.subjectId() == null) {
      return;
    }
    once.handle(
        RISK_CONSUMER,
        event,
        () ->
            nudges.pause(
                UUID.fromString(event.subjectId()),
                clock
                    .instant()
                    .plus(core.duration("solace.tracking.nudges.risk-pause", riskPause))));
  }
}
