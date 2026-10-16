# Segmented

Switches between views of the same list. Two forms: the mobile segmented control and the admin tab bar.

**Consumer provides:** 2–4 tabs (`role="tab"`, `aria-selected`) inside a `role="tablist"` with a label, and a count in the label when it helps ("To review · 2" on mobile, "Submitted (3)" in admin).

Mobile (`sl-segmented`)
- Track `surface-track`, `radius-14`, inset `space-4`; segments 40px, `radius-10`, equal width.
- Selected: `surface-card` segment, `ink`. Others: `ink-muted`.

Admin (`sl-tabs`)
- White bar with a `line` border, `radius-12`; tabs 36px, `radius-9`, hug their label and wrap.
- Selected: `brand` fill, `on-brand`. Others: `ink-soft`.

Don't use more than four segments on mobile; long French labels need the room.
