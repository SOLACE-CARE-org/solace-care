import { inject, Injectable } from '@angular/core';
import { TranslocoService } from '@jsverse/transloco';
import { type CoreSchemas, createCoreClient } from '@solace/api-clients/core';
import Keycloak from 'keycloak-js';
import type { Middleware } from 'openapi-fetch';

import { environment } from '../../environments/environment';

export type { CoreSchemas };

/** A problem answer from Core (RFC 9457): its stable code and its title in the portal language. */
export class ApiProblem extends Error {
  constructor(
    readonly status: number,
    readonly code: string | undefined,
    readonly title: string | undefined,
  ) {
    super(code ?? `HTTP ${status}`);
  }
}

/**
 * Core through the gateway, with the staff member's Keycloak token (refreshed when close to
 * expiry) and the portal language, so problem titles come back in it. Core checks the permission
 * matrix again on every call.
 */
@Injectable({ providedIn: 'root' })
export class CoreApi {
  private readonly keycloak = inject(Keycloak, { optional: true });
  private readonly transloco = inject(TranslocoService);

  private readonly session: Middleware = {
    onRequest: async ({ request }) => {
      if (this.keycloak?.authenticated) {
        await this.keycloak.updateToken(30).catch(() => this.keycloak?.login());
        request.headers.set('Authorization', `Bearer ${this.keycloak.token}`);
      }
      request.headers.set('Accept-Language', this.transloco.getActiveLang() || 'en');
      return request;
    },
  };

  readonly client = createCoreClient(`${environment.apiUrl}/core`, this.session);

  /** A credential document as a blob, for opening in a new tab. Core audits the opening. */
  async document(applicationId: string, fileId: string): Promise<Blob> {
    const headers: Record<string, string> = {};
    if (this.keycloak?.authenticated) {
      await this.keycloak.updateToken(30);
      headers['Authorization'] = `Bearer ${this.keycloak.token}`;
    }
    const response = await fetch(
      `${environment.apiUrl}/core/admin/v1/specialist-applications/${applicationId}/documents/${fileId}`,
      { headers, cache: 'no-store' },
    );
    if (!response.ok) throw new ApiProblem(response.status, undefined, undefined);
    return response.blob();
  }
}

/** Unwraps an openapi-fetch result: the data, or an {@link ApiProblem}. */
export function orThrow<T>(result: { data?: T; error?: unknown; response: Response }): T {
  if (result.error !== undefined || !result.response.ok) {
    const problem = result.error as { code?: string; title?: string } | undefined;
    throw new ApiProblem(result.response.status, problem?.code, problem?.title);
  }
  return result.data as T;
}
