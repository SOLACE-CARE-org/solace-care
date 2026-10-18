/**
 * Messaging: which message each event sends to whom (notification-copy.md; D38), reminders, quiet
 * hours, discreet mode, the in-app inbox and push devices. Delivery itself is the notifications
 * module's, which depends on nothing else, so sign-in codes can use it too.
 */
@ApplicationModule(displayName = "Messaging")
package care.solace.core.messaging;

import org.springframework.modulith.ApplicationModule;
