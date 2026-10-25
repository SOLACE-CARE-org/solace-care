package care.solace.core.payments;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Notch Pay's webhook format, which the sandbox copies so both run through the same path: a JSON
 * body with {@code type} (payment.complete, payment.failed, transfer.complete, ...) and {@code
 * data}, signed with HMAC-SHA256 of the raw body, hex, in {@code X-Notch-Signature}.
 */
final class NotchPayFormat {

  static final String SIGNATURE_HEADER = "X-Notch-Signature";

  private static final ObjectMapper JSON = new ObjectMapper();

  private NotchPayFormat() {}

  static String sign(String secret, byte[] body) {
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
      return HexFormat.of().formatHex(mac.doFinal(body));
    } catch (NoSuchAlgorithmException | InvalidKeyException e) {
      throw new IllegalStateException(e);
    }
  }

  /** Constant-time comparison; a missing secret never verifies. */
  static boolean verify(String secret, String signature, byte[] body) {
    if (secret == null || secret.isBlank() || signature == null || signature.isBlank()) {
      return false;
    }
    return MessageDigest.isEqual(
        sign(secret, body).getBytes(StandardCharsets.US_ASCII),
        signature.trim().toLowerCase().getBytes(StandardCharsets.US_ASCII));
  }

  static Optional<PaymentProvider.Notification> parse(byte[] body) {
    JsonNode root;
    try {
      root = JSON.readTree(body);
    } catch (Exception e) {
      return Optional.empty();
    }
    String type = root.path("type").asText(root.path("event").asText(""));
    JsonNode data = root.path("data");
    PaymentProvider.Kind kind;
    if (type.startsWith("payment.")) {
      kind = PaymentProvider.Kind.PAYMENT;
    } else if (type.startsWith("transfer.")) {
      kind = PaymentProvider.Kind.TRANSFER;
    } else {
      return Optional.empty();
    }
    PaymentProvider.Status status =
        switch (type.substring(type.indexOf('.') + 1)) {
          case "complete" -> PaymentProvider.Status.COMPLETE;
          case "failed", "canceled", "expired" -> PaymentProvider.Status.FAILED;
          default -> null;
        };
    if (status == null) {
      return Optional.empty();
    }
    String providerRef = text(data, "reference");
    String reference = text(data, "merchant_reference");
    if (providerRef == null && reference == null) {
      return Optional.empty();
    }
    String eventId = text(root, "id");
    if (eventId == null) {
      eventId = type + ":" + (providerRef != null ? providerRef : reference);
    }
    String reason = text(data, "failure_reason");
    if (reason == null) {
      reason = text(data, "message");
    }
    return Optional.of(
        new PaymentProvider.Notification(eventId, kind, providerRef, reference, status, reason));
  }

  static PaymentProvider.Status status(String providerStatus) {
    return switch (providerStatus == null ? "" : providerStatus.toLowerCase()) {
      case "complete", "completed", "success", "successful" -> PaymentProvider.Status.COMPLETE;
      case "failed", "canceled", "cancelled", "expired", "rejected" ->
          PaymentProvider.Status.FAILED;
      default -> PaymentProvider.Status.PENDING;
    };
  }

  private static String text(JsonNode node, String field) {
    JsonNode value = node.path(field);
    return value.isMissingNode() || value.isNull() || value.asText().isBlank()
        ? null
        : value.asText();
  }
}
