Solace Care is a bilingual (English and French) mental-health marketplace for Cameroon: seekers find and book specialists, join sessions by video or audio, and track their wellbeing between sessions; staff run the service from the admin portal. This system is the shared source for the React Native app and the Angular admin portal. Every value here is copied from the approved screens on the Solace App Screens and Solace Admin Portal canvases.

## Content fundamentals

**Voice.** Calm, plain and specific. Short sentences. Say what happens and what to do next. Never cheerful at someone's expense: a person may open the app on the worst day of their year.

- Sentence case everywhere: buttons, titles, pills ("Book session", "Under review").
- Address the person as "you"; Solace speaks as "we" only in system messages ("We sent a 6-digit code…").
- Outcome first, then the fix: "You weren't charged. Try again or pick another number."
- No exclamation marks, no emoji, no "Oops", no apologies in errors.
- Name things the way people know them: "session", "specialist", "reminder", not "appointment slot" or "provider".
- Numbers: times in 24-hour ("18:00"), dates as "Mon 19 Oct", money as "15 000 FCFA" (space every three digits), phones as "+237 6 77 00 00 00".

**Neutral wording outside the app.** Anything that can show on a lock screen, in an SMS or in an inbox never names a condition, a topic or a specialist's field. "You have an appointment today at 18:00", never "Your therapy session for anxiety". In discreet mode the sender shows as Solace Care and previews show no names at all. The full rules and every message are in the Notification Copy doc.

