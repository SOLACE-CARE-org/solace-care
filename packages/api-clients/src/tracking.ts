import createClient, { type Middleware } from 'openapi-fetch';

import type { components, paths } from '../dist/tracking';

/** Request and response shapes of Tracking's edge API (contracts/openapi/tracking.yaml). */
export type TrackingSchemas = components['schemas'];
export type TrackingPaths = paths;

/**
 * A typed client for Tracking through the gateway. {@code baseUrl} is the gateway's /api/tracking;
 * the middleware adds the bearer token and the caller's language to every request.
 */
export function createTrackingClient(baseUrl: string, ...middleware: Middleware[]) {
  const client = createClient<paths>({ baseUrl, fetch: (request) => globalThis.fetch(request) });
  for (const m of middleware) client.use(m);
  return client;
}

export type TrackingClient = ReturnType<typeof createTrackingClient>;
