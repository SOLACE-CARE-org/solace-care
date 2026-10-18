import { act, fireEvent } from '@testing-library/react-native';
import { renderRouter } from 'expo-router/testing-library';

import Language from '@/app/language';
import Splash from '@/app/splash';
import Welcome from '@/app/welcome';
import i18n, { initI18n } from '@/i18n';
import { useSession } from '@/state/session';

const routes = {
  splash: Splash,
  language: Language,
  welcome: Welcome,
  intro: () => null,
  // Find a specialist has its own tests (search.test.tsx); here only the way there matters.
  search: () => null,
};

beforeEach(async () => {
  await initI18n('en');
  useSession.setState({ language: null, role: null });
});

describe('splash', () => {
  it('is one labelled control that continues to the language choice', async () => {
    const screen = await renderRouter(routes, { initialUrl: '/splash' });

    await act(() => fireEvent.press(screen.getByLabelText('Solace Care. Tap to continue')));

    expect(screen.getPathname()).toBe('/language');
  });
});

describe('language choice', () => {
  it('always shows the title in both languages', async () => {
    const screen = await renderRouter(routes, { initialUrl: '/language' });

    expect(screen.getByText('Choose your language')).toBeTruthy();
    expect(screen.getByText('Choisissez votre langue')).toBeTruthy();
  });

  it('switches the screen to French as soon as French is picked, and saves it on Continue', async () => {
    const screen = await renderRouter(routes, { initialUrl: '/language' });

    await act(() => fireEvent.press(screen.getByRole('radio', { name: /^Français/ })));

    expect(screen.getByRole('radio', { name: /^Français/ })).toBeChecked();
    expect(screen.getByText(/Vous pourrez la changer plus tard/)).toBeTruthy();
    expect(screen.getByText("Utiliser l'application en anglais")).toBeTruthy();

    await act(() => fireEvent.press(screen.getByRole('button', { name: 'Continuer' })));

    expect(useSession.getState().language).toBe('fr');
    // The three intro screens come next (Onboard1–3.dc.html).
    expect(screen.getPathname()).toBe('/intro');
  });
});

describe('welcome', () => {
  it('shows the welcome copy, the crisis entry point and labelled controls', async () => {
    const screen = await renderRouter(routes, { initialUrl: '/welcome' });

    expect(screen.getByText('Someone to talk to, in your language.')).toBeTruthy();
    expect(screen.getByRole('button', { name: 'In crisis? Get help now' })).toBeTruthy();
    expect(screen.getByRole('image', { name: 'Solace Care' })).toBeTruthy();
    for (const control of [...screen.getAllByRole('button'), ...screen.getAllByRole('radio')]) {
      expect(control.props.accessibilityLabel).toBeTruthy();
    }
  });

  it('switches language from the EN/FR toggle', async () => {
    const screen = await renderRouter(routes, { initialUrl: '/welcome' });

    await act(() => fireEvent.press(screen.getByRole('radio', { name: 'Français' })));

    expect(i18n.language).toBe('fr');
    expect(screen.getByText("Quelqu'un à qui parler, dans votre langue.")).toBeTruthy();
    expect(screen.getByRole('button', { name: 'En crise ? Obtenez de l’aide maintenant' })).toBeTruthy();
    expect(useSession.getState().language).toBe('fr');
  });

  it('lets a guest find a specialist without an account', async () => {
    const screen = await renderRouter(routes, { initialUrl: '/welcome' });

    await act(() => fireEvent.press(screen.getByRole('button', { name: 'Find a specialist' })));

    expect(screen.getPathname()).toBe('/search');
  });
});
