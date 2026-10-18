import { inject, Injectable } from '@angular/core';
import { TranslocoService } from '@jsverse/transloco';
import { createTrackingClient, type TrackingSchemas } from '@solace/api-clients/tracking';
import Keycloak from 'keycloak-js';
import type { Middleware } from 'openapi-fetch';

import { environment } from '../../environments/environment';

export type { TrackingSchemas };

/**
 * Tracking through the gateway, for the module builder (admin screens 21 to 25). Same session
 * handling as {@link CoreApi}; Tracking checks the permission matrix again on every call.
 */
@Injectable({ providedIn: 'root' })
export class TrackingApi {
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

  readonly client = createTrackingClient(`${environment.apiUrl}/tracking`, this.session);

  /** A card image's address; images are not personal data and need no token. */
  imageUrl(id: string): string {
    return `${environment.apiUrl}/tracking/v1/tracking/images/${id}`;
  }
}
