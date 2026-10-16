# ProgressSteps

Segmented bar for multi-step flows: onboarding, booking, specialist application, enrolment.

**Consumer provides:** the total steps, the current step, and `aria-label="Step 3 of 4"` on the `role="progressbar"`.

- Segments 6px high, `radius-3`, `space-4` apart, equal width.
- Done and current: `brand`. Remaining: `line`.
- Pair with a back button on the left of the same row.

Don't use it for more than six steps; split the flow instead.
