# Card

A white block on `surface-page` that groups one thing: a session, a module, a log entry, a record panel.

**Consumer provides:** the content. A card holds a title, optional meta, and at most one action row.

- Default: padding `space-16`, `radius-16`, 1px `line` border, no shadow.
- Large (`sl-card--lg`): padding `space-18`, `radius-18`, for home and crisis cards.
- Crisis and safety plan: `sl-card--crisis` swaps the border to `danger-line-soft`.
- Inside a card, use `sl-summary-row` (`surface-sunken`, `radius-12`) for label/value pairs, and `sl-icon-tile` (36px, `radius-12`, `accent` with `on-accent`) to mark upcoming sessions.
- Stack cards with `space-14` between them on mobile, `space-14`–`space-18` in admin grids.

Don't
- Add shadows. Solace separates surfaces with borders.
- Nest a card in a card; use a summary row.
- Add a coloured stripe down one side for status; use a Pill.
