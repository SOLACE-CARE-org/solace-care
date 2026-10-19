package care.solace.tracking.rules;

import care.solace.contracts.ai.v1.ExplainRecommendationRequest;
import care.solace.contracts.ai.v1.ExplainRecommendationResponse;
import care.solace.contracts.ai.v1.InsightServiceGrpc.InsightServiceBlockingStub;
import care.solace.grpc.ResilientCalls;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Asks the AI service to word a new recommendation plainly, from its evidence only (System Design,
 * Recommendation flow; D56). Runs after the pattern check commits, never in the push. If the AI is
 * off, refuses, or does not answer in 2 seconds, the rule's reviewed message stays. Notes never
 * travel: the evidence holds thresholds, counts and dates only.
 */
@Component
class RecommendationWording {

  private static final Logger LOG = LoggerFactory.getLogger(RecommendationWording.class);
  private static final ObjectMapper JSON = new ObjectMapper();

  private final JdbcClient db;
  private final InsightServiceBlockingStub ai;
  private final ResilientCalls calls;

  RecommendationWording(JdbcClient db, InsightServiceBlockingStub ai, ResilientCalls calls) {
    this.db = db;
    this.ai = ai;
    this.calls = calls;
  }

  @Async
  @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
  void on(PatternCheck.Stored stored) {
    try {
      word(stored.recommendationId());
    } catch (RuntimeException e) {
      LOG.warn("Wording a recommendation failed; the rule's message stays");
    }
  }

  void word(UUID id) {
    var row =
        db.sql(
                """
                SELECT r.user_id, r.rule_code, r.evidence_json, m.code AS module_code,
                       ru.message_en, ru.message_fr
                  FROM recommendation r JOIN module m ON m.id = r.module_id JOIN rule ru ON ru.id = r.rule_id
                 WHERE r.id = :id
                """)
            .param("id", id)
            .query(
                (rs, n) ->
                    new Object[] {
                      rs.getObject("user_id", UUID.class),
                      rs.getString("rule_code"),
                      rs.getString("evidence_json"),
                      rs.getString("module_code"),
                      rs.getString("message_en"),
                      rs.getString("message_fr")
                    })
            .optional();
    if (row.isEmpty()) {
      return;
    }
    Object[] r = row.get();
    Map<String, Object> values;
    try {
      values = JSON.readValue((String) r[2], new TypeReference<>() {});
    } catch (Exception e) {
      return;
    }
    var request =
        ExplainRecommendationRequest.newBuilder()
            .setRecommendationId(id.toString())
            .setSubjectId(r[0].toString())
            .setRuleCode((String) r[1])
            .setModuleCode((String) r[3])
            .setEvidenceJson((String) r[2])
            .setMessageEn(RuleRules.render((String) r[4], values))
            .setMessageFr(RuleRules.render((String) r[5], values))
            .build();
    ExplainRecommendationResponse answer =
        calls.call(
            "ai",
            () -> ai.explainRecommendation(request),
            failure -> ExplainRecommendationResponse.getDefaultInstance());
    if (!answer.getWorded()) {
      return;
    }
    db.sql(
            """
            UPDATE recommendation SET wording_en = :en, wording_fr = :fr, wording_version = :v
             WHERE id = :id
            """)
        .param("en", answer.getTextEn())
        .param("fr", answer.getTextFr())
        .param("v", answer.getPromptVersion())
        .param("id", id)
        .update();
  }
}
