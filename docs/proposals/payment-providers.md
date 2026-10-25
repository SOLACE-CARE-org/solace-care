# Payment providers as data

**Status:** accepted as D61 on 25 October 2026, and built in Core. It replaces "`solace.payments.provider` picks one" in D35. Where this document and D61 differ, D61 wins. The admin screens still need a design.

**Goal:** Solace can have several payment providers configured at once, such as two mobile-money aggregators, a test account next to a live one, and later a card PSP. Staff can see them, route traffic between them and retire one without a deploy, and no money in flight is lost or misrouted.

## How it works today

| Part | Today | Where |
| --- | --- | --- |
| Choice of provider | One environment variable, `SOLACE_PAYMENTS_PROVIDER` (`notchpay` or `sandbox`), read when Core starts. Anything else stops Core from starting. | `PaymentsConfiguration.paymentProvider`, `application.yml` (`solace.payments.provider`) |
| Credentials | Notch Pay public key, private key and webhook secret, as environment variables that the External Secrets Operator fills from Vault | `PaymentSettings.NotchPay`, `infra/k8s/base/secrets.yaml` |
| The adapter | One `PaymentProvider` bean, injected into `Payments`, `PayoutRuns`, `Reconciliation` and `PaymentController` | `payments/` package |
| Webhooks | `POST /v1/webhooks/payments/{provider}` accepts only the one configured provider name. The signature is read from Notch Pay's own header, `X-Notch-Signature`. | `PaymentController.webhook` |
| What the tables record | `payment.provider` and `webhook_event.provider` already hold a provider name. `refund` and `payout_item` do not; they assume the one provider. | `V1_13__payments.sql` |
| Ledger | One `PROVIDER_CLEARING` account for all money held at the aggregator | `ledger_line.account` |
| Reconciliation | One daily run, against the one provider | `Reconciliation`, `reconciliation_run (day UNIQUE)` |

There is **no provider table**. Some of the groundwork exists: the port is provider-neutral, payments record their provider, and webhook paths and deduplication are already keyed by provider.

**A risk in today's design:** if the variable is switched from one provider to another, pending payments and sent transfers are still checked at whichever provider is configured now. `Payments.check` calls `provider.chargeStatus(p.providerRef())` without looking at `p.provider()`. A switch would strand in-flight money, or worse, query the wrong provider for it. The design below removes this.

## The design in one paragraph

Each adapter **type** (Notch Pay, the sandbox, later a card PSP) stays Java code, because each provider has its own API. A **provider** is now a row: one account with one adapter type, its mode (test or live), its status and a reference to its keys in Vault. **Routes** say which provider handles collections, refunds and payouts for each payment method, in priority order. Every payment, refund and payout item records the provider that handled it, and every later step for that item (status checks, webhooks, reconciliation) goes to that same provider, whatever the routes say by then.

One limit to state plainly: a provider whose API has no adapter yet still needs code. What becomes possible without a deploy is adding a second account on an existing adapter, switching routes, failing over, and draining or retiring a provider.

## Tables (Core)

```sql
-- One account with an aggregator or card PSP. Keys never live here: secret_ref names them in Vault.
CREATE TABLE payment_provider (
    code            TEXT PRIMARY KEY CHECK (code ~ '^[a-z][a-z0-9-]{1,30}$'), -- in webhook paths and payment.provider
    adapter         TEXT        NOT NULL,      -- which code adapter: NOTCHPAY, SANDBOX; later a card PSP
    display_name    TEXT        NOT NULL,      -- staff only; seekers never see a provider name
    mode            TEXT        NOT NULL CHECK (mode IN ('TEST', 'LIVE')),
    status          TEXT        NOT NULL CHECK (status IN ('DRAFT', 'ACTIVE', 'DRAINING', 'DISABLED')),
    base_url        TEXT,                      -- null: the adapter's default
    secret_ref      TEXT        NOT NULL,      -- Vault key holding this account's keys and webhook secret
    version         INTEGER     NOT NULL CHECK (version >= 1),
    updated_by      TEXT        NOT NULL,
    updated_by_name TEXT        NOT NULL,
    updated_at      TIMESTAMPTZ NOT NULL
);

-- Every version kept with its reason, like platform_setting_version (D42).
CREATE TABLE payment_provider_version (
    code            TEXT        NOT NULL REFERENCES payment_provider (code),
    version         INTEGER     NOT NULL,
    snapshot        JSONB       NOT NULL,      -- the row as it was, without secrets (there are none to leak)
    reason          TEXT        NOT NULL CHECK (length(reason) BETWEEN 1 AND 500),
    changed_by      TEXT        NOT NULL,
    changed_by_name TEXT        NOT NULL,
    changed_at      TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (code, version)
);

-- Which provider handles what, in order. Priority 1 first; the next one is the fallback.
CREATE TABLE payment_route (
    purpose        TEXT    NOT NULL CHECK (purpose IN ('COLLECT', 'REFUND', 'PAYOUT')),
    method         TEXT    NOT NULL CHECK (method IN ('MTN_MOMO', 'ORANGE_MONEY', 'CARD')),
    priority       INTEGER NOT NULL CHECK (priority >= 1),
    provider_code  TEXT    NOT NULL REFERENCES payment_provider (code),
    min_amount     BIGINT,                     -- whole FCFA; null: no limit
    max_amount     BIGINT,
    PRIMARY KEY (purpose, method, priority),
    UNIQUE (purpose, method, provider_code)
);
-- Route changes are versioned as a whole set (payment_route_version), with a reason.
```

