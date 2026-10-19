package care.solace.ai.scribe;

import care.solace.ai.admin.Members;
import care.solace.ai.features.Features;
import care.solace.ai.gateway.Feature;
import care.solace.audit.AuditLog;
import care.solace.errors.CommonError;
import care.solace.errors.SolaceException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * The specialist's notes (contracts/openapi/ai.yaml, tag notes; D57). Every read is a clinical read
 * and is audited; only the note's specialist reaches it, anyone else gets 404.
 */
@RestController
class NoteController {

  record Edit(@NotNull String format, @NotNull @Size(max = 4) List<Notes.Section> sections) {}

  record Redraft(@NotNull String format) {}

  private final Members members;
  private final Notes notes;
  private final NoteDrafter drafter;
  private final Features features;
  private final AuditLog audit;

  NoteController(
      Members members, Notes notes, NoteDrafter drafter, Features features, AuditLog audit) {
    this.members = members;
    this.notes = notes;
    this.drafter = drafter;
    this.features = features;
    this.audit = audit;
  }

  @GetMapping("/v1/specialist/notes")
  List<Notes.Summary> list(
      @AuthenticationPrincipal Jwt token,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) @Size(max = 100) String q) {
    UUID specialist = members.specialist(token);
    if (status != null && !status.equals("DRAFT") && !status.equals("SIGNED")) {
      throw new SolaceException(CommonError.REQUEST_INVALID);
    }
    var list = notes.list(specialist, status, q);
    audit.read("notes.list", "session-note", specialist.toString(), null);
    return list;
  }

  @GetMapping("/v1/specialist/notes/{noteId}")
  Notes.Note get(@AuthenticationPrincipal Jwt token, @PathVariable UUID noteId) {
    UUID specialist = members.specialist(token);
    return read(noteId, specialist);
  }

  @PutMapping("/v1/specialist/notes/{noteId}")
  Notes.Note edit(
      @AuthenticationPrincipal Jwt token,
      @PathVariable UUID noteId,
      @Valid @RequestBody Edit edit) {
    UUID specialist = members.specialist(token);
    notes.edit(noteId, specialist, edit.format(), edit.sections());
    return read(noteId, specialist);
  }

  /** Drafts again from the transcript in the other format; the specialist's edits are replaced. */
  @PostMapping("/v1/specialist/notes/{noteId}/redraft")
  Notes.Note redraft(
      @AuthenticationPrincipal Jwt token,
      @PathVariable UUID noteId,
      @Valid @RequestBody Redraft body) {
    UUID specialist = members.specialist(token);
    String format = body.format();
    if (!format.equals("SOAP") && !format.equals("DAP")) {
      throw new SolaceException(CommonError.REQUEST_INVALID);
    }
    var draft = notes.draft(noteId, specialist);
    String transcript =
        notes.transcript(noteId).orElseThrow(() -> new SolaceException(ScribeError.NO_TRANSCRIPT));
    audit.read("note.transcript.read", "session-note", noteId.toString(), null);
    var live =
        features
            .live(Feature.SESSION_SCRIBE)
            .orElseThrow(() -> new SolaceException(CommonError.DEPENDENCY_UNAVAILABLE));
    Locale language = Locale.forLanguageTag(notes.transcriptLanguage(noteId).orElse("en"));
    var sections =
        drafter
            .draft(live, null, transcript, format, language)
            .orElseThrow(() -> new SolaceException(CommonError.DEPENDENCY_UNAVAILABLE));
    notes.replace(draft.id(), format, sections, live.version());
    return read(noteId, specialist);
  }

  @PostMapping("/v1/specialist/notes/{noteId}/signature")
  Notes.Note sign(@AuthenticationPrincipal Jwt token, @PathVariable UUID noteId) {
    UUID specialist = members.specialist(token);
    notes.sign(noteId, specialist);
    return read(noteId, specialist);
  }

  @DeleteMapping("/v1/specialist/notes/{noteId}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  void discard(@AuthenticationPrincipal Jwt token, @PathVariable UUID noteId) {
    UUID specialist = members.specialist(token);
    notes.discard(noteId, specialist);
  }

  private Notes.Note read(UUID noteId, UUID specialist) {
    UUID subject =
        notes
            .subjectOf(noteId, specialist)
            .orElseThrow(() -> new SolaceException(CommonError.NOT_FOUND));
    return audit.readThen(
        "note.read",
        "session-note",
        noteId.toString(),
        subject.toString(),
        () -> notes.get(noteId, specialist).orElseThrow());
  }
}
