# Pill

A read-only status label on a booking, application, payout, module or log entry.

**Consumer provides:** the status word (one or two words, sentence case) and its tone.

| Tone | Class | Colours | Use for |
| --- | --- | --- | --- |
| Brand | `sl-pill--brand` | `brand-tint` / `brand-deep` | Confirmed, approved, active, paid |
| Warning | `sl-pill--warning` | `warning-tint` / `warning-ink` | Pending, waiting, due soon, under review |
| Neutral | `sl-pill--neutral` | `surface-sunken` / `ink-neutral` | Draft, past, archived |
| Danger | `sl-pill--danger` | `danger-tint` / `danger` | Failed, rejected, refund requested, crisis |
| Solid | `sl-pill--solid` | `brand` / `on-brand` | Live now, current version |
| Pink | `sl-pill--pink` | `track-pink-tint` / `track-pink-ink` | Cycle tracking only |

The word carries the meaning; the colour only reinforces it. 26px high, `caption` size at weight 700, `radius-pill`.

Don't make a pill clickable. If it filters, use Chip.
