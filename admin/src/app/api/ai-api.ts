import { inject, Injectable } from '@angular/core';
import { TranslocoService } from '@jsverse/transloco';
import { createAiClient, type AiSchemas } from '@solace/api-clients/ai';
import Keycloak from 'keycloak-js';
import type { Middleware } from 'openapi-fetch';

import { environment } from '../../environments/environment';

export type { AiSchemas };

/**
 * The AI service through the gateway, for AI controls (admin screen 26; D56). Same session
 * handling as {@link CoreApi}; the AI service checks the permission matrix again on every call.
 */
@Injectable({ providedIn: 'root' })
export class AiApi {
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

  readonly client = createAiClient(`${environment.apiUrl}/ai`, this.session);
}
