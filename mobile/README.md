# Mobile app

One Expo app (React Native, TypeScript) for guests, seekers and specialists; the navigation shell follows the person's role after sign-in. Expo SDK 58, React Native 0.88, New Architecture and Hermes. Expo's own guidance for agents is in [AGENTS.md](AGENTS.md).

## Run it

Native modules are used, so the app runs as a development build, not in Expo Go.

```sh
# from the repo root, once
pnpm install                     # also generates packages/tokens/dist from tokens.json

# from mobile/
pnpm android                     # builds the dev client and installs it on a device or emulator
pnpm start                       # Metro for an installed dev client
pnpm test                        # Jest (jest-expo, Expo Router testing library)
pnpm typecheck
pnpm doctor                      # expo-doctor
```

`ios/` and `android/` are generated (Continuous Native Generation) and not committed; configure native behaviour in `app.json`.

### Building Android on Windows

React Native compiles C++ with CMake and ninja, which fail on paths over 260 characters ("Filename longer than 260 characters", or "build.ninja still dirty"). The repo path appears twice in those paths, so keep it short:

- The workspace uses pnpm's flat `node_modules` (`nodeLinker: hoisted` in `pnpm-workspace.yaml`) for this reason.
- Build from a short drive letter: `subst S: C:\path\to\solace`, then run `pnpm android` from `S:\mobile`. Or clone the repo to a short path such as `C:\sc`.
- Turning on Windows long paths (`LongPathsEnabled`, needs an administrator) helps other tools, but the ninja bundled with the Android SDK's CMake 3.22 ignores it.

CI and EAS build on Linux and are not affected.

Quick visual check without a device: `npx expo start --web` renders the screens in a browser (react-native-web). It is a preview for layout, copy and colour, not a product target.

## Open items

- **Splash lockup missing.** The Logos README names `solace-care-splash.svg`, but the bundle only has the light-ground logos; the stacked logo is drawn in `brand-splash` itself and disappears on the splash. The app icon (a light mark on exactly that green) stands in on the splash screen and the native splash until the lockup is added to `design/design-system/assets/Logos/` (see the comment in `src/app/splash.tsx`).
- **Low-end Android baseline.** Not measured yet: needs the reference phone (implementation-plan.md, Phase 0, Mobile shell).

## Layout

| Path | What |
| --- | --- |
| `src/app/` | Routes (Expo Router). `splash`, `language`, `welcome`; `(seeker)` and `(specialist)` tab shells; routes later epics build show a development stand-in |
| `src/ui/` | Design-system components in code: `Button`, `Text`, `CrisisButton`, the tab bar |
| `src/i18n/` | i18next over the shared catalogues in `packages/i18n` |
| `src/state/` | Device-local state (Zustand, persisted): the chosen app language and the role |
| `src/api/` | TanStack Query client |
| `src/config/flags.ts` | Later-phase features, off (the Track tab, Phase 2) |

## Rules this code follows

- Colours, spacing, radii, sizes and type come from `@solace/tokens/native`, generated from `design/design-system/tokens.json`. A few one-off values copied from a screen file (the language card radius, the welcome illustration) are named constants with the screen they come from.
- Fonts (Bricolage Grotesque, Figtree) are bundled from the Expo Google Fonts packages; nothing calls a font CDN.
- Logos are loaded from `design/design-system/assets/Logos/png` as supplied.
- Every string comes from `packages/i18n` in EN and FR; `pnpm --filter @solace/i18n check` fails on a missing key.
- Every control has a screen-reader label and a touch area of at least 44px.
