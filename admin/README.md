# Admin portal

Angular 22 (standalone components, zoneless, signals) for staff: Super admin, Operations, Support, Finance, Clinical reviewer, Crisis responder and Content editor.

## Run it

```sh
# from the repo root, once: installs, and generates design tokens and the sign-in theme assets
pnpm install

# local stack with Keycloak (infra/README.md), then from admin/
pnpm start        # http://localhost:4200, redirects to the staff sign-in
pnpm test         # Vitest
pnpm typecheck
pnpm build
```

Staff accounts are created only by a Super admin (D7). For a local one, create a user in the `solace-staff` realm in the Keycloak admin console (<http://localhost:8180>) with a temporary password and a role; the first sign-in asks for a new password, then an authenticator app.

## How it works

| Part | Where |
| --- | --- |
| Sign-in | Keycloak's pages in the `solace-staff` login theme (`infra/keycloak/themes`), built to the Sign-in and FirstSignIn screens. Authorization code with PKCE; the portal never sees a password |
| Idle sign-out | After `idleSignOutMinutes` (`[15]`, D7) without activity, through keycloak-angular's inactivity timeout |
| Permissions | `src/app/auth/permissions.ts` is the matrix from the Admin Portal Spec. The sidebar shows only the entries a person's roles allow; a direct link to anything else shows No access |
| Sidebar | `src/app/nav/nav.ts` (7 groups, as on Sidebar.dc.html) and `src/app/layout/sidebar.ts` |
| Strings | Transloco over the shared EN/FR catalogues in `packages/i18n` |
| Design values | `@solace/tokens/css`, generated from `design/design-system/tokens.json`; fonts self-hosted from `@fontsource` |
| Later phases | Tracking modules, Health check (Phase 2), AI controls (Phase 3) and Copilot (Phase 4) are off in `src/environments/environment.ts` |

Screens themselves arrive with their epics; until then each route shows a development stand-in.
