# Skeleton

Grey blocks in the shape of the content that is loading. Used for lists and cards that take longer than about 300 ms.

**Consumer provides:** the layout of the real content, drawn with `sl-skel` blocks, and `aria-busy="true"` plus an `aria-label` ("Loading specialists") on the container.

- Fill `skeleton`, `radius-6` for lines; discs for avatars.
- Pulse only when the person allows motion (`prefers-reduced-motion`).
- Lines vary in width (45–70%) so it reads as text.
- After 10 seconds, replace with an error notice and a Try again button.

Don't show a spinner and a skeleton together, or a skeleton for the crisis screen: crisis numbers are bundled and show instantly, even offline.
