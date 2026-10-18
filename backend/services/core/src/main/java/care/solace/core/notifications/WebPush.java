package care.solace.core.notifications;

import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.AlgorithmParameters;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.ECParameterSpec;
import java.security.spec.ECPoint;
import java.security.spec.ECPrivateKeySpec;
import java.security.spec.ECPublicKeySpec;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.KeyAgreement;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Web Push message encryption (RFC 8291, aes128gcm) and VAPID signatures (RFC 8292) with the JDK
 * alone, so staff notifications carry their text end to end encrypted to the browser (D47).
 */
final class WebPush {

  private static final SecureRandom RANDOM = new SecureRandom();
  private static final Base64.Encoder B64 = Base64.getUrlEncoder().withoutPadding();
  private static final Base64.Decoder D64 = Base64.getUrlDecoder();
  static final ECParameterSpec P256 = p256();

  private WebPush() {}

  /** An application server key pair, as VAPID's public key (65 bytes) and private scalar. */
  record Vapid(byte[] publicKey, ECPrivateKey privateKey) {

    static Vapid of(String publicKeyB64, String privateKeyB64) {
      try {
        var spec = new ECPrivateKeySpec(new BigInteger(1, D64.decode(privateKeyB64)), P256);
        var key = (ECPrivateKey) KeyFactory.getInstance("EC").generatePrivate(spec);
        return new Vapid(D64.decode(publicKeyB64), key);
      } catch (GeneralSecurityException e) {
        throw new IllegalArgumentException("Invalid VAPID key", e);
      }
    }

    String publicKeyB64() {
      return B64.encodeToString(publicKey);
    }
  }

  /** Generates a key pair, for setting up VAPID and for tests. */
  static KeyPair keyPair() {
    try {
      var generator = KeyPairGenerator.getInstance("EC");
      generator.initialize(new ECGenParameterSpec("secp256r1"));
      return generator.generateKeyPair();
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException(e);
    }
  }

  static byte[] uncompressed(ECPublicKey key) {
    byte[] x = fixed(key.getW().getAffineX().toByteArray());
    byte[] y = fixed(key.getW().getAffineY().toByteArray());
    byte[] out = new byte[65];
    out[0] = 4;
    System.arraycopy(x, 0, out, 1, 32);
    System.arraycopy(y, 0, out, 33, 32);
    return out;
  }

  static ECPublicKey publicKey(byte[] uncompressed) throws GeneralSecurityException {
    if (uncompressed.length != 65 || uncompressed[0] != 4) {
      throw new GeneralSecurityException("Not an uncompressed P-256 point");
    }
    var point =
        new ECPoint(
            new BigInteger(1, Arrays.copyOfRange(uncompressed, 1, 33)),
            new BigInteger(1, Arrays.copyOfRange(uncompressed, 33, 65)));
    return (ECPublicKey)
        KeyFactory.getInstance("EC").generatePublic(new ECPublicKeySpec(point, P256));
  }

  /**
   * The aes128gcm body for one subscription (RFC 8291, section 3 and 4).
   *
   * @param p256dh the browser's public key, base64url
   * @param auth the browser's 16-byte auth secret, base64url
   */
  static byte[] encrypt(byte[] payload, String p256dh, String auth)
      throws GeneralSecurityException {
    byte[] uaPublic = D64.decode(p256dh);
    byte[] authSecret = D64.decode(auth);
    KeyPair ephemeral = keyPair();
    byte[] asPublic = uncompressed((ECPublicKey) ephemeral.getPublic());

    var agreement = KeyAgreement.getInstance("ECDH");
    agreement.init(ephemeral.getPrivate());
    agreement.doPhase(publicKey(uaPublic), true);
    byte[] ecdhSecret = agreement.generateSecret();

    byte[] salt = new byte[16];
    RANDOM.nextBytes(salt);
    byte[][] keys = keys(ecdhSecret, authSecret, uaPublic, asPublic, salt);

    byte[] plaintext = Arrays.copyOf(payload, payload.length + 1);
    plaintext[payload.length] = 2; // the last (and only) record
    var cipher = Cipher.getInstance("AES/GCM/NoPadding");
    cipher.init(
        Cipher.ENCRYPT_MODE, new SecretKeySpec(keys[0], "AES"), new GCMParameterSpec(128, keys[1]));
    byte[] ciphertext = cipher.doFinal(plaintext);

    var body = new ByteArrayOutputStream();
    body.writeBytes(salt);
    body.writeBytes(ByteBuffer.allocate(4).putInt(4096).array());
    body.write(asPublic.length);
    body.writeBytes(asPublic);
    body.writeBytes(ciphertext);
    return body.toByteArray();
  }

