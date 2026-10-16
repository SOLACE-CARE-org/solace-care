# BottomNav

The mobile app's main navigation. Seekers have four tabs (Home, Track, Sessions, You); specialists have five (Today, Calendar, Notes, Earnings, Profile).

**Consumer provides:** the tabs (24px stroke icon + label) and `aria-current="page"` on the current one.

- `surface-card` bar with a `line` top border; padding 8px sides (4px with five tabs) and 24px bottom for the home indicator.
- Labels `nav-label`: `ink-muted` weight 600; current tab `brand` weight 700. Icon and label both change.
- Hidden during calls, checkout and onboarding.

Don't add a sixth tab or a floating action button. The crisis entry point is the 44px "Get urgent help" disc in the home header (`danger-tint` / `danger`), not a tab.
