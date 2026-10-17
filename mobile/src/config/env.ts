/**
 * Where the app talks to, and settings it shares with the backend. EXPO_PUBLIC_* values are baked
 * in at build time per environment (eas.json profiles); the defaults point at the local stack.
 * An Android emulator reaches the computer's localhost as 10.0.2.2.
 */
export const env = {
  /** The gateway's API root; Core is under /core. */
  apiUrl: process.env.EXPO_PUBLIC_API_URL ?? 'http://localhost:8080/api',
  /** Keycloak, for the members realm's code sign-in. */
  keycloakUrl: process.env.EXPO_PUBLIC_KEYCLOAK_URL ?? 'http://localhost:8180',
  membersRealm: 'solace-members',
  clientId: 'solace-mobile',
  /**
   * Versions of the Terms and privacy notice this build shows. Core refuses a sign-up on an older
   * version (account.outdated-terms), so an outdated app asks to be updated.
   */
  termsVersion: 'v1',
  privacyNoticeVersion: 'v1',
  /** Versions of the Specialist terms and Code of conduct this build shows (SpecSignup). */
  specialistTermsVersion: 'v1',
  codeOfConductVersion: 'v1',
  /**
   * Seconds before "Resend" is offered. Matches Keycloak's resendAfterSeconds (a setting, not in
   * the specs); Keycloak enforces it, this only shows the countdown.
   */
  codeResendSeconds: Number(process.env.EXPO_PUBLIC_CODE_RESEND_SECONDS ?? 60),
  /** Wrong tries before a new code is sent ([3], StateCodeWrong); Keycloak's maxAttempts. */
  codeMaxAttempts: Number(process.env.EXPO_PUBLIC_CODE_MAX_ATTEMPTS ?? 3),
  /** Minutes a code lives ([10], notification-copy.md); Keycloak's codeTtlSeconds. */
  codeLifetimeMinutes: Number(process.env.EXPO_PUBLIC_CODE_LIFETIME_MINUTES ?? 10),
} as const;
