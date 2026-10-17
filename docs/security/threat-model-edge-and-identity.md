# Threat model: edge and identity

**Scope:** the API gateway, Keycloak with its two realms (`solace-members`, `solace-staff`, D23), the one-time code authenticator and temporary-password expiry (`backend/keycloak/otp-authenticator`), the staff sign-in theme, and service-to-service tokens. Status as of Phase 0.

## What is protected

| Asset | Where |
| --- | --- |
| Member accounts: phone number, email, roles | Keycloak database (`solace-members`) |
| Staff accounts: name, email, password hash, TOTP secret, roles | Keycloak database (`solace-staff`) |
| Sign-in codes | Keycloak, single-use store with its own expiry (`CodeBook`) |
| Signing keys for every token on the platform | Keycloak database |
| Client secrets of Core, Tracking and AI | Vault (deployed), realm JSON (local only) |
| Sign-in events | Keycloak database, both realms |

## Trust boundaries and flows

1. **Internet → gateway** (`api.<domain>`): mobile app and admin portal, bearer token. The gateway validates the token against both realms, rate-limits per person (per address when signed out), and routes `/api/core|tracking|ai/**`.
2. **Internet → Keycloak** (`id.<domain>`): only `/realms/` and `/resources/` are exposed (`infra/k8s/base/ingress.yaml`). Members sign in through the direct-grant code flow from the app's own screens; staff through Keycloak's pages (authorization code with PKCE).
3. **Gateway → services**: plain HTTP inside the cluster; each service validates the token again.
4. **Service → Keycloak**: client-credentials tokens fetched inside the cluster.
5. **Core → Keycloak admin API**: staff account creation (Phase 1, D7).

## Threats

