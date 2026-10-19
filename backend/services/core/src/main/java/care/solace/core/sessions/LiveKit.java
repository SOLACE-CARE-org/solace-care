package care.solace.core.sessions;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jose.crypto.MACVerifier;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.ParseException;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * The media server (System Design, Calls: LiveKit). Access tokens are HS256 JWTs signed with the
 * API secret, carrying a {@code video} grant; webhooks carry the same kind of token with the body's
 * SHA-256. Rooms are made through the RoomService API.
 */
@Component
class LiveKit {

  private static final Logger LOG = LoggerFactory.getLogger(LiveKit.class);

  /** A dropped participant keeps their place for 2 minutes (D18). */
  static final Duration RECONNECT = Duration.ofMinutes(2);

  private final SessionSettings settings;
  private final RestClient http;

  LiveKit(SessionSettings settings, RestClient.Builder builder) {
    this.settings = settings;
    this.http = builder.baseUrl(settings.apiUrl().toString()).build();
  }

  /** A token to join one room as {@code identity}; audio sessions may only publish a microphone. */
  String joinToken(String room, String identity, boolean video, Instant now, Instant expires) {
    Map<String, Object> grant =
        Map.of(
            "room", room,
            "roomJoin", true,
            "canPublish", true,
            "canSubscribe", true,
            "canPublishData", true,
            "canPublishSources", video ? List.of("microphone", "camera") : List.of("microphone"));
    return sign(identity, grant, now, expires);
  }

  /**
   * Creates the room, two people at most, keeping a dropped participant for 2 minutes. Joining also
   * creates it, so a failure here only loses those limits; it is logged, not raised.
   */
  void createRoom(String room, Duration emptyTimeout, Instant now) {
    String admin = sign("solace-core", Map.of("roomCreate", true), now, now.plusSeconds(60));
    try {
      http.post()
          .uri("/twirp/livekit.RoomService/CreateRoom")
          .header("Authorization", "Bearer " + admin)
          .contentType(MediaType.APPLICATION_JSON)
          .body(
              Map.of(
                  "name",
                  room,
                  "empty_timeout",
                  emptyTimeout.toSeconds(),
                  "departure_timeout",
                  RECONNECT.toSeconds(),
                  "max_participants",
                  2))
          .retrieve()
          .toBodilessEntity();
    } catch (RestClientException e) {
      LOG.warn("Could not create media room {}", room, e);
    }
  }

  /**
   * Starts an audio-only recording of the room to {@code filepath} in the egress service's own
   * encrypted bucket (infra: the scribe-audio bucket, never public). Null when it could not start:
   * the session goes on unrecorded.
   */
  String startAudioEgress(String room, String filepath, Instant now) {
    String admin = sign("solace-core", Map.of("roomRecord", true), now, now.plusSeconds(60));
    try {
      Map<?, ?> info =
          http.post()
              .uri("/twirp/livekit.Egress/StartRoomCompositeEgress")
              .header("Authorization", "Bearer " + admin)
              .contentType(MediaType.APPLICATION_JSON)
              .body(
                  Map.of(
                      "room_name",
                      room,
                      "audio_only",
                      true,
                      "file_outputs",
                      List.of(Map.of("file_type", "OGG", "filepath", filepath))))
              .retrieve()
              .body(Map.class);
      Object id = info == null ? null : info.get("egress_id");
      return id == null ? null : id.toString();
    } catch (RestClientException e) {
      LOG.warn("Could not start the scribe recording for room {}", room, e);
      return null;
    }
  }

  /** Stops a recording; a failure is logged, the egress ends with the room anyway. */
  void stopEgress(String egressId, Instant now) {
    if (egressId == null) {
      return;
    }
    String admin = sign("solace-core", Map.of("roomRecord", true), now, now.plusSeconds(60));
    try {
      http.post()
          .uri("/twirp/livekit.Egress/StopEgress")
          .header("Authorization", "Bearer " + admin)
          .contentType(MediaType.APPLICATION_JSON)
          .body(Map.of("egress_id", egressId))
          .retrieve()
          .toBodilessEntity();
    } catch (RestClientException e) {
      LOG.warn("Could not stop recording {}", egressId, e);
    }
  }

