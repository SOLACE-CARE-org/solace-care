# OptionCard

A full-width radio or checkbox row with a title and an optional line of meta text. Used for onboarding choices, booking options, module enrolment questions and consent.

**Consumer provides:** a native `input` (radio or checkbox) inside the `label`, the title, optional meta, and a group with `role="radiogroup"` or a `fieldset` and legend.

- Rest: `surface-card`, 1px `line-control`, `radius-14`, padding `space-14`.
- Selected: `brand-tint` fill, 2px `brand` border (padding drops to 13px so nothing shifts).
- The control itself uses the platform control tinted `brand` (18px; 20px in admin).

Don't
- Pre-select consent options. Consent checkboxes always start unchecked.
- Put more than one line of meta; link to details instead.
