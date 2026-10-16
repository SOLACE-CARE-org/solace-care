# CountBadge

A number beside a nav item or a heading, or a dot for one unread item.

**Consumer provides:** the count and an `aria-label` that says what is counted ("3 open crisis alerts").

- `sl-count`: 22px high, `radius` 11px, `danger-count` fill, white 12px numerals. Reserved for the crisis queue count.
- `sl-count--warning`: `warning` fill, for work waiting (applications, reviews).
- `sl-dot`: 10px `warning` dot, beside unread notifications and messages.

Don't show a badge for zero; hide it. Cap the number at 99+.
