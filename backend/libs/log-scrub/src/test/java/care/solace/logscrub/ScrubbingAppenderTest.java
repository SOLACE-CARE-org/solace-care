package care.solace.logscrub;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

class ScrubbingAppenderTest {

  private final LoggerContext context = new LoggerContext();
  private final ListAppender<ILoggingEvent> written = new ListAppender<>();
  private Logger logger;

  @BeforeEach
  void setUp() {
    context.setMDCAdapter(MDC.getMDCAdapter());
    written.setContext(context);
    written.start();
    ScrubbingAppender scrubbing = new ScrubbingAppender();
    scrubbing.setContext(context);
    scrubbing.addAppender(written);
    scrubbing.start();
    logger = context.getLogger("test");
    logger.addAppender(scrubbing);
  }

  @AfterEach
  void tearDown() {
    MDC.clear();
    context.stop();
  }

  @Test
  void scrubsTheFormattedMessageAndDropsRawArguments() {
    logger.info("Code sent to {} for booking {}", "+237677000000", "b-42");

    ILoggingEvent event = written.list.getFirst();
    assertThat(event.getFormattedMessage()).isEqualTo("Code sent to [phone] for booking b-42");
    assertThat(event.getArgumentArray()).isNull();
  }

  @Test
  void scrubsExceptionMessagesAndTheirCauses() {
    Exception cause = new IllegalStateException("duplicate key email=awa@example.cm");
    logger.error("Sign-up failed", new RuntimeException("for +237677000000", cause));

    ILoggingEvent event = written.list.getFirst();
    assertThat(event.getThrowableProxy().getMessage()).isEqualTo("for [phone]");
    assertThat(event.getThrowableProxy().getCause().getMessage())
        .isEqualTo("duplicate key email=[redacted]");
    assertThat(event.getThrowableProxy().getStackTraceElementProxyArray()).isNotEmpty();
  }

  @Test
  void scrubsMdcValues() {
    MDC.put("email", "awa@example.cm");
    MDC.put("bookingId", "b-42");
    MDC.put("path", "/v1/guests/677000000");
    logger.info("Request");

    ILoggingEvent event = written.list.getFirst();
    assertThat(event.getMDCPropertyMap())
        .containsEntry("email", Scrubber.REDACTED)
        .containsEntry("bookingId", "b-42")
        .containsEntry("path", "/v1/guests/[phone]");
  }

  @Test
  void scrubsKeyValuePairs() {
    logger
        .atInfo()
        .setMessage("Review stored")
        .addKeyValue("text", "My therapist was kind")
        .addKeyValue("rating", 5)
        .log();

    ILoggingEvent event = written.list.getFirst();
    assertThat(event.getKeyValuePairs())
        .extracting(pair -> pair.key + "=" + pair.value)
        .containsExactly("text=" + Scrubber.REDACTED, "rating=5");
  }

  @Test
  void keepsLevelLoggerAndTime() {
    logger.warn("Plain message");

    ILoggingEvent event = written.list.getFirst();
    assertThat(event.getLevel().toString()).isEqualTo("WARN");
    assertThat(event.getLoggerName()).isEqualTo("test");
    assertThat(event.getInstant()).isNotNull();
  }
}