**Changes to existing tables**

| Table | Change | Why |
| --- | --- | --- |
| `payment` | `provider` gets a foreign key to `payment_provider (code)` | A provider with payments can never be deleted, only disabled |
| `refund` | Add `provider` | The refund's transfer may run at a different provider than the one that collected it |
| `payout_item` | Add `provider` | Status checks and webhooks for a payout go to the provider that sent it |
| `ledger_line` | Add `provider`, required on `PROVIDER_CLEARING` lines, through a `CHECK ... NOT VALID` constraint | Each provider holds its own float, so the clearing balance has to be per provider. `NOT VALID` enforces the rule on new lines without rewriting the append-only ledger; older lines all belong to the provider in use before the migration |
| `refund` | Also add `method` and `phone`, starting as the payment's | The refund carries the method the seeker is repaid in |
| `reconciliation_mismatch` | Add `provider` | There is still one run a day, but each item is checked at the provider that handled it, and a mismatch names that provider |

**Migration:** insert one `payment_provider` row for the provider set today (`notchpay` or `sandbox`, read from `SOLACE_PAYMENTS_PROVIDER`), with routes for both mobile-money methods and all three purposes. Backfill `refund.provider` and `payout_item.provider` with that code. After the migration, Core behaves exactly as it does today.

## Provider status

| Status | New charges and transfers | Status checks, webhooks, reconciliation | Allowed when |
| --- | --- | --- | --- |
| DRAFT | No | No | Always. Used while keys are added and the connection is tested |
| ACTIVE | Yes, where a route names it | Yes | The connection test passed; for LIVE, the activation checks below are done |
| DRAINING | No; routes skip it | Yes, until nothing is in flight | Always. This is the way to retire a provider |
| DISABLED | No | No | Nothing is pending or sent at it. Core refuses the change otherwise |

## Routing rules

- **Collect.** The seeker chooses the method (MTN MoMo or Orange Money), as now. Core takes the route's providers for that method in priority order and skips any that is not ACTIVE, whose circuit breaker is open, or whose amount limits do not fit. Seekers never see which provider handled their payment.
- **Fallback is automatic only when nothing was asked.** If the call to provider 1 fails **before it accepted the request** (connection refused, circuit open), Core tries provider 2 with a new reference. If provider 1 **timed out** after the request may have arrived, Core does not fall back: the payment stays PENDING and is checked at provider 1. Otherwise the seeker could be asked to approve two payments.
- **Refund.** The refund goes back through the provider that collected the payment while that provider is ACTIVE. Otherwise, whether it is draining or disabled, it goes through the first ACTIVE provider on the REFUND route for the method the seeker is repaid in, which is always the wallet that paid (D61).
- **Payout.** Each payout item takes the PAYOUT route for its method when the run is sent, and records it. A transfer is **never** retried at another provider automatically. An ambiguous transfer stays SENT and is checked, and Finance resolves it with the provider. This is the same rule as above: never risk sending money twice.
- **Every later step goes to the provider on the record.** Polling, webhooks and reconciliation look up the adapter by the row's `provider` value, never by the current route. This fixes the risk described above.

## Code changes (Core, `payments` package)

