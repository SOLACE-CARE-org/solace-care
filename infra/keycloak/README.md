# Keycloak

Keycloak is the only identity provider (D5). Two realms (D23), imported from `realms/` the first time Keycloak starts. The Java extension lives in [backend/keycloak/otp-authenticator](../../backend/keycloak/otp-authenticator).

## Running it locally

```sh
cd backend && ./gradlew :keycloak:otp-authenticator:jar && cd ..
docker compose -f infra/docker-compose.yml up -d
```

Realm files are only imported when a realm does not exist yet. After changing one, delete the realm in the admin console (<http://localhost:8180>, `admin` / `admin`) and restart Keycloak, or reset everything with `docker compose -f infra/docker-compose.yml down -v`.

## `solace-members`: seekers and specialists

| Item | Setting |
| --- | --- |
| Roles | `SEEKER`, `SPECIALIST` (the role picks the app shell) |
| Sign-in | One-time code by SMS or email (D6), through the `solace members code sign-in` direct grant flow |
| Accounts | Created by Core through the `core-admin` client. No self-registration in Keycloak |
| User attributes | `phone_number` in E.164 form, admin-editable only |
| Clients | `solace-mobile` (public, direct grant only), `core-admin` (service account: manage users) |

**How the app signs in.** Two calls to `POST /realms/solace-members/protocol/openid-connect/token` with `grant_type=password` and `client_id=solace-mobile`:

1. `username=<phone or email>` (form-encoded, so `+` is sent as `%2B`): answers `401 {"error":"code_required"}` whether or not the contact has an account, and sends a code if it does.
2. `username=<same>&code=<code>`: tokens on success, `401 {"error":"invalid_grant"}` otherwise.

**Codes locally.** The `logging` sender writes each code to the Keycloak log with the contact masked: `docker compose -f infra/docker-compose.yml logs keycloak | grep "sign-in code"`. It refuses to run unless `SOLACE_DEV_CODE_SENDER=enabled`, which only docker-compose and the tests set. The sender that hands codes to Core (Core sends the SMS) comes with the identity epic in Phase 1.

## `solace-staff`: admin portal and services

| Item | Setting |
| --- | --- |
| Roles | The seven from the Admin Portal Spec: `SUPER_ADMIN`, `OPERATIONS`, `SUPPORT`, `FINANCE`, `CLINICAL_REVIEWER`, `CRISIS_RESPONDER`, `CONTENT_EDITOR` |
| Sign-in | `solace staff browser` flow: email and password, then the temporary password expiry check, then the authenticator code |
| First sign-in | Update Password, then Configure OTP (the order on the FirstSignIn screen) |
| Temporary password | Set by a Super admin through Core with `temporary: true`. Works once (Keycloak replaces it at first sign-in) and is refused after 72 hours by `solace-temporary-password-expiry` |
| Password policy | At least 12 characters, not the username or email, not one of the last `[5]` |
| Idle sign-out | `[15]` minutes (`SOLACE_STAFF_IDLE_TIMEOUT_SECONDS`, default 900) |
| Lockout | Temporary lockout after `[5]` failures, with growing waits up to 15 minutes |
| Events | Sign-in and credential events plus admin events kept for 90 days (sign-in audit) |
| Clients | `solace-admin` (public, PKCE), `core-admin` (service account: manage users and sessions) |

**Service-to-service clients** (client credentials, D2). Each token carries only its own scopes, and an audience naming the service it may call:

| Client | Scopes | Audience |
| --- | --- | --- |
| `core-service` | `ai:moderation.invoke`, `tracking:ping`, `ai:ping` | `ai`, `tracking` |
| `tracking-service` | `core:booking-access.read`, `core:taxonomy.read`, `core:profile-age.read` | `core` |
| `ai-service` | `tracking:summary.read` | `tracking` |

## Settings

Values in brackets are placeholders from the specs or not in the specs yet. Each one can be overridden with an environment variable on the Keycloak container, read when a realm is imported:

| Variable | Default | Source |
| --- | --- | --- |
| `SOLACE_OTP_CODE_TTL_SECONDS` | 600 | `[10]` minutes, notification-copy.md |
| `SOLACE_OTP_CODE_LENGTH` | 6 | not in the specs |
| `SOLACE_OTP_MAX_ATTEMPTS` | 5 | not in the specs |
| `SOLACE_OTP_RESEND_AFTER_SECONDS` | 60 | not in the specs |
| `SOLACE_OTP_SENDER` | `logging` | Phase 0 only |
| `SOLACE_MEMBERS_LOCKOUT_FAILURES` | 10 | not in the specs |
| `SOLACE_STAFF_IDLE_TIMEOUT_SECONDS` | 900 | `[15]` minutes, D7 |
| `SOLACE_STAFF_PASSWORD_HISTORY` | 5 | FirstSignIn says "not one used before", count not set |
| `SOLACE_STAFF_LOCKOUT_FAILURES` | 5 | not in the specs |
| `SOLACE_ADMIN_URL` | `http://localhost:4200` | admin portal address |
| `SOLACE_*_SECRET` | `*-dev-secret` | local only; real secrets come from Vault |

## Not done here

- **Leaked-password check** (FirstSignIn: "Checked against known leaked passwords"): Keycloak has no built-in check. Needs a password blacklist file or a small policy extension, with the Team and roles work in Phase 1.
- **Ending sessions when a role changes:** Core does this through `core-admin` when it changes a role (Phase 1).
- **Login screens in the Solace design:** the admin Sign-in and FirstSignIn screens become a Keycloak theme in step 10.
- **"Forgot your password?"** is switched on to match the Sign-in screen, but needs an SMTP server before it can send anything.
