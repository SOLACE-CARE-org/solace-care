import {
  type ApplicationConfig,
  provideBrowserGlobalErrorListeners,
  provideZonelessChangeDetection,
} from '@angular/core';
import { provideRouter, withComponentInputBinding } from '@angular/router';
import {
  AutoRefreshTokenService,
  provideKeycloak,
  UserActivityService,
  withAutoRefreshToken,
} from 'keycloak-angular';

import { environment } from '../environments/environment';
import { routes } from './app.routes';
import { provideAdminTransloco, startLanguage } from './i18n/transloco';

export const appConfig: ApplicationConfig = {
  providers: [
    provideBrowserGlobalErrorListeners(),
    provideZonelessChangeDetection(),
    provideRouter(routes, withComponentInputBinding()),
    provideAdminTransloco(),
    // Staff sign in on Keycloak's pages (password, then authenticator code; first sign-in sets a new
    // password and an authenticator app). Authorization code flow with PKCE; no password ever
    // reaches the portal.
    provideKeycloak({
      config: environment.keycloak,
      initOptions: {
        onLoad: 'login-required',
        pkceMethod: 'S256',
        checkLoginIframe: false,
        locale: startLanguage(),
      },
      features: [
        // Sign out after [15] idle minutes (D7). Token refresh stops when the person is idle, so
        // Keycloak's own idle timeout ends the session on the server too.
        withAutoRefreshToken({
          onInactivityTimeout: 'logout',
          sessionTimeout: environment.idleSignOutMinutes * 60_000,
        }),
      ],
      providers: [AutoRefreshTokenService, UserActivityService],
    }),
  ],
};