- `PaymentProvider` (the port) gets two changes: `verify` takes **all request headers** instead of one signature string, so each adapter reads its own header; a new `test()` makes a call that moves no money; and a failure says whether the request certainly never reached the provider (`notSent`), which is what allows a fallback.
- A new `PaymentProviders` component replaces the single bean. It reads the `payment_provider` rows, builds one client per ACTIVE or DRAINING row through a factory for each adapter type, and resolves clients by code and by route. It rebuilds within a minute of a change, on the same `[30]`-second refresh as settings (`solace.admin.settings-refresh`).
- `Payments`, `PayoutRuns`, `Reconciliation` and `PaymentController` look up the provider through `PaymentProviders`. They take the code from the item's own record for follow-up steps, and from the routes for new items.
- Each provider gets its own Resilience4j circuit breaker, named after its code, so one failing provider does not trip the others.
- `PaymentSettings.NotchPay` keeps only the default `baseUrl`. Keys come from the provider's `secret_ref`.
- The overview's mobile-money health ("slow" after `[5]` minutes) is shown per provider.

## Secrets

Keys stay in Vault and are never stored in the database or shown in the portal. `secret_ref` names a Vault key, for example `core-payments/notchpay-live`, that holds `public-key`, `private-key` and `webhook-secret`.

Today every Vault key becomes an environment variable when the pod starts, so a new key would need a restart. For provider keys, the proposal is a separate `ExternalSecret` (`core-payment-providers`) **mounted as files** with a short refresh, `[1]` minute. Kubernetes updates mounted secret files without a restart. Core reads the files when it builds a provider's client. The portal shows only "keys found" or "keys missing" for each provider.

Adding the keys to Vault stays an operations step done by an infrastructure engineer. Choosing, activating and routing providers moves to the portal.

## Admin portal

There is no designed screen for this yet. It would sit under **Payments and payouts**, where Super admin and Finance have full access (admin spec, permission matrix). It needs a design before it is built, following the design system (DataTable, Pill, Notice, Switch). Proposed content:

- **Providers list:** name, adapter, TEST or LIVE, status, keys found or missing, circuit state, last webhook received, today's volume and failures.
- **Provider detail:** edit display name, mode, base URL and keys reference; **Test connection**, which makes a call that moves no money (Notch Pay: list one payment); change status. Every change asks for a reason.
- **Routes:** for each purpose and method, an ordered list with optional amount limits. A change is saved as a new version with a reason.

**Controls (D61):** only a Super admin adds, changes, activates, drains or disables a provider, or changes routes. Each change needs a reason, and there is no second approver. Staff with access to payments can see providers and routes. Every change is written to the audit log.

## Before a LIVE provider is activated

Core cannot check these itself. The activation dialog asks the person to confirm each one, and records who confirmed:

- A records-of-processing entry for the provider, which receives payers' phone numbers (`docs/security/records-of-processing.md`)
- A threat-model entry for its webhooks and API (`docs/security/threat-model-core.md`)
- A contract with the provider, and its webhook URL registered at the provider: `https://<api host>/v1/webhooks/payments/<code>`
- A test payment and a test transfer made in TEST mode with the same adapter

## Contracts

- **OpenAPI (`contracts/openapi/core.yaml`):** admin endpoints to list, create and update providers and routes, run a connection test, and read the version history. The webhook path is unchanged.
- **Events:** none needed. Only Core uses providers. Existing payment events need no provider field; if analytics wants one, it is an additive v2 field.
- **Proto:** none.

## Feature flag

`paymentRouting`, off by default. While it is off, Core uses the single ACTIVE provider for everything, as today, and the routes screen is read-only. Turning it on enables priority routing and fallback. Cards keep their own `cardPayments` flag; a card PSP is one more adapter type plus a `CARD` route.

## Tests

- **Unit:** route selection (status, circuit, amount limits, order); fallback only before the request was accepted, never for transfers; refunds default to the collecting provider.
- **Testcontainers (PostgreSQL):**
  - The migration backfill.
  - Two sandbox providers running at once: a payment collected at A and refunded at B. The ledger balances for each provider, and reconciliation runs for each provider.
  - A provider is drained while payments are pending: they complete, and it can then be disabled.
  - Disabling a provider with items in flight is refused.
  - A webhook to an unknown or DISABLED code is rejected.

## Open questions

1. **Second aggregator:** which one? The risks table in the implementation plan names a second aggregator as the fallback; none has been chosen.
2. **Float management:** moving money between providers' floats, or to Solace's bank, happens outside the platform today. Should Finance be able to record such transfers in the ledger?

