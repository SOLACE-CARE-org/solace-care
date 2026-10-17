package care.solace.core.specialists;

import care.solace.core.system.ObjectStorage;
import care.solace.errors.CommonError;
import care.solace.errors.SolaceException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Credential documents (SpecDocuments). The type is read from the file's first bytes, never from
 * the name or the declared type. Files are stored first, then the rows are swapped in one
 * transaction; replaced files are removed only once that has committed.
 */
@Service
class Documents {

  private static final Logger LOG = LoggerFactory.getLogger(Documents.class);

  /** IDENTITY takes front and back; every other kind takes one file. */
  static final int MAX_IDENTITY_FILES = 2;

  static final String PDF = "application/pdf";
  static final String JPEG = "image/jpeg";
  static final String PNG = "image/png";

  private final DocumentStore store;
  private final ObjectStorage storage;
  private final SpecialistSettings settings;
  private final TransactionTemplate transactions;
  private final Clock clock;

  Documents(
      DocumentStore store,
      ObjectStorage storage,
      SpecialistSettings settings,
      TransactionTemplate transactions,
      Clock clock) {
    this.store = store;
    this.storage = storage;
    this.settings = settings;
    this.transactions = transactions;
    this.clock = clock;
  }

  /** One uploaded file as the controller received it. */
  record Upload(String fileName, byte[] bytes) {}

  enum Kind {
    LICENCE,
    IDENTITY,
    PHOTO,
    CV
  }

  /** Kinds an application needs before it can be submitted; the CV is optional. */
  static final List<String> REQUIRED = List.of("LICENCE", "IDENTITY", "PHOTO");

  void replace(UUID accountId, Kind kind, List<Upload> uploads) {
    int allowed = kind == Kind.IDENTITY ? MAX_IDENTITY_FILES : 1;
    if (uploads.isEmpty() || uploads.size() > allowed) {
      throw new SolaceException(CommonError.REQUEST_INVALID);
    }
    long max = settings.documents().maxFileSize().toBytes();
    Instant now = clock.instant();
    List<DocumentStore.StoredFile> files = new ArrayList<>();
    for (Upload upload : uploads) {
      if (upload.bytes().length == 0) {
        throw new SolaceException(CommonError.REQUEST_INVALID);
      }
      if (upload.bytes().length > max) {
        throw new SolaceException(CommonError.TOO_LARGE);
      }
      String type = sniff(upload.bytes());
      if (type == null || (kind == Kind.PHOTO && type.equals(PDF))) {
        throw new SolaceException(SpecialistError.DOCUMENT_TYPE);
      }
      UUID id = UUID.randomUUID();
      files.add(
          new DocumentStore.StoredFile(
              id,
              kind.name(),
              "specialists/" + accountId + "/" + id,
              safeName(upload.fileName(), type),
              type,
              upload.bytes().length,
              now));
    }

    String bucket = settings.documents().bucket();
    List<String> stored = new ArrayList<>();
    try {
      for (int i = 0; i < files.size(); i++) {
        storage.put(
            bucket, files.get(i).objectKey(), uploads.get(i).bytes(), files.get(i).contentType());
        stored.add(files.get(i).objectKey());
      }
    } catch (RuntimeException e) {
      removeQuietly(bucket, stored);
      throw e;
    }

    List<String> replaced;
    try {
      replaced = transactions.execute(s -> store.replace(accountId, kind.name(), files));
    } catch (RuntimeException e) {
      removeQuietly(bucket, stored);
      throw e;
    }
    removeQuietly(bucket, replaced == null ? List.of() : replaced);
  }

  /** The file type from its signature, or null when it is none of the accepted ones. */
  static String sniff(byte[] bytes) {
    if (starts(bytes, 0x25, 0x50, 0x44, 0x46, 0x2D)) {
      return PDF; // %PDF-
    }
    if (starts(bytes, 0xFF, 0xD8, 0xFF)) {
      return JPEG;
    }
    if (starts(bytes, 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)) {
      return PNG;
    }
    return null;
  }

  /**
   * The name shown back to the specialist: no path, no control characters, at most 200 characters.
   * Never used to build a storage key.
   */
  static String safeName(String original, String type) {
    String name = original == null ? "" : original;
    name = name.substring(Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\')) + 1);
    name = name.replaceAll("\\p{Cntrl}", "").strip();
    if (name.isEmpty()) {
      name =
          switch (type) {
            case PDF -> "document.pdf";
            case PNG -> "image.png";
            default -> "image.jpg";
          };
    }
    return name.length() > 200 ? name.substring(name.length() - 200) : name;
  }

  private static boolean starts(byte[] bytes, int... signature) {
    if (bytes.length < signature.length) {
      return false;
    }
    for (int i = 0; i < signature.length; i++) {
      if ((bytes[i] & 0xFF) != signature[i]) {
        return false;
      }
    }
    return true;
  }

  private void removeQuietly(String bucket, List<String> keys) {
    for (String key : keys) {
      try {
        storage.delete(bucket, key);
      } catch (RuntimeException e) {
        // An orphaned object holds no row; log the key (a random id) and move on.
        LOG.warn("Could not remove stored document {}", key, e);
      }
    }
  }
}
