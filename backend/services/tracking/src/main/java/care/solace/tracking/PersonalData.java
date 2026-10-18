package care.solace.tracking;

import java.util.UUID;

/** Anything in Tracking that holds a person's data deletes it on erasure (D40, D49). */
public interface PersonalData {

  void erase(UUID userId);
}
