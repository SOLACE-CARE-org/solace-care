import { QueryClientProvider } from '@tanstack/react-query';
import { fireEvent, waitFor } from '@testing-library/react-native';
import { Slot } from 'expo-router';
import { renderRouter } from 'expo-router/testing-library';

import { queryClient } from '@/api/query-client';
import SpecialistProfileTab from '@/app/(specialist)/profile';
import Search from '@/app/search';
import SpecialistProfileScreen from '@/app/specialist/[id]';
import { useAuth } from '@/auth/auth';
import { initI18n } from '@/i18n';
import { whenLabel } from '@/state/format';
import { useSession } from '@/state/session';

function Layout() {
  return (
    <QueryClientProvider client={queryClient}>
      <Slot />
    </QueryClientProvider>
  );
}

const routes = {
  _layout: Layout,
  search: Search,
  'specialist/[id]': SpecialistProfileScreen,
  '(specialist)/profile': SpecialistProfileTab,
  welcome: () => null,
};

type Call = { url: string; method: string; body: string };
let calls: Call[];
let answer: (call: Call) => { status: number; body?: unknown };

beforeEach(async () => {
  await initI18n('en');
  useSession.setState({ language: 'en', role: null });
  await useAuth.getState().signOut();
  queryClient.clear();
  calls = [];
  globalThis.fetch = jest.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const request = input instanceof Request ? input : new Request(String(input), init);
    const call = {
      url: request.url,
      method: request.method,
      body: request.method === 'GET' ? '' : await request.clone().text(),
    };
    calls.push(call);
    const { status, body } = answer(call);
    return new Response(body === undefined ? null : JSON.stringify(body), {
      status,
      headers: { 'Content-Type': 'application/json' },
    });
  }) as typeof fetch;
});

const languages = [
  { code: 'en', name: 'English' },
  { code: 'fr', name: 'Français' },
];
const clarisse = {
  id: '2c9b6f0e-8d41-4a7a-b3e5-5f0d9c1a7e64',
  fullName: 'Ngo Ebode Clarisse',
  professionLabel: 'Clinical psychologist',
  focusAreas: ['Anxiety', 'Low mood', 'Grief', 'Sleep'],
  languages: [
    { code: 'en', level: 'NATIVE' },
    { code: 'fr', level: 'FLUENT' },
  ],
  offering: { modality: 'AUDIO', durationMinutes: 50, price: 15000 },
};

describe('when a session could start', () => {
  it('says today, tomorrow, or the date', () => {
    const t = ((key: string, o: { time: string }) => `${key}|${o.time}`) as never;
    const now = new Date(2026, 9, 19, 8, 0);
    expect(whenLabel(new Date(2026, 9, 19, 18, 0).toISOString(), t, 'en', now)).toMatch(/^search\.today\|/);
    expect(whenLabel(new Date(2026, 9, 20, 9, 0).toISOString(), t, 'en', now)).toMatch(/^search\.tomorrow\|/);
    expect(whenLabel(new Date(2026, 9, 22, 16, 0).toISOString(), t, 'en', now)).toMatch(/22/);
  });
});

describe('Find a specialist', () => {
  it('searches in the session language first, by audio, and opens a profile', async () => {
    answer = ({ url }) => {
      if (url.includes('/v1/catalogue/session-languages')) return { status: 200, body: languages };
      if (url.includes('/v1/catalogue/specialists/')) return { status: 404 };
      if (url.includes('/v1/catalogue/specialists'))
        return { status: 200, body: { items: [clarisse], hasMore: false } };
      return { status: 200, body: [] };
    };
    const screen = await renderRouter(routes, { initialUrl: '/search' });

    expect(await screen.findByText('Ngo Ebode Clarisse')).toBeTruthy();
    expect(screen.getByText('Specialists who counsel in English by audio')).toBeTruthy();
    expect(screen.getByText('15,000 FCFA · 50 min')).toBeTruthy();
    expect(screen.getByText('English · Native   Français · Fluent')).toBeTruthy();
    // Three focus areas on the card.
    expect(screen.queryByText('Sleep')).toBeNull();
    const first = calls.find((c) => c.url.includes('/v1/catalogue/specialists'))!;
    expect(first.url).toContain('language=en');
    expect(first.url).toContain('modality=AUDIO');

    await fireEvent.press(screen.getByRole('radio', { name: 'Français' }));
    await waitFor(() =>
      expect(calls.some((c) => c.url.includes('/v1/catalogue/specialists') && c.url.includes('language=fr'))).toBe(true),
    );

    await fireEvent.press(await screen.findByRole('button', { name: /^Ngo Ebode Clarisse/ }));
    await waitFor(() => expect(screen.getPathname()).toBe(`/specialist/${clarisse.id}`));
  });

  it('offers the other language when nobody matches', async () => {
    answer = ({ url }) => {
      if (url.includes('/v1/catalogue/session-languages')) return { status: 200, body: languages };
      if (url.includes('/v1/catalogue/specialists')) return { status: 200, body: { items: [], hasMore: false } };
      return { status: 200, body: [] };
    };
    const screen = await renderRouter(routes, { initialUrl: '/search' });

    expect(await screen.findByText('Nobody free for this yet')).toBeTruthy();
    await fireEvent.press(screen.getByRole('button', { name: 'Search in Français instead' }));
    await waitFor(() => expect(screen.getByRole('radio', { name: 'Français' })).toBeChecked());
  });
});

