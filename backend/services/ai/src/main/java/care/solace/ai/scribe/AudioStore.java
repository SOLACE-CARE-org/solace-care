package care.solace.ai.scribe;

import java.net.URI;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.ResponseTransformer;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.S3Exception;

/**
 * Session audio in the private scribe bucket (System Design, Recording; D57). The egress writes it;
 * this reads it once to transcribe and deletes it when the note is signed or discarded, after 7
 * days at most. The bucket also expires anything older.
 */
public interface AudioStore {

  Optional<byte[]> read(String key);

  void delete(String key);

  /**
   * @param endpoint S3-compatible endpoint (MinIO locally)
   * @param bucket the private bucket the egress writes to
   */
  @ConfigurationProperties("solace.ai.scribe")
  record Settings(
      @DefaultValue("http://localhost:9000") URI endpoint,
      @DefaultValue("us-east-1") String region,
      @DefaultValue("solace-scribe-audio") String bucket,
      @DefaultValue("solace") String accessKey,
      @DefaultValue("solace-dev-only") String secretKey) {}

  @Component
  @EnableConfigurationProperties(Settings.class)
  class S3 implements AudioStore {

    private static final Logger LOG = LoggerFactory.getLogger(AudioStore.class);

    private final S3Client s3;
    private final String bucket;

    S3(Settings settings) {
      this.bucket = settings.bucket();
      this.s3 =
          S3Client.builder()
              .endpointOverride(settings.endpoint())
              .region(Region.of(settings.region()))
              .credentialsProvider(
                  StaticCredentialsProvider.create(
                      AwsBasicCredentials.create(settings.accessKey(), settings.secretKey())))
              .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
              .build();
    }

    @Override
    public Optional<byte[]> read(String key) {
      try {
        return Optional.of(
            s3.getObject(b -> b.bucket(bucket).key(key), ResponseTransformer.toBytes())
                .asByteArray());
      } catch (NoSuchKeyException e) {
        return Optional.empty();
      } catch (S3Exception | software.amazon.awssdk.core.exception.SdkClientException e) {
        LOG.warn("Could not read session audio");
        return Optional.empty();
      }
    }

    @Override
    public void delete(String key) {
      s3.deleteObject(b -> b.bucket(bucket).key(key));
    }
  }
}
