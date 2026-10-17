package care.solace.core.system;

import java.io.InputStream;

/**
 * S3-compatible object storage (MinIO locally): specialist credentials now, content images later.
 * Objects are private; nothing is ever served from a public URL.
 */
public interface ObjectStorage {

  /** Stores the bytes under {@code key}, encrypted at rest where the store supports it. */
  void put(String bucket, String key, byte[] bytes, String contentType);

  /** The object's bytes; the caller closes the stream. */
  InputStream get(String bucket, String key);

  /** Removes the object; nothing happens if it is already gone. */
  void delete(String bucket, String key);
}
