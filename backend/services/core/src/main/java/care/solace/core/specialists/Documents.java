package care.solace.core.specialists;

import care.solace.core.system.ObjectStorage;
import care.solace.errors.CommonError;
import care.solace.errors.SolaceException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Credential documents (SpecDocuments). The type is read from the file's first bytes, never from
 * the name or the declared type. Files are stored first, then the rows are swapped in one
 * transaction; replaced files are removed only once that has committed. A LICENCE is for one
 * profession, and a CERTIFICATE comes with a specialty or a specialty request (D62).
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
    CV,
    CERTIFICATE
  }

  /**
   * Kinds an application needs before it can be submitted, besides a LICENCE for every profession;
   * the CV is optional.
   */
  static final List<String> REQUIRED = List.of("IDENTITY", "PHOTO");

  /**
   * Replaces every file of one kind, or for a LICENCE every file of that profession.
   *
   * @param profession the profession a LICENCE is for; null for the other kinds
   */
  void replace(UUID accountId, Kind kind, String profession, List<Upload> uploads) {
    int allowed = kind == Kind.IDENTITY ? MAX_IDENTITY_FILES : 1;
    if (uploads.isEmpty()
        || uploads.size() > allowed
        || kind == Kind.CERTIFICATE
        || (kind == Kind.LICENCE) != (profession != null)) {
      throw new SolaceException(CommonError.REQUEST_INVALID);
    }
    List<DocumentStore.StoredFile> files = new ArrayList<>();
    for (Upload upload : uploads) {
      files.add(checked(accountId, kind, profession, upload));
    }
    String bucket = settings.documents().bucket();
    List<String> stored = store(bucket, files, uploads);
    List<String> replaced;
    try {
      replaced =
          transactions.execute(s -> store.replace(accountId, kind.name(), profession, files));
    } catch (RuntimeException e) {
      removeQuietly(bucket, stored);
      throw e;
    }
    removeQuietly(bucket, replaced == null ? List.of() : replaced);
  }

  /** Stores one CERTIFICATE beside the others and returns its id, for its specialty or request. */
  UUID addCertificate(UUID accountId, Upload upload) {
    DocumentStore.StoredFile file = checked(accountId, Kind.CERTIFICATE, null, upload);
    String bucket = settings.documents().bucket();
    List<String> stored = store(bucket, List.of(file), List.of(upload));
    try {
      transactions.executeWithoutResult(s -> store.insert(accountId, file));
    } catch (RuntimeException e) {
      removeQuietly(bucket, stored);
      throw e;
    }
    return file.id();
  }

  /** Removes one file and its bytes, as when what it came with could not be saved. */
  void discard(UUID accountId, UUID id) {
    removeQuietly(settings.documents().bucket(), store.remove(accountId, id));
  }

  /** Removes the licences of professions a draft no longer lists (D62). */
  void keepLicencesOf(UUID accountId, Collection<String> professions) {
    List<String> removed =
        transactions.execute(s -> store.removeLicencesExcept(accountId, professions));
    removeQuietly(settings.documents().bucket(), removed == null ? List.of() : removed);
  }

  private DocumentStore.StoredFile checked(
      UUID accountId, Kind kind, String profession, Upload upload) {
    if (upload.bytes().length == 0) {
      throw new SolaceException(CommonError.REQUEST_INVALID);
    }
    if (upload.bytes().length > settings.documents().maxFileSize().toBytes()) {
      throw new SolaceException(CommonError.TOO_LARGE);
    }
    String type = sniff(upload.bytes());
    if (type == null || (kind == Kind.PHOTO && type.equals(PDF))) {
      throw new SolaceException(SpecialistError.DOCUMENT_TYPE);
    }
    UUID id = UUID.randomUUID();
    return new DocumentStore.StoredFile(
        id,
        kind.name(),
        profession,
        "specialists/" + accountId + "/" + id,
        safeName(upload.fileName(), type),
        type,
        upload.bytes().length,
        clock.instant());
  }

  /** Puts the bytes first; on a failure, removes what was stored and throws. */
  private List<String> store(
      String bucket, List<DocumentStore.StoredFile> files, List<Upload> uploads) {
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
    return stored;
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
