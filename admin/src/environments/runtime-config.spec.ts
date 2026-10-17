import { afterEach, describe, expect, it } from 'vitest';

import { environment } from './environment';
import { applyRuntimeConfig } from './runtime-config';

const defaults = structuredClone(environment);

describe('applyRuntimeConfig', () => {
  afterEach(() => Object.assign(environment, structuredClone(defaults)));

  it('takes the values the container supplies', () => {
    applyRuntimeConfig({
      keycloak: { url: 'https://id.example.cm', realm: 'solace-staff', clientId: 'solace-admin' },
      apiUrl: 'https://api.example.cm/api',
      idleSignOutMinutes: '20',
      flags: { trackingModules: true },
    });
    expect(environment.keycloak.url).toBe('https://id.example.cm');
    expect(environment.apiUrl).toBe('https://api.example.cm/api');
    expect(environment.idleSignOutMinutes).toBe(20);
    expect(environment.flags.trackingModules).toBe(true);
    expect(environment.flags.copilot).toBe(false);
  });

  it('keeps the defaults for missing or malformed values', () => {
    applyRuntimeConfig({
      keycloak: { url: '  ' },
      idleSignOutMinutes: '0',
      flags: { aiControls: 'true' },
    });
    expect(environment.keycloak.url).toBe(defaults.keycloak.url);
    expect(environment.idleSignOutMinutes).toBe(defaults.idleSignOutMinutes);
    expect(environment.flags.aiControls).toBe(false);
  });
});
