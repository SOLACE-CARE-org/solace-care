# Threat model: clients

**Scope:** the mobile app (Expo, iOS and Android; seekers, guests and specialists in one app) and the admin portal (Angular, staff only). Status as of Phase 0: both shells exist without product features.

## What is protected

| Asset | Where |
| --- | --- |
| Tokens (member, guest, staff) | Device secure storage (mobile, Phase 1); memory only (admin portal, keycloak-js) |
| Offline tracking entries and notes waiting to sync | op-sqlite on the device (Phase 2) |
| Cached bookings, profiles, messages | TanStack Query cache in memory |
| App language and role | AsyncStorage (`mobile/src/state/session.ts`): not personal |

## Threats

| ID | STRIDE | Threat | Control | Status |
| --- | --- | --- | --- | --- |
| CL-1 | I | Someone with the phone opens the app and sees health data | Optional PIN or biometric app lock; neutral notifications; no health data in push payloads | Planned (Phase 1 app lock, Phase 2 tracking) |
| CL-2 | I | Offline entries and notes read from a stolen or rooted device | Encrypted local storage (System Design): op-sqlite with SQLCipher, a random 256-bit key in the Keychain or Keystore (this device only, when unlocked); the module catalogue cache lives there too; both are cleared on sign-out | In place (D53; `mobile/src/tracking/store.ts`) |
| CL-3 | I | Tokens stolen from device storage | Tokens in the platform's secure storage (Keychain, Keystore), never AsyncStorage; the session store persists only language and role today | Planned (Phase 1 sign-in) |
| CL-4 | T / I | Traffic intercepted on hostile networks | TLS; certificate pinning (System Design, OWASP MASVS) | Planned; see Findings 1 |
| CL-5 | T | App run on a rooted or jailbroken device | Warning shown (System Design) | Planned |
| CL-6 | I | A notification on the lock screen reveals care | Neutral copy; discreet mode | Planned (Phase 1) |
| CL-7 | S | Another app claims the `solacecare://` scheme and catches a join link | Use verified App Links (Android) and Universal Links (iOS) for links in SMS and email | Open; Findings 2 |
| CL-8 | I | Screenshots or the app switcher show sensitive screens | Not specified | Open; Findings 3 |
| CL-9 | D | Crisis help unavailable offline | Crisis button one tap away on every screen, with local numbers bundled for offline use | Button in place (`mobile/src/ui/CrisisButton.tsx`); numbers Planned (Phase 1) |
| CL-10 | I / T | Script injection in the admin portal steals a staff session | Angular's escaping; Content-Security-Policy with no inline script; tokens kept in memory | In place (`admin/nginx/security-headers.conf.template`, checked in a browser with no CSP errors) |
| CL-11 | T | The admin portal framed by another site (clickjacking) | `frame-ancestors 'none'` | In place |
| CL-12 | E | A staff member opens a screen their role does not allow | The portal hides it and shows No access; the server refuses it (Core CO-3) | Portal in place (`permissions.spec.ts`); server Planned |
| CL-13 | S | An unattended admin session used by someone else | `[15]`-minute idle sign-out in the portal and in Keycloak | In place |
| CL-14 | T | A malicious dependency in the app bundle | Lockfile, Dependabot, dependency review, Trivy | In place once on GitHub |

## Findings

1. **Certificate pinning (CL-4).** Pinning on mobile breaks the app when a certificate rotates. Pin the public key of an intermediate or keep a backup pin, and decide the CDN/WAF (hosting) first, because it terminates TLS.
2. **Verified links (CL-7).** Session join links and booking links in SMS and email should be `https://` links verified for the app (App Links, Universal Links), falling back to a web page, not the custom scheme. Needs the domain.
3. **Screen capture (CL-8).** Not in the specs. Proposal: block screenshots and blur the app switcher on screens showing tracking entries, notes, health check answers and session notes. It affects how the app works for people, so it needs the owner's decision.
