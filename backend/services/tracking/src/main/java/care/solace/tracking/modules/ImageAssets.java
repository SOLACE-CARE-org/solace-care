package care.solace.tracking.modules;

import care.solace.errors.CommonError;
import care.solace.errors.SolaceException;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.Optional;
import java.util.UUID;
import javax.imageio.ImageIO;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

/**
 * Module card images (Tracking Service Spec, What a module carries; D51): 3:2, at least 1200×800,
 * JPG, PNG or WebP, at most 1 MB, in object storage, served with a phone-sized rendition.
 */
@Component
public class ImageAssets implements AutoCloseable {

  static final int MAX_BYTES = 1_048_576;
  static final int MIN_WIDTH = 1200;
  static final int MIN_HEIGHT = 800;
  static final int RENDITION_WIDTH = 600;

  public record Asset(
      UUID id, String contentType, int width, int height, String objectKey, String renditionKey) {}

  public record Stored(String contentType, byte[] bytes) {}

  private final S3Client s3;
  private final String bucket;
  private final JdbcClient db;
  private final Clock clock;

  ImageAssets(
      @Value("${solace.storage.endpoint:http://localhost:9000}") String endpoint,
      @Value("${solace.storage.region:us-east-1}") String region,
      @Value("${solace.storage.access-key:solace}") String accessKey,
      @Value("${solace.storage.secret-key:solace-dev-only}") String secretKey,
      @Value("${solace.tracking.images-bucket:solace-module-images}") String bucket,
      JdbcClient db,
      Clock clock) {
    this.s3 =
        S3Client.builder()
            .endpointOverride(java.net.URI.create(endpoint))
            .region(Region.of(region))
            .credentialsProvider(
                StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKey, secretKey)))
            .forcePathStyle(true)
            .build();
    this.bucket = bucket;
    this.db = db;
    this.clock = clock;
  }

  /** Checks and stores an image; 400 with a plain reason when it does not meet the rules. */
  public Asset upload(byte[] bytes, String uploadedBy) {
    if (bytes.length == 0 || bytes.length > MAX_BYTES) {
      throw new SolaceException(ImageError.TOO_LARGE);
    }
    String type = type(bytes).orElseThrow(() -> new SolaceException(ImageError.WRONG_TYPE));
    int[] size =
        dimensions(bytes, type).orElseThrow(() -> new SolaceException(ImageError.WRONG_TYPE));
    int w = size[0];
    int h = size[1];
    if (w < MIN_WIDTH || h < MIN_HEIGHT || Math.abs(w * 2 - h * 3) > w / 50) {
      throw new SolaceException(ImageError.WRONG_SIZE);
    }
    UUID id = UUID.randomUUID();
    String key = "modules/" + id + extension(type);
    put(key, bytes, type);
    String rendition = null;
    byte[] small = rendition(bytes, type);
    if (small != null) {
      rendition = "modules/" + id + "-600.jpg";
      put(rendition, small, "image/jpeg");
    }
    db.sql(
            """
            INSERT INTO image_asset (id, object_key, content_type, width, height, bytes, rendition_key, uploaded_by, uploaded_at)
            VALUES (:id, :k, :t, :w, :h, :b, :r, :by, :now)
            """)
        .param("id", id)
        .param("k", key)
        .param("t", type)
        .param("w", w)
        .param("h", h)
        .param("b", bytes.length)
        .param("r", rendition)
        .param("by", uploadedBy)
        .param("now", Timestamp.from(clock.instant()))
        .update();
    return new Asset(id, type, w, h, key, rendition);
  }

  public Optional<Asset> asset(UUID id) {
    return db.sql("SELECT * FROM image_asset WHERE id = :id")
        .param("id", id)
        .query(
            (rs, n) ->
                new Asset(
                    rs.getObject("id", UUID.class),
                    rs.getString("content_type"),
                    rs.getInt("width"),
                    rs.getInt("height"),
                    rs.getString("object_key"),
                    rs.getString("rendition_key")))
        .optional();
  }

  /** The phone-sized rendition when there is one, else the original. */
  public Stored read(UUID id, boolean small) {
    Asset a = asset(id).orElseThrow(() -> new SolaceException(CommonError.NOT_FOUND));
    boolean useRendition = small && a.renditionKey() != null;
    try (InputStream in =
        s3.getObject(
            GetObjectRequest.builder()
                .bucket(bucket)
                .key(useRendition ? a.renditionKey() : a.objectKey())
                .build())) {
      return new Stored(useRendition ? "image/jpeg" : a.contentType(), in.readAllBytes());
    } catch (IOException e) {
      throw new SolaceException(CommonError.DEPENDENCY_UNAVAILABLE, e);
    }
  }

  private void put(String key, byte[] bytes, String type) {
    s3.putObject(
        PutObjectRequest.builder().bucket(bucket).key(key).contentType(type).build(),
        RequestBody.fromBytes(bytes));
  }

  /** The type from the first bytes, never from what the browser claimed. */
  static Optional<String> type(byte[] b) {
    if (b.length > 3 && (b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xD8 && (b[2] & 0xFF) == 0xFF) {
      return Optional.of("image/jpeg");
    }
    if (b.length > 8 && (b[0] & 0xFF) == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G') {
      return Optional.of("image/png");
    }
    if (b.length > 30
        && b[0] == 'R'
        && b[1] == 'I'
        && b[2] == 'F'
        && b[3] == 'F'
        && b[8] == 'W'
        && b[9] == 'E'
        && b[10] == 'B'
        && b[11] == 'P') {
      return Optional.of("image/webp");
    }
    return Optional.empty();
  }

  static Optional<int[]> dimensions(byte[] b, String type) {
    if (type.equals("image/webp")) {
      return webp(b);
    }
    try {
      BufferedImage image = ImageIO.read(new ByteArrayInputStream(b));
      return image == null
          ? Optional.empty()
          : Optional.of(new int[] {image.getWidth(), image.getHeight()});
    } catch (IOException e) {
      return Optional.empty();
    }
  }

  /** Width and height from a WebP header (VP8, VP8L or VP8X), which ImageIO cannot read. */
  static Optional<int[]> webp(byte[] b) {
    String chunk = new String(b, 12, 4, java.nio.charset.StandardCharsets.US_ASCII);
    switch (chunk) {
      case "VP8X" -> {
        int w = 1 + ((b[24] & 0xFF) | (b[25] & 0xFF) << 8 | (b[26] & 0xFF) << 16);
        int h = 1 + ((b[27] & 0xFF) | (b[28] & 0xFF) << 8 | (b[29] & 0xFF) << 16);
        return Optional.of(new int[] {w, h});
      }
      case "VP8 " -> {
        int w = ((b[26] & 0xFF) | (b[27] & 0xFF) << 8) & 0x3FFF;
        int h = ((b[28] & 0xFF) | (b[29] & 0xFF) << 8) & 0x3FFF;
        return Optional.of(new int[] {w, h});
      }
      case "VP8L" -> {
        int bits =
            (b[21] & 0xFF) | (b[22] & 0xFF) << 8 | (b[23] & 0xFF) << 16 | (b[24] & 0xFF) << 24;
        return Optional.of(new int[] {(bits & 0x3FFF) + 1, ((bits >> 14) & 0x3FFF) + 1});
      }
      default -> {
        return Optional.empty();
      }
    }
  }

  private static byte[] rendition(byte[] bytes, String type) {
    if (type.equals("image/webp")) {
      return null;
    }
    try {
      BufferedImage source = ImageIO.read(new ByteArrayInputStream(bytes));
      if (source == null) {
        return null;
      }
      int w = RENDITION_WIDTH;
      int h = Math.round(source.getHeight() * (w / (float) source.getWidth()));
      BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
      Graphics2D g = out.createGraphics();
      g.setRenderingHint(
          RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
      g.drawImage(source, 0, 0, w, h, java.awt.Color.WHITE, null);
      g.dispose();
      var buffer = new ByteArrayOutputStream();
      ImageIO.write(out, "jpg", buffer);
      return buffer.toByteArray();
    } catch (IOException e) {
      return null;
    }
  }

  private static String extension(String type) {
    return switch (type) {
      case "image/png" -> ".png";
      case "image/webp" -> ".webp";
      default -> ".jpg";
    };
  }

  @Override
  public void close() {
    s3.close();
  }

  enum ImageError implements care.solace.errors.ErrorCode {
    TOO_LARGE("tracking.image-too-large", 400),
    WRONG_TYPE("tracking.image-type", 400),
    WRONG_SIZE("tracking.image-size", 400);

    private final String code;
    private final int status;

    ImageError(String code, int status) {
      this.code = code;
      this.status = status;
    }

    @Override
    public String code() {
      return code;
    }

    @Override
    public int status() {
      return status;
    }

    @Override
    public String area() {
      return "TRK";
    }
  }
}
