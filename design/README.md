# Design files

The screens and the design system for Solace Care, all approved. Start with `../docs/screens.md`, which maps every screen to its file and build phase.

## screens/

- **`mobile/`** holds 81 screens: 69 product screens and 12 states, each in EN and FR. The phone frame is 390 × 844.
- **`admin/`** holds 39 boards: 31 numbered screens, the shared Sidebar, a phone layout of the crisis queue, first sign-in and 5 states, each in EN and FR. Layouts are 1440 wide.

Each folder contains:

- **`png/`:** a picture of every screen. Look here first.
  - They are rendered with fallback fonts. The real faces are Bricolage Grotesque and Figtree.
  - Logos show as a dashed "logo" box.
- **`source/*.dc.html`:** the design source. Use it for exact copy, spacing, colours and structure.
  - These are files from a design canvas, not app code.
  - `<x-dc>` wraps the markup.
  - `<helmet>` holds the head content.
  - `dc-import` pulls in another board (the admin Sidebar).
  - The `<script type="text/x-dc">` block holds the copy and view logic, and fills the `{{…}}` placeholders. On mobile it often includes the EN/FR copy tables.
  - Build the real screens with the design-system components and tokens.
- **`canvas.json`:** the board layout of the canvas, with pages, titles and positions. It also holds the sticky notes designers left, which are worth reading.

## design-system/

- **`README.md`:** the rules. Voice, colour use, type, spacing, shape, icons, contrast, and how to use the system in code.
- **`tokens.json`:** 84 tokens (colour, type, spacing, radius, size, shadow), each with a usage note. Generate platform files from it; never retype values.
- **`components/<Name>/`:**
  - `README.md` describes each component's variants and what the caller provides.
  - `preview.html` shows it rendered.
  - `bundle.css` holds the reference CSS.
- **`assets/Logos/`:** the four marks and where each one goes. See the note in that folder's README about getting the SVG files.
