# Notice

A one-to-two sentence message inside a screen: a warning, an error, or the connection state. Not a toast; it stays until the condition clears.

**Consumer provides:** a bold lead that names the situation, one sentence saying what to do, a 20px stroke icon, and `role="status"`.

| Tone | Class | Colours | Use for |
| --- | --- | --- | --- |
| Warning | `sl-notice--warning` | `warning-tint`, 1px `accent` border, `warning-ink-deep` text | Pending payment, data partly loaded, expiring code |
| Danger | `sl-notice--danger` | `danger-tint`, `danger` text | Failed payment, failed save |
| Offline | `sl-notice--offline` | `ink-soft` band, white text, square corners, full width under the status bar | No connection on mobile |
| In call | `sl-notice--call` | `brand-offline`, `brand-tint` text | Reconnecting inside a call |

Write the outcome first ("You weren't charged."), then the fix. No apologies, no "Oops".

Don't stack more than one notice; show the most urgent.