  /**
   * A SIP dispatch rule that sends a caller who enters {@code pin} to {@code room} (D59). Null when
   * the media server refused: the app then offers joining by data only.
   */
  String createDialInRule(String room, String pin, List<String> trunks, Instant now) {
    try {
      Map<?, ?> info =
          http.post()
              .uri("/twirp/livekit.SIP/CreateSIPDispatchRule")
              .header("Authorization", "Bearer " + sipAdmin(now))
              .contentType(MediaType.APPLICATION_JSON)
              .body(
                  Map.of(
                      "rule",
                      Map.of("dispatch_rule_direct", Map.of("room_name", room, "pin", pin)),
                      "trunk_ids",
                      trunks,
                      "name",
                      "booking-" + room))
              .retrieve()
              .body(Map.class);
      Object id = info == null ? null : info.get("sip_dispatch_rule_id");
      return id == null ? null : id.toString();
    } catch (RestClientException e) {
      LOG.warn("Could not create the dial-in rule for room {}", room, e);
      return null;
    }
  }

  /** Removes a dial-in rule; a failure is logged and tried again on the next sweep. */
  boolean deleteDialInRule(String ruleId, Instant now) {
    try {
      http.post()
          .uri("/twirp/livekit.SIP/DeleteSIPDispatchRule")
          .header("Authorization", "Bearer " + sipAdmin(now))
          .contentType(MediaType.APPLICATION_JSON)
          .body(Map.of("sip_dispatch_rule_id", ruleId))
          .retrieve()
          .toBodilessEntity();
      return true;
    } catch (RestClientException e) {
      LOG.warn("Could not delete dial-in rule {}", ruleId, e);
      return false;
    }
  }

  private String sipAdmin(Instant now) {
    try {
      var claims =
          new JWTClaimsSet.Builder()
              .issuer(settings.apiKey())
              .subject("solace-core")
              .notBeforeTime(Date.from(now.minusSeconds(10)))
              .expirationTime(Date.from(now.plusSeconds(60)))
              .claim("sip", Map.of("admin", true, "call", true))
              .build();
      var jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims);
      jwt.sign(new MACSigner(secret()));
      return jwt.serialize();
    } catch (JOSEException e) {
      throw new IllegalStateException("LiveKit SIP token", e);
    }
  }

  /** True when the Authorization token is ours and carries the body's SHA-256. */
  boolean verifyWebhook(String authorization, byte[] body) {
    if (authorization == null || authorization.isBlank()) {
      return false;
    }
    try {
      SignedJWT jwt = SignedJWT.parse(authorization.replaceFirst("^Bearer ", "").trim());
      if (!jwt.verify(new MACVerifier(secret()))) {
        return false;
      }
      JWTClaimsSet claims = jwt.getJWTClaimsSet();
      if (!settings.apiKey().equals(claims.getIssuer())) {
        return false;
      }
      Date exp = claims.getExpirationTime();
      if (exp != null && exp.toInstant().isBefore(Instant.now().minusSeconds(60))) {
        return false;
      }
      String expected =
          Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(body));
      return MessageDigest.isEqual(
          expected.getBytes(StandardCharsets.US_ASCII),
          String.valueOf(claims.getClaim("sha256")).getBytes(StandardCharsets.US_ASCII));
    } catch (ParseException | JOSEException | NoSuchAlgorithmException e) {
      return false;
    }
  }

  String url() {
    return settings.url();
  }

  String sign(String identity, Map<String, Object> grant, Instant now, Instant expires) {
    try {
      var claims =
          new JWTClaimsSet.Builder()
              .issuer(settings.apiKey())
              .subject(identity)
              .jwtID(identity + "-" + now.toEpochMilli())
              .notBeforeTime(Date.from(now.minusSeconds(10)))
              .expirationTime(Date.from(expires))
              .claim("video", grant)
              .build();
      var jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims);
      jwt.sign(new MACSigner(secret()));
      return jwt.serialize();
    } catch (JOSEException e) {
      throw new IllegalStateException("LiveKit token", e);
    }
  }

  private byte[] secret() {
    return settings.apiSecret().getBytes(StandardCharsets.UTF_8);
  }
}
