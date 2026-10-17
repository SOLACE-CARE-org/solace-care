/**
 * Admin portal settings. Local values point at infra/docker-compose.yml; each environment
 * replaces this file at build time.
 */
export const environment = {
  keycloak: {
    url: 'http://localhost:8180',
    realm: 'solace-staff',
    clientId: 'solace-admin',
  },
  /** API base: the gateway. */
  apiUrl: 'http://localhost:8080/api',
  /** Idle sign-out, [15] minutes in D7: a setting, not a constant. */
  idleSignOutMinutes: 15,
  /** Later-phase areas stay out of the sidebar until their phase ships (CLAUDE.md, Feature flags). */
  flags: {
    trackingModules: false, // Phase 2
    healthCheck: false, // Phase 2
    aiControls: false, // Phase 3
    copilot: false, // Phase 4
  },
};
