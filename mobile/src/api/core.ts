import { type CoreSchemas, createCoreClient } from '@solace/api-clients/core';
import type { Middleware } from 'openapi-fetch';

import { accessToken } from '@/auth/auth';
import { env } from '@/config/env';
import i18n from '@/i18n';

export type { CoreSchemas };

/**
 * Adds the bearer token when there is a session, and the app language so problem titles, the
 * catalogue and anything else Core words come back in it.
 */
const session: Middleware = {
  async onRequest({ request }) {
    const token = await accessToken();
    if (token) request.headers.set('Authorization', `Bearer ${token}`);
    request.headers.set('Accept-Language', i18n.language || 'en');
    return request;
  },
};

/** Core through the gateway (contracts/openapi/core.yaml). */
export const core = createCoreClient(`${env.apiUrl}/core`, session);

/** A problem response from Core (RFC 9457) with its stable code. */
export class ApiProblem extends Error {
  constructor(
    readonly status: number,
    readonly code: string | undefined,
    /** Core's title, already in the app language (it reads Accept-Language). */
    readonly title: string | undefined,
  ) {
    super(code ?? `HTTP ${status}`);
  }
}

/** Unwraps an openapi-fetch result: the data, or an {@link ApiProblem}. */
export function orThrow<T>(result: {
  data?: T;
  error?: unknown;
  response: Response;
}): T {
  if (result.error !== undefined || !result.response.ok) {
    const problem = result.error as { code?: string; title?: string } | undefined;
    throw new ApiProblem(result.response.status, problem?.code, problem?.title);
  }
  return result.data as T;
}
