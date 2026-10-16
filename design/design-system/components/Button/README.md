# Button

One primary button per screen, for the thing the screen is for. Labels are verbs in sentence case and say exactly what happens ("Book session", "Approve", not "Submit" or "OK").

**Consumer provides:** the label, an optional leading icon (20px, stroke icon), and the size: mobile (default) or admin (`sl-btn--admin`).

| Variant | Class | Mobile | Admin | Use for |
| --- | --- | --- | --- | --- |
| Primary | `sl-btn--primary` | 54px high (56px on onboarding), `radius-16`, `button-lg` | 42px, `radius-12`, `label` | The one main action |
| Secondary | `sl-btn--secondary` | 50px, `radius-14`, `body-lg`, `line-control` border | 42px, `radius-12` | Alternatives beside a primary |
| Quiet | `sl-btn--quiet` | 44px tap area, `brand` text | same | "Not now", "Skip", links that act |
| Danger | `sl-btn--danger` | 54px, `danger` fill, weight 700 | 42px | Confirming a destructive step, crisis call |
| Danger outline | `sl-btn--danger-outline` | 50px, `danger-strong` text and border | — | Opening a destructive flow (cancel booking, delete account) |
| Danger quiet | `sl-btn--danger-quiet` | — | 42px, `danger-line` border | Reject, suspend: the first click, before a confirm |
| On brand | `sl-btn--on-brand` | 44px, `brand-line-on-dark` border | — | Secondary action on a `brand` fill (welcome, crisis header) |
| Icon | `sl-btn--icon` | 44×44, no fill | same | Back, close, more |

Do
- Put the primary button at the bottom of mobile screens, full width (`sl-btn--block`), above the safe-area inset.
- Put price or time in the label when it is what the person is agreeing to ("Book session · 15 000 FCFA").
- Show a pressed state on mobile with `brand-deep`.

Don't
- Use two primary buttons side by side. Make the second one secondary.
- Use danger red for anything that is not destructive or crisis-related.
- Shorten French labels with abbreviations; let the button wrap the row instead (French runs up to a third longer).
