import createClient, { type Middleware } from 'openapi-fetch';

import type { components, paths } from '../dist/ai';

/** Request and response shapes of the AI service's edge API (contracts/openapi/ai.yaml). */
export type AiSchemas = components['schemas'];
export type AiPaths = paths;

/**
 * A typed client for the AI service through the gateway. {@code baseUrl} is the gateway's /api/ai;
 * the middleware adds the bearer token and the caller's language to every request.
 */
export function createAiClient(baseUrl: string, ...middleware: Middleware[]) {
  const client = createClient<paths>({ baseUrl, fetch: (request) => globalThis.fetch(request) });
  for (const m of middleware) client.use(m);
  return client;
}

export type AiClient = ReturnType<typeof createAiClient>;
