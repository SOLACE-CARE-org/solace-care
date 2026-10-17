package care.solace.core.system;

/**
 * Response of {@code GET /v1/system/ping} (contracts/openapi/core.yaml, PingResult).
 *
 * @param core always true: Core answered
 * @param tracking Tracking answered over gRPC within the deadline
 * @param ai AI answered over gRPC within the deadline
 */
public record PingResult(boolean core, boolean tracking, boolean ai) {}
