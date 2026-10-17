package care.solace.core.system;

import java.io.InputStream;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.ServerSideEncryption;

/** {@link ObjectStorage} on the AWS SDK, with path-style addressing so MinIO works too. */
@Component
@EnableConfigurationProperties(StorageSettings.class)
class S3ObjectStorage implements ObjectStorage, AutoCloseable {

  private final S3Client s3;
  private final StorageSettings settings;

  S3ObjectStorage(StorageSettings settings) {
    this.settings = settings;
    this.s3 =
        S3Client.builder()
            .endpointOverride(settings.endpoint())
            .region(Region.of(settings.region()))
            .credentialsProvider(
                StaticCredentialsProvider.create(
                    AwsBasicCredentials.create(settings.accessKey(), settings.secretKey())))
            .forcePathStyle(true)
            .build();
  }

  @Override
  public void put(String bucket, String key, byte[] bytes, String contentType) {
    var request = PutObjectRequest.builder().bucket(bucket).key(key).contentType(contentType);
    if (!settings.serverSideEncryption().isBlank()) {
      request.serverSideEncryption(ServerSideEncryption.fromValue(settings.serverSideEncryption()));
    }
    s3.putObject(request.build(), RequestBody.fromBytes(bytes));
  }

  @Override
  public InputStream get(String bucket, String key) {
    return s3.getObject(GetObjectRequest.builder().bucket(bucket).key(key).build());
  }

  @Override
  public void delete(String bucket, String key) {
    s3.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(key).build());
  }

  @Override
  public void close() {
    s3.close();
  }
}
