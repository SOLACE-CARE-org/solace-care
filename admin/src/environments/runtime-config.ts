import { environment } from './environment';

/**
 * Settings the container supplies at start-up, so one image moves unchanged from dev to staging to
 * production. The admin image's nginx serves /config.json from its environment variables; under
 * `ng serve` there is no such file and the values in environment.ts stay.
 */
export interface RuntimeConfig {
  keycloak?: { url?: unknown; realm?: unknown; clientId?: unknown };
  apiUrl?: unknown;
  idleSignOutMinutes?: unknown;
  flags?: Partial<Record<keyof typeof environment.flags, unknown>>;
}

const text = (value: unknown): value is string => typeof value === 'string' && value.trim() !== '';

/** Copies the well-formed values onto `environment`; anything missing or malformed keeps its default. */
export function applyRuntimeConfig(config: RuntimeConfig): void {
  const { keycloak, flags } = config;
  if (text(keycloak?.url)) environment.keycloak.url = keycloak.url;
  if (text(keycloak?.realm)) environment.keycloak.realm = keycloak.realm;
  if (text(keycloak?.clientId)) environment.keycloak.clientId = keycloak.clientId;
  if (text(config.apiUrl)) environment.apiUrl = config.apiUrl;
  const idle = Number(config.idleSignOutMinutes);
  if (config.idleSignOutMinutes !== undefined && Number.isInteger(idle) && idle > 0) {
    environment.idleSignOutMinutes = idle;
  }
  for (const key of Object.keys(environment.flags) as (keyof typeof environment.flags)[]) {
    const value = flags?.[key];
    if (typeof value === 'boolean') environment.flags[key] = value;
  }
}

export async function loadRuntimeConfig(): Promise<void> {
  try {
    const response = await fetch('config.json', { cache: 'no-store' });
    if (response.ok) applyRuntimeConfig((await response.json()) as RuntimeConfig);
  } catch {
    // No config.json (local development): keep environment.ts.
  }
}
