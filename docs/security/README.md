# Security and privacy documents

The Implementation Plan (Phase 0, Security) asks for **a threat model per service and a records-of-processing register, both reviewed before Phase 1 code touches personal data.** These are those documents. They are drafts until the review table below is filled in.

| Document | Covers |
| --- | --- |
| [Threat model: edge and identity](threat-model-edge-and-identity.md) | Gateway, Keycloak (both realms), the sign-in code authenticator, service-to-service tokens |
| [Threat model: Core](threat-model-core.md) | Identity data, marketplace, money, health check, crisis queue, admin actions, audit |
| [Threat model: Tracking](threat-model-tracking.md) | Tracking entries and notes, specialist access, rules and recommendations |
| [Threat model: AI](threat-model-ai.md) | Safety monitor first; later AI features behind flags |
| [Threat model: clients](threat-model-clients.md) | Mobile app (seekers, guests, specialists) and admin portal |
| [Records of processing](records-of-processing.md) | Every processing activity: purpose, data, people, recipients, transfers, retention |

## How to read the threat models

Each one lists what the service holds, where its trust boundaries are, and the threats against it by STRIDE category (Spoofing, Tampering, Repudiation, Information disclosure, Denial of service, Elevation of privilege). Every threat names its control and that control's status:

| Status | Meaning |
| --- | --- |
| **In place** | Built in Phase 0 and covered by a test; the file is named |
| **Planned** | Required by a spec; built in the epic named |
| **Open** | No decision or no control yet; listed under Findings with a proposal |

Threat IDs (`EI-3`, `CO-7`, ...) are stable: refer to them from pull requests and tickets.

## Keeping them current

- A pull request that adds a data flow, a store of personal data, a new kind of caller or a new external provider updates the threat model and, if personal data is involved, the register in the same PR.
- Each phase gate re-reviews both, and the pre-launch penetration test (Phase 1 gate) is scoped from them.
- The register is the basis of the authorisation filing with the Data Protection Authority and of the data-protection impact assessment for health data (Implementation Plan, launch checklist). Legal bases and transfer questions in it need the data-protection lawyer's confirmation.

## Review

| Document | Reviewer | Role | Date | Outcome |
| --- | --- | --- | --- | --- |
| Threat models | | Engineering lead | | |
| Threat models | | Security reviewer | | |
| Records of processing | | Data-protection lawyer | | |
| Records of processing | | Owner | | |