| ID | STRIDE | Threat | Control | Status |
| --- | --- | --- | --- | --- |
| EI-1 | S | Guessing a member's sign-in code | 6-digit code, 10-minute lifetime, discarded after `[5]` wrong entries, single use (`OtpSettings`, `CodeBook`); realm brute-force lockout after `[10]` failures | In place (`CodeBookTest`, `RealmsIntegrationTest.tooManyWrongCodesDiscardTheCode`) |
| EI-2 | I | Learning whether a phone number or email has an account | Unknown contacts get the same answer as known ones and no code is sent | In place (`RealmsIntegrationTest.unknownContactGetsTheSameAnswerAndNoCode`) |
| EI-3 | D / cost | SMS pumping: requesting codes for many numbers to run up SMS costs | Resend delay `[60]` s per account; gateway rate limit per address. No limit per destination prefix or per day yet | Partly in place; see Findings 1 |
| EI-4 | I | Codes or contacts written to logs | Codes never logged; contacts masked (`Contact`); log appender scrubs phone numbers, emails, codes and tokens (`libs/log-scrub`) | In place (`RealmsIntegrationTest.codesAndContactsNeverReachTheLogInFull`, `ScrubberTest`) |
| EI-5 | S | The development code sender (codes in the log) left on in a real environment | Refuses to run unless `SOLACE_DEV_CODE_SENDER=enabled`; set only in local compose and the dev overlay | In place; real SMS sender in Phase 1 (Core sends it) |
| EI-6 | S | Staff password guessing or reuse | 12+ characters, not the username or email, history `[5]`; lockout after `[5]` failures; TOTP required on every sign-in | In place (`RealmsIntegrationTest.staffRealmFollowsTheStaffPolicy`) |
| EI-7 | S | A temporary password intercepted or reused | Unique, handed over in person, single use, expires after 72 hours, forces a new password then TOTP (D7) | In place (`TemporaryPasswordPolicyTest`, `staffFirstSignInForcesANewPasswordThenAnAuthenticatorApp`) |
| EI-8 | E | Self-registration as staff or member through Keycloak | Registration off in both realms; staff created only by a Super admin through Core | In place (realm config); creation flow in Phase 1 |
| EI-9 | S | A stolen staff session used later | `[15]`-minute idle sign-out on the server (`ssoSessionIdleTimeout`) and in the portal; 10-hour maximum; sessions end when a role changes (Phase 1, Team and roles) | In place except role-change sign-out (Planned, admin portal v1) |
| EI-10 | I | Leaked-password sign-in for staff | No breached-password check | Open; Findings 2 |
| EI-11 | S | A token from one realm or for one service used at another | Each service accepts only the two realm issuers; gRPC calls require the callee's audience and a method scope (`GrpcMethodScopes`, `ServiceSecurityAutoConfiguration`) | In place (`RealmsIntegrationTest.trackingServiceGetsOnlyItsOwnScopes`, `aiServiceCanOnlyReadTrackingSummaries`, service tests) |
| EI-12 | S | Bypassing the gateway to reach a service directly | Services re-validate every token; network policies allow only the gateway to call services and only Core to call gRPC | In place (code); network policies written, not applied |
| EI-13 | T / I | Traffic between services read or changed inside the cluster | TLS ends at the ingress; inside, tokens but no encryption. mTLS needs a mesh or SPIFFE (System Design) | Open; Findings 3 |
| EI-14 | E | Keycloak admin console or admin API reached from the internet | Not routed by the ingress; reached only from inside the cluster | Written, not applied |
| EI-15 | S | Clickjacking or phishing look-alikes of the staff sign-in page | Keycloak sends frame-ancestors and X-Frame-Options; TOTP limits the value of a phished password | In place (Keycloak defaults); phishing-resistant keys not planned |
| EI-16 | D | Request floods against the API | Per-person token bucket in Redis (`[20]`/s, burst `[40]`); WAF/CDN in front (System Design) | Gateway limit in place (`GatewayTest`); WAF depends on hosting |
| EI-17 | D | Keycloak overwhelmed by sign-in traffic | Two replicas, brute-force detection; no rate limit in front of `/realms/` yet | Open; Findings 1 |
| EI-18 | R | A person denies signing in or an admin denies a change in Keycloak | Sign-in events in both realms (90 days, a setting); admin events in both realms | In place |
| EI-19 | I | Token contents expose personal data | Staff tokens carry name and email. Member tokens carry no phone number (checked on a real token) but do carry `preferred_username`, and `email` when the member has one. Services must not log tokens (scrubbed if they do) | In place; see Findings 4 |
| EI-20 | T | Supply chain: a compromised dependency, base image or CI action | Actions pinned by commit; Dependabot; Trivy on repository and images; dependency review on PRs; CodeQL | In place (`.github/`) once the repository is on GitHub |

## Findings

1. **SMS cost and abuse limits (EI-3, EI-17).** Add a per-destination and per-day cap on code requests, and a rate limit on `/realms/solace-members/protocol/openid-connect/token` at the ingress or WAF. The numbers are not in the specs: make them settings, and ask the owner for defaults before Phase 1 sign-in goes live.
2. **Breached-password check for staff (EI-10).** Keycloak has no built-in check. Options: a password-policy provider that checks a k-anonymity range API (sends 5 hash characters abroad, so it needs the lawyer's view), or a local breached-hash list. Decide in Phase 1, Team and roles.
3. **mTLS inside the cluster (EI-13).** The System Design asks for it. The choice (Linkerd, Istio, or SPIFFE certificates in Spring gRPC) depends on hosting. Until then, network policies and scoped tokens limit who can call what.
4. **Contact details in member tokens (EI-19).** When Core creates member accounts (Phase 1), the Keycloak username must be an opaque id, not the phone number, or `preferred_username` puts the number in every token. Also decide whether member tokens need the `email` scope at all; Core holds the contact details already.
5. **Realms in deployed environments.** The committed realm JSON holds development secrets. Deployed realms need secrets from Vault and real redirect URIs; choose the tool (realm-config CLI or the Keycloak Operator) with hosting.
