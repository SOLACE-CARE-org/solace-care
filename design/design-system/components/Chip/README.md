# Chip

A pill that toggles on and off: search filters, session format, and quick picks in tracking logs.

**Consumer provides:** the label, `aria-pressed` state, and a group label on the wrapping element (`role="group"`, `aria-label`).

- Off: `surface-card`, 1px `line-control` border, `ink`, weight 600, 38px high (36px in dense rows).
- On: 2px `brand` border, `brand-tint` fill, `brand-deep`, weight 700. The heavier border and weight carry the state, not the colour alone.
- Cycle-tracking elements use `sl-chip--pink` (`track-pink` border, `track-pink-tint`, `track-pink-ink`).
- Rows wrap; never scroll a chip row sideways on admin.

Don't use a chip for navigation (use Segmented or Tabs) or for a status (use Pill).
