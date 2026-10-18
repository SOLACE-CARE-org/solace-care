package care.solace.core.notifications;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.KeyAgreement;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;

/** Web Push encryption and VAPID, checked the way a browser and a push service would (D47). */
class WebPushTest {

  private static final Base64.Encoder B64 = Base64.getUrlEncoder().withoutPadding();

  @Test
  void aBrowserCanDecryptWhatCoreSends() throws Exception {
    var browser = WebPush.keyPair();
    byte[] uaPublic = WebPush.uncompressed((ECPublicKey) browser.getPublic());
    byte[] auth = new byte[16];
    new SecureRandom().nextBytes(auth);
    byte[] message =
        "{\"title\":\"Solace\",\"body\":\"2 refunds are waiting.\"}"
            .getBytes(StandardCharsets.UTF_8);

    byte[] body = WebPush.encrypt(message, B64.encodeToString(uaPublic), B64.encodeToString(auth));

    // The header (RFC 8188): salt, record size, key id length, the sender's public key.
    byte[] salt = Arrays.copyOfRange(body, 0, 16);
    assertThat(ByteBuffer.wrap(body, 16, 4).getInt()).isEqualTo(4096);
    int idLength = body[20];
    assertThat(idLength).isEqualTo(65);
    byte[] asPublic = Arrays.copyOfRange(body, 21, 21 + idLength);
    byte[] ciphertext = Arrays.copyOfRange(body, 21 + idLength, body.length);

    var agreement = KeyAgreement.getInstance("ECDH");
    agreement.init((ECPrivateKey) browser.getPrivate());
    agreement.doPhase(WebPush.publicKey(asPublic), true);
    byte[][] keys = WebPush.keys(agreement.generateSecret(), auth, uaPublic, asPublic, salt);
    var cipher = Cipher.getInstance("AES/GCM/NoPadding");
    cipher.init(
        Cipher.DECRYPT_MODE, new SecretKeySpec(keys[0], "AES"), new GCMParameterSpec(128, keys[1]));
    byte[] plain = cipher.doFinal(ciphertext);

    assertThat(plain[plain.length - 1]).isEqualTo((byte) 2);
    assertThat(new String(Arrays.copyOf(plain, plain.length - 1), StandardCharsets.UTF_8))
        .contains("2 refunds are waiting.");
  }

  @Test
  void theVapidTokenIsSignedForThePushServiceOrigin() throws Exception {
    var pair = WebPush.keyPair();
    byte[] d = ((ECPrivateKey) pair.getPrivate()).getS().toByteArray();
    var vapid =
        WebPush.Vapid.of(
            B64.encodeToString(WebPush.uncompressed((ECPublicKey) pair.getPublic())),
            B64.encodeToString(d));

    String header =
        WebPush.authorization(
            vapid,
            URI.create("https://fcm.googleapis.com/fcm/send/abc"),
            "mailto:ops@solace.care",
            Instant.parse("2026-10-18T10:00:00Z"));

    assertThat(header).startsWith("vapid t=").contains(", k=" + vapid.publicKeyB64());
    String jwt = header.substring("vapid t=".length(), header.indexOf(','));
    String[] parts = jwt.split("\\.");
    String claims = new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8);
    assertThat(claims).contains("\"aud\":\"https://fcm.googleapis.com\"");

    byte[] raw = Base64.getUrlDecoder().decode(parts[2]);
    assertThat(raw).hasSize(64);
    var verifier = Signature.getInstance("SHA256withECDSAinP1363Format");
    verifier.initVerify(pair.getPublic());
    verifier.update((parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII));
    assertThat(verifier.verify(raw)).isTrue();
  }
}
