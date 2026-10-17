package care.solace.core.system;

import java.net.URI;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Object storage settings ({@code solace.storage.*}). Defaults point at MinIO in
 * infra/docker-compose.yml.
 *
 * @param endpoint the S3 endpoint
 * @param region any region name; MinIO ignores it
 * @param accessKey the access key
 * @param secretKey the secret key
 * @param serverSideEncryption the S3 server-side encryption to ask for, e.g. {@code AES256}. Blank
 *     locally: MinIO without a key service refuses it. The cluster sets it (System Design,
 *     Infrastructure: "server-side encryption")
 */
@ConfigurationProperties("solace.storage")
record StorageSettings(
    @DefaultValue("http://localhost:9000") URI endpoint,
    @DefaultValue("us-east-1") String region,
    @DefaultValue("solace") String accessKey,
    @DefaultValue("solace-dev-only") String secretKey,
    @DefaultValue("") String serverSideEncryption) {}