  /** The content encryption key and nonce (RFC 8291, section 3.4). */
  static byte[][] keys(
      byte[] ecdhSecret, byte[] authSecret, byte[] uaPublic, byte[] asPublic, byte[] salt)
      throws GeneralSecurityException {
    byte[] prkKey = hmac(authSecret, ecdhSecret);
    byte[] keyInfo =
        concat(
            "WebPush: info\0".getBytes(StandardCharsets.US_ASCII),
            uaPublic,
            asPublic,
            new byte[] {1});
    byte[] ikm = hmac(prkKey, keyInfo);
    byte[] prk = hmac(salt, ikm);
    byte[] cek =
        Arrays.copyOf(
            hmac(
                prk,
                concat(
                    "Content-Encoding: aes128gcm\0".getBytes(StandardCharsets.US_ASCII),
                    new byte[] {1})),
            16);
    byte[] nonce =
        Arrays.copyOf(
            hmac(
                prk,
                concat(
                    "Content-Encoding: nonce\0".getBytes(StandardCharsets.US_ASCII),
                    new byte[] {1})),
            12);
    return new byte[][] {cek, nonce};
  }

  /** The Authorization header value: {@code vapid t=<jwt>, k=<public key>} (RFC 8292). */
  static String authorization(Vapid vapid, URI endpoint, String subject, Instant now)
      throws GeneralSecurityException {
    String audience = endpoint.getScheme() + "://" + endpoint.getAuthority();
    String header =
        B64.encodeToString("{\"typ\":\"JWT\",\"alg\":\"ES256\"}".getBytes(StandardCharsets.UTF_8));
    String claims =
        B64.encodeToString(
            ("{\"aud\":\""
                    + audience
                    + "\",\"exp\":"
                    + now.plusSeconds(12 * 3600).getEpochSecond()
                    + ",\"sub\":\""
                    + subject
                    + "\"}")
                .getBytes(StandardCharsets.UTF_8));
    String signingInput = header + "." + claims;
    var signer = Signature.getInstance("SHA256withECDSA");
    signer.initSign(vapid.privateKey());
    signer.update(signingInput.getBytes(StandardCharsets.US_ASCII));
    String jwt = signingInput + "." + B64.encodeToString(raw(signer.sign()));
    return "vapid t=" + jwt + ", k=" + vapid.publicKeyB64();
  }

  /** A DER ECDSA signature as the 64-byte R || S that JWS ES256 uses. */
  static byte[] raw(byte[] der) {
    int offset = 3;
    int rLength = der[offset];
    byte[] r = Arrays.copyOfRange(der, offset + 1, offset + 1 + rLength);
    offset = offset + 1 + rLength + 1;
    int sLength = der[offset];
    byte[] s = Arrays.copyOfRange(der, offset + 1, offset + 1 + sLength);
    return concat(fixed(r), fixed(s));
  }

  /** A big-endian integer as exactly 32 bytes. */
  private static byte[] fixed(byte[] value) {
    if (value.length == 32) {
      return value;
    }
    byte[] out = new byte[32];
    if (value.length > 32) {
      System.arraycopy(value, value.length - 32, out, 0, 32);
    } else {
      System.arraycopy(value, 0, out, 32 - value.length, value.length);
    }
    return out;
  }

  static byte[] hmac(byte[] key, byte[] data) throws GeneralSecurityException {
    var mac = Mac.getInstance("HmacSHA256");
    mac.init(new SecretKeySpec(key, "HmacSHA256"));
    return mac.doFinal(data);
  }

  static byte[] concat(byte[]... parts) {
    var out = new ByteArrayOutputStream();
    for (byte[] p : parts) {
      out.writeBytes(p);
    }
    return out.toByteArray();
  }

  private static ECParameterSpec p256() {
    try {
      var parameters = AlgorithmParameters.getInstance("EC");
      parameters.init(new ECGenParameterSpec("secp256r1"));
      return parameters.getParameterSpec(ECParameterSpec.class);
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException(e);
    }
  }
}