describe('Specialist profile', () => {
  it('shows languages, sessions and the next start, in the app language', async () => {
    answer = ({ url }) => {
      if (url.includes('/v1/catalogue/session-languages')) return { status: 200, body: languages };
      return {
        status: 200,
        body: {
          id: clarisse.id,
          fullName: clarisse.fullName,
          professionLabel: clarisse.professionLabel,
          bio: 'I help adults through anxiety.',
          languages: clarisse.languages,
          offerings: [
            { modality: 'AUDIO', durationMinutes: 50, price: 15000 },
            { modality: 'VIDEO', durationMinutes: 50, price: 18000 },
          ],
          focusAreas: ['Anxiety'],
        },
      };
    };
    const screen = await renderRouter(routes, { initialUrl: `/specialist/${clarisse.id}` });

    expect(await screen.findByText('I help adults through anxiety.')).toBeTruthy();
    expect(screen.getByText('Licence verified by Solace')).toBeTruthy();
    expect(screen.getByText('Works on weak connections')).toBeTruthy();
    expect(screen.getByText('18,000 FCFA')).toBeTruthy();
    expect(screen.getByRole('button', { name: 'Book a session' })).toBeTruthy();
  });
});

describe('Profile tab', () => {
  it('asks the specialist to check the bio Solace drafted, and confirms it', async () => {
    await useAuth.getState().signedIn({
      accessToken: 'access',
      refreshToken: 'refresh',
      accessExpiresAt: Date.now() + 300_000,
    });
    const application = (confirmed: boolean) => ({
      status: 'APPROVED',
      fullName: 'Ngo Ebode Clarisse',
      steps: { practice: true, languages: true, documents: true, services: true, hours: true, payout: true },
      languages: [{ code: 'en', proficiency: 'NATIVE', verified: true }],
      documents: [],
      offerings: [{ modality: 'AUDIO', enabled: true, durationMinutes: 50, price: 15000 }],
      pricing: { minPrice: 5000, maxPrice: 20000, feePercent: 20, durations: [30, 50, 80] },
      draftedBio: { language: 'fr', text: "J'accompagne les adultes.", confirmed },
    });
    let confirmed = false;
    answer = ({ url, method }) => {
      if (url.endsWith('/bio-confirmation') && method === 'POST') {
        confirmed = true;
        return { status: 200, body: application(true) };
      }
      if (url.endsWith('/v1/me/specialist-application')) return { status: 200, body: application(confirmed) };
      if (url.endsWith('/v1/me/weekly-hours'))
        return { status: 200, body: { timeZone: 'Africa/Douala', breakMinutes: 10, minimumNoticeHours: 2, days: [] } };
      if (url.endsWith('/v1/me'))
        return {
          status: 200,
          body: { id: clarisse.id, role: 'SPECIALIST', displayName: 'Ngo Ebode Clarisse', appLanguage: 'en', onboarding: { preferences: true, privacy: true }, applicationStatus: 'APPROVED' },
        };
      return { status: 200, body: [] };
    };
    const screen = await renderRouter(routes, { initialUrl: '/profile' });

    expect(await screen.findByText('Check your French bio')).toBeTruthy();
    expect(screen.getByText('Verified')).toBeTruthy();
    await fireEvent.press(screen.getByRole('button', { name: 'Confirm bio' }));

    await waitFor(() => expect(calls.some((c) => c.url.endsWith('/bio-confirmation'))).toBe(true));
    await waitFor(() => expect(screen.getByText('Verified · profile live')).toBeTruthy());
    const confirm = calls.find((c) => c.url.endsWith('/bio-confirmation'))!;
    expect(JSON.parse(confirm.body)).toEqual({});
  });
});
