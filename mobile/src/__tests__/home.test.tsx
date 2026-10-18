import { QueryClientProvider } from '@tanstack/react-query';
import { fireEvent, waitFor } from '@testing-library/react-native';
import { Slot } from 'expo-router';
import { renderRouter } from 'expo-router/testing-library';

import { queryClient } from '@/api/query-client';
import Home, { useHomeSnapshot } from '@/app/(seeker)/home';
import Intro from '@/app/intro';
import EditProfile from '@/app/profile/edit';
import { useAuth } from '@/auth/auth';
import { initI18n } from '@/i18n';
import { useSession } from '@/state/session';

jest.setTimeout(30_000);

function Layout() {
  return (
    <QueryClientProvider client={queryClient}>
      <Slot />
    </QueryClientProvider>
  );
}

type Call = { url: string; method: string; body: string };
let calls: Call[];
let answers: Record<string, { status: number; body?: unknown }>;
let offline = false;

const inThreeHours = new Date(Date.now() + 3 * 3600_000);
const booking = {
  id: 'b1',
  status: 'CONFIRMED',
  specialist: { id: 's1', fullName: 'Dr. Ngo Ebode Clarisse' },
  start: inThreeHours.toISOString(),
  end: new Date(inThreeHours.getTime() + 50 * 60_000).toISOString(),
  modality: 'AUDIO',
  sessionLanguage: 'en',
  price: 15000,
};
const me = {
  id: '2c9b6f0e-8d41-4a7a-b3e5-5f0d9c1a7e64',
  role: 'SEEKER',
  displayName: 'Mireille',
  phone: '+237677000000',
  appLanguage: 'en',
  onboarding: { preferences: true, privacy: true },
};

beforeEach(async () => {
  await initI18n('en');
  useSession.setState({ language: 'en', role: 'seeker' });
  useHomeSnapshot.setState({ next: null, at: null });
  await useAuth.getState().signedIn({
    accessToken: 'access',
    refreshToken: 'refresh',
    accessExpiresAt: Date.now() + 300_000,
  });
  queryClient.clear();
  calls = [];
  offline = false;
  answers = {
    'GET /v1/me': { status: 200, body: me },
    'GET /v1/me/bookings': { status: 200, body: [booking] },
    'GET /v1/catalogue/session-languages': {
      status: 200,
      body: [
        { code: 'en', name: 'English' },
        { code: 'fr', name: 'Français' },
      ],
    },
    'GET /v1/me/preferences': {
      status: 200,
      body: {
        appLanguage: 'en',
        sessionLanguages: ['en'],
        helpTopics: [],
        specialistGender: 'ANY',
      },
    },
    'PUT /v1/me/profile': { status: 204 },
    'PUT /v1/me/preferences': {
      status: 200,
      body: {
        appLanguage: 'en',
        sessionLanguages: ['en', 'fr'],
        helpTopics: [],
        specialistGender: 'WOMAN',
      },
    },
  };
  globalThis.fetch = jest.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    if (offline) throw new TypeError('Network request failed');
    const request = input instanceof Request ? input : new Request(String(input), init);
    const call = {
      url: request.url,
      method: request.method,
      body: request.method === 'GET' ? '' : await request.clone().text(),
    };
    calls.push(call);
    const key = `${call.method} ${new URL(call.url).pathname.replace(/^.*\/core/, '')}`;
    const { status, body } = answers[key] ?? { status: 404 };
    return new Response(body === undefined ? null : JSON.stringify(body), {
      status,
      headers: { 'Content-Type': 'application/json' },
    });
  }) as typeof fetch;
});

describe('Seeker home', () => {
  it('greets the seeker and shows the next session with when joining opens', async () => {
    const screen = await renderRouter({ _layout: Layout, index: Home }, { initialUrl: '/' });
    expect(await screen.findByText(/, Mireille$/)).toBeTruthy();
    expect(await screen.findByText('with Dr. Ngo Ebode Clarisse')).toBeTruthy();
    expect(screen.getByText(/^Join opens /)).toBeTruthy();
    expect(screen.getByRole('button', { name: 'Reschedule' })).toBeTruthy();
    await waitFor(() => expect(useHomeSnapshot.getState().next?.id).toBe('b1'));
  });

  it('keeps the last next session offline and says what needs a connection', async () => {
    useHomeSnapshot.setState({ next: booking as never, at: new Date().toISOString() });
    offline = true;
    const screen = await renderRouter({ _layout: Layout, index: Home }, { initialUrl: '/' });
    expect(await screen.findByText("You're offline.", {}, { timeout: 15_000 })).toBeTruthy();
    expect(screen.getByText(/with Dr. Ngo Ebode Clarisse · last updated/)).toBeTruthy();
    expect(screen.getByText('Reschedule needs a connection')).toBeTruthy();
    expect(screen.getByText('Emergency numbers work offline')).toBeTruthy();
  });
});

describe('Intro', () => {
  it('walks through three screens, and Skip goes to Welcome', async () => {
    const screen = await renderRouter(
      { intro: Intro, welcome: () => null, crisis: () => null },
      { initialUrl: '/intro' },
    );
    expect(screen.getByText('Talk to a licensed specialist')).toBeTruthy();
    await fireEvent.press(screen.getByRole('button', { name: 'Next' }));
    expect(screen.getByText('Book without an account')).toBeTruthy();
    await fireEvent.press(screen.getByRole('button', { name: 'Next' }));
    expect(screen.getByText('Track what matters, privately')).toBeTruthy();
    await fireEvent.press(screen.getByRole('button', { name: 'Skip' }));
    expect(screen).toHavePathname('/welcome');
  });
});

describe('Edit profile', () => {
  it('saves the name and the preferences', async () => {
    const screen = await renderRouter(
      { _layout: Layout, 'profile/edit': EditProfile, index: () => null },
      { initialUrl: '/profile/edit' },
    );
    const name = await screen.findByDisplayValue('Mireille');
    fireEvent.changeText(name, 'Mimi');
    await fireEvent.press(await screen.findByRole('checkbox', { name: 'Français' }));
    await fireEvent.press(screen.getByRole('tab', { name: 'Woman' }));
    await fireEvent.press(screen.getByRole('button', { name: 'Save changes' }));
    await waitFor(() =>
      expect(calls.some((c) => c.method === 'PUT' && c.url.endsWith('/v1/me/preferences'))).toBe(
        true,
      ),
    );
    expect(JSON.parse(calls.find((c) => c.url.endsWith('/v1/me/profile'))!.body)).toEqual({
      displayName: 'Mimi',
    });
    const prefs = JSON.parse(
      calls.find((c) => c.url.endsWith('/v1/me/preferences') && c.method === 'PUT')!.body,
    );
    expect(prefs.sessionLanguages).toEqual(['en', 'fr']);
    expect(prefs.specialistGender).toBe('WOMAN');
  });
});
