import createClient, { type Middleware } from 'openapi-fetch';

import type { components, paths } from '../dist/core';

/** Request and response shapes of Core's edge API (contracts/openapi/core.yaml). */
export type CoreSchemas = components['schemas'];
export type CorePaths = paths;

/**
 * A typed client for Core through the gateway. {@code baseUrl} is the gateway's /api/core; the
 * middleware adds the bearer token and the caller's language to every request.
 */
export function createCoreClient(baseUrl: string, ...middleware: Middleware[]) {
  // Look fetch up on every request, not once at import, so a fetch wrapped later (tracing, a test
  // stub) is the one used.
  const client = createClient<paths>({ baseUrl, fetch: (request) => globalThis.fetch(request) });
  for (const m of middleware) client.use(m);
  return client;
}

export type CoreClient = ReturnType<typeof createCoreClient>;