**Two languages, one design.** Every screen exists in English and French. The person chooses on first launch; content stored in one language (a specialist's bio, a note) stays as written. French runs up to a third longer, so:

- Let buttons and tabs wrap or grow; never truncate a label with an ellipsis.
- Keep French typographic spacing: a space before `:` `;` `?` `!` in French text ("Besoin d'aide ?").
- SMS copy: prefer é è à ù; ê â î ô û ç ë ï switch an SMS to 70 characters.

| English | French |
| --- | --- |
| Seeker | Usager |
| Session | Séance |
| Tracking | Suivi |
| Daily check-in | Bilan du jour |
| Payout / Payout run | Versement / Lot de versements |
| Crisis queue | File de crise |
| PCOS | SOPK |
| Guest | Invité |

## Visual foundations

**Colour.** A quiet green-and-sand palette on an off-white ground.

- Screens sit on `surface-page`; content sits on `surface-card` with a 1px `line` border.
- `brand` is the one action colour: primary buttons, selected states, links, current nav item. Text on it is `on-brand`.
- `accent` (sand) marks what is coming up: upcoming-session tiles and soft warning borders. It is never text; text on it is `on-accent`.
- Body text is `ink`; secondary text is `ink-muted`. Both pass 4.5:1 on every surface listed in their notes.
- Status always has words: a Pill or a Notice says "Payment pending"; colour only reinforces it. `danger` is for destructive actions, failures and crisis only.
- Tracking modules have their own hue so a person can tell them apart at a glance: `track-pink` for PCOS & cycle, `track-blue` for the daily check-in. Use them only on tracking screens.
- The splash screen alone uses `brand-splash` with `brand-splash-ring` discs.
- Light theme only at launch. A dark theme is not designed; don't improvise one in code.

**Type.** Two Google faces.

- Bricolage Grotesque (`--font-display`) for titles and headings: `display-xl` to `heading-sm`, weight 700 (600 for `heading`), tight negative tracking.
- Figtree (`--font-body`) for everything else: `body` (15px) on mobile, `body-admin` (14px) in the portal, `meta` for secondary lines, `caption` for pills and table headers.
- One display title per screen. Mobile body text never goes below 13px; admin never below 12px.
- Use tabular numerals for money, counts and times in tables.

**Spacing.** A 2px-based scale, used as copied: `space-20` is the mobile side gutter, `space-16` the card padding, `space-12` the default stack gap, `space-24` between sections. Admin pages use `space-28` top and `space-32` side padding with `space-14`–`space-18` between panels.

**Shape.** Soft and rounded, never sharp. Cards `radius-16` (`radius-18` for large ones), mobile buttons `radius-16`, inputs and secondary buttons `radius-14`, admin controls `radius-12`, pills and chips `radius-pill`. Decorative discs (splash, module cards) are full circles.

**Borders, not shadows.** Separate surfaces with `line` borders. The only shadow is `focus-ring`.

**Touch and size.** Every tap target is at least `size-tap` (44px). Mobile buttons are `size-button-mobile` (54px), fields `size-field-mobile` (50px). Admin controls are `size-control-admin` (42px). Mobile screens are designed at `size-phone-width` (390px); the admin sidebar is `size-admin-sidebar` (248px).

**Focus.** Every interactive element shows `focus-ring` on keyboard focus: a 2px white gap, then a 2px `brand` ring. This is a handoff addition; the screens don't draw focus states.

**States.** Every list has a loading state (Skeleton), an empty state (EmptyState), an error state (danger Notice with Try again) and, on mobile, an offline state (offline Notice; tracking logs still save). The patterns are on the States pages of both canvases.

**Motion.** Minimal. Skeletons pulse; screens push and pop with the platform default. Respect reduced-motion: no pulse, no slide.

## Iconography

- Line icons on a 24px grid, `stroke-width` 1.8 (1.6 at 40px in empty states), round caps and joins, `currentColor`, no fills.
- Sizes: 20px in buttons and notices, 24px in the bottom nav, 18px in the admin sidebar, 22px in header icon buttons, 40px in empty states.
- The screens draw their icons by hand in this style. For the build, use Lucide (`lucide-react-native`, `lucide-angular`) at stroke-width 1.8: it is the closest match, not the source, so check each icon against the screen.
- Icons always sit beside a word, except header icon buttons, which carry an `aria-label` ("Get urgent help", "Back").
- No emoji anywhere in the product.

## Logos

The Logos group holds the four marks used on the screens: the wordmark, the splash lockup, the small mark on the language screen and the app icon in notification previews. Use them as supplied; never redraw or recolour them. See the group's notes for where each goes.

## Contrast

Every text, icon and control colour here meets WCAG 2.1 AA on the surfaces its note names. Five colours were darkened on 16 Oct 2026 to get there, on both canvases:

| Token | Was | Now | Why |
| --- | --- | --- | --- |
| `ink-faint` | #8A968F | #66726C | Placeholder text needs 4.5:1 |
| `line-control` (new) | #C9D3CC | #7F8B84 | Control borders need 3:1; `line-strong` stays for dashed placeholders only |
| `rating-star` | #D69A2D | #BF8126 | Icons need 3:1 |
| `warning` | #A86A10 | #A36710 | White numerals need 4.5:1 |
| `success` | #2E8B57 | #2A7F50 | Small text needs 4.5:1 |

## Using this system in code

- Generate platform files from `tokens.json` at build time; never retype values. React Native: a typed `tokens.ts` (colours, spacing, radii, sizes, type styles). Angular: CSS custom properties with the same `--name`s as this system, plus classes per type style.
- Fonts: bundle Bricolage Grotesque and Figtree with the app (Expo Google Fonts packages) and self-host the woff2 files for the admin portal, so neither surface calls a font CDN at runtime.
- Component names here are the names in code: `Button`, `TextField`, `Chip`, `OptionCard`, `Switch`, `Segmented`, `Pill`, `CountBadge`, `Avatar`, `Card`, `Notice`, `Skeleton`, `EmptyState`, `ProgressSteps`, `BottomNav` (mobile), `Sidebar` and `DataTable` (admin). Each component's guidelines say what the caller provides.
- All copy comes from the i18n catalogues (i18next on mobile, Transloco on admin); components never hard-code text.
