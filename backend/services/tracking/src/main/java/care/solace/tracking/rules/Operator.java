package care.solace.tracking.rules;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;

/**
 * One way a condition reads a signal (Tracking Service Spec, What a rule is made of; D61).
 * Operators are a fixed, tested list in code: the builder can choose among them but never define
 * one, so there is still no OR and no free-form expression.
 */
public interface Operator {

  /** The condition's {@code operator} value. */
  String code();

  /** Whether this operator can read the signal. */
  boolean accepts(SignalCatalog.Signal signal);

  /** The comparisons it allows on the signal, in display order. */
  List<String> comparisons(SignalCatalog.Signal signal);

  /** The condition fields the builder shows for it, besides signal, comparison and threshold. */
  List<String> fields();

  /** The evidence keys it fills, which rule messages can use as placeholders. */
  List<String> evidenceKeys();

  /** Problems with the operator's own fields; empty when they are fine. */
  List<String> problems(JsonNode condition);

  RuleEngine.ConditionResult evaluate(JsonNode condition, RuleEngine.Signals signals);
}
