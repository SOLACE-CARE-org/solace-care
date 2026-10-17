import { computed, effect, inject } from '@angular/core';
import { patchState, signalStore, withComputed, withHooks, withMethods, withState } from '@ngrx/signals';
import Keycloak from 'keycloak-js';
import { KEYCLOAK_EVENT_SIGNAL, KeycloakEventType } from 'keycloak-angular';

import { type Access, type Area, accessTo, isStaffRole, type StaffRole } from './permissions';

type StaffState = {
  /** Display name from the token; shown in the sidebar footer only. */
  name: string;
  roles: StaffRole[];
};

/** Initials for the avatar disc: first letters of the first two words. */
export function initialsOf(name: string): string {
  return name
    .split(/\s+/)
    .filter(Boolean)
    .slice(0, 2)
    .map((word) => word[0]!.toUpperCase())
    .join('');
}

/**
 * The signed-in staff member, from the Keycloak token. Roles drive the sidebar and the route
 * guards; services check them again on every call.
 */
export const StaffSession = signalStore(
  { providedIn: 'root' },
  withState<StaffState>({ name: '', roles: [] }),
  withComputed(({ name, roles }) => ({
    initials: computed(() => initialsOf(name())),
    /** The first role, shown under the name in the sidebar. */
    primaryRole: computed(() => roles()[0] ?? null),
  })),
  withMethods((store) => ({
    accessTo(area: Area): Access | null {
      return accessTo(area, store.roles());
    },
    fromToken(token: Record<string, unknown> | undefined) {
      const realmAccess = token?.['realm_access'] as { roles?: string[] } | undefined;
      const name =
        (token?.['name'] as string | undefined) ??
        [token?.['given_name'], token?.['family_name']].filter(Boolean).join(' ');
      patchState(store, {
        name: name || String(token?.['preferred_username'] ?? ''),
        roles: (realmAccess?.roles ?? []).filter(isStaffRole),
      });
    },
  })),
  withHooks({
    onInit(store) {
      const keycloak = inject(Keycloak, { optional: true });
      const events = inject(KEYCLOAK_EVENT_SIGNAL, { optional: true });
      if (!keycloak || !events) return;
      // Keycloak has finished signing in before the app starts, so the roles are known before the
      // first route guard runs. The effect keeps them current after each token refresh.
      store.fromToken(keycloak.tokenParsed as Record<string, unknown> | undefined);
      effect(() => {
        const event = events();
        if (
          event.type === KeycloakEventType.Ready ||
          event.type === KeycloakEventType.AuthRefreshSuccess
        ) {
          store.fromToken(keycloak.tokenParsed as Record<string, unknown> | undefined);
        }
      });
    },
  }),
);
