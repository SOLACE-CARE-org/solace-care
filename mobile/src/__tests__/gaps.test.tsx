import { QueryClientProvider } from '@tanstack/react-query';
import { fireEvent, waitFor } from '@testing-library/react-native';
import { Slot } from 'expo-router';
import { renderRouter } from 'expo-router/testing-library';
import { Linking } from 'react-native';

import { queryClient } from '@/api/query-client';
import Home from '@/app/(seeker)/home';
import SpecialistToday from '@/app/(specialist)/today';
import Crisis from '@/app/crisis';
import SessionDetail from '@/app/session/[id]';
import Settings from '@/app/settings';
import { useAuth } from '@/auth/auth';
import { useFlags } from '@/config/flags';
import { initI18n } from '@/i18n';
import { useSafetyPlan } from '@/state/safetyPlan';
import { useSession } from '@/state/session';

jest.setTimeout(30_000);

function Layout() {
  return (
    <QueryClientProvider client={queryClient}>
      <Slot />
    </QueryClientProvider>
  );
}

type Call = { method: string; path: string; body: string };
let calls: Call[];
let answers: Record<string, { status: number; body?: unknown }>;

const start = new Date(Date.now() + 2 * 86_400_000).toISOString();
const booking = {
  id: 'b-1',
  status: 'CONFIRMED',
  specialist: { id: 's-1', fullName: 'Dr. Ngo Ebode' },
  start,
  end: new Date(Date.parse(start) + 50 * 60_000).toISOString(),
  modality: 'AUDIO',
  sessionLanguage: 'en',
  price: 15000,
  reschedulesLeft: 2,
};
const mood = {
  code: 'mood',
  version: '1',
  kind: 'DAY_STATE',
  valueType: 'INT',
  constraints: { min: 1, max: 5 },
  inputView: 'scale_5',
  icon: 'mood',
  signals: ['daily_value'],
  labels: {
    en: { name: 'Mood', values: { 1: 'Awful', 2: 'Low', 3: 'Okay', 4: 'Good', 5: 'Great' } },
    fr: {
      name: 'Humeur',
      values: { 1: 'Très mal', 2: 'Pas bien', 3: 'Moyen', 4: 'Bien', 5: 'Très bien' },
    },
  },
  minAppVersion: '1.0.0',
};
const checkin = {
  code: 'daily_checkin',
  version: '1',
  accentColour: 'brand',
  name: { en: 'Daily check-in', fr: 'Bilan du jour' },
  description: { en: 'Mood, sleep and a note', fr: 'Humeur, sommeil et une note' },
  audience: { en: 'Anyone', fr: 'Tout le monde' },
  imageAlt: { en: 'Shapes', fr: 'Formes' },
  elements: [{ code: 'mood', version: '1', required: true, position: 0 }],
  presentation: {
    quickLog: 'mood',
    quickLogTitle: { en: 'How are you feeling today?', fr: "Comment vous sentez-vous aujourd'hui ?" },
  },
  minAppVersion: '1.0.0',
  enrolled: true,
  reminderTime: '20:00',
};

beforeEach(async () => {
  await initI18n('en');
  useSession.setState({ language: 'en', role: 'seeker', appLock: false });
  useFlags.setState({ trackTab: true, companion: true, sessionScribe: false });
  useSafetyPlan.setState({ plan: undefined });
  await useAuth.getState().signedIn({
    accessToken: 'access',
    refreshToken: 'refresh',
    accessExpiresAt: Date.now() + 300_000,
  });
  queryClient.clear();
  queryClient.setDefaultOptions({ queries: { retry: false } });
  calls = [];
  answers = {
    'GET /v1/me': { status: 200, body: { id: 'me', displayName: 'Mireille', type: 'SEEKER' } },
    'GET /v1/me/bookings': { status: 200, body: [booking] },
    'GET /v1/bookings/b-1': { status: 200, body: booking },
    'GET /v1/catalogue/session-languages': { status: 200, body: [{ code: 'en', name: 'English' }] },
    'GET /v1/me/consents': {
      status: 200,
      body: { consents: [{ purpose: 'TRACKING', granted: true, version: 'v1' }] },
    },
    'GET /v1/tracking/modules': { status: 200, body: [checkin] },
    'GET /v1/tracking/elements': { status: 200, body: [mood] },
    'GET /v1/tracking/recommendations': { status: 200, body: [] },
    'GET /v1/tracking/sync/pull': {
      status: 200,
      body: { observations: [], deleted: [], cursor: 0, hasMore: false },
    },
    'GET /v1/tracking/shares': { status: 200, body: [] },
    'POST /v1/tracking/shares': {
      status: 201,
      body: { id: 'g-1', specialistId: 's-1', includesNotes: false, grantedAt: start },
    },
    'GET /v1/me/homework': { status: 200, body: [] },
  };
  globalThis.fetch = jest.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const request = input instanceof Request ? input : new Request(String(input), init);
    const path = new URL(request.url).pathname.replace(/^.*\/api\/(core|tracking|ai)/, '');
    const call = {
      method: request.method,
      path,
      body: request.method === 'GET' ? '' : await request.clone().text(),
    };
    calls.push(call);
    const { status, body } = answers[`${call.method} ${path}`] ?? { status: 404 };
    return new Response(body === undefined || status === 204 ? null : JSON.stringify(body), {
      status,
      headers: { 'Content-Type': 'application/json' },
    });
  }) as typeof fetch;
});

afterAll(() => {
  queryClient.setDefaultOptions({
    queries: { retry: 1, staleTime: 30_000, refetchOnWindowFocus: false },
  });
});

describe('Home with tracking', () => {
  it('logs the mood in one tap and shows the tracked modules', async () => {
    const screen = await renderRouter({ _layout: Layout, index: Home }, { initialUrl: '/' });
    expect(await screen.findByText('How are you feeling today?')).toBeTruthy();
    expect(await screen.findByText('Daily check-in')).toBeTruthy();
    await fireEvent.press(screen.getByRole('radio', { name: 'Good' }));
    expect(await screen.findByText('Saved for today. Change it any time today.')).toBeTruthy();
  });
});

describe('Sharing with the specialist', () => {
  it('shares what is logged from now on with this specialist', async () => {
    const screen = await renderRouter(
      { _layout: Layout, 'session/[id]': SessionDetail },
      { initialUrl: '/session/b-1' },
    );
    await fireEvent.press(await screen.findByRole('switch', { name: 'Share my tracking summary' }));
    await waitFor(() =>
      expect(calls).toContainEqual({
        method: 'POST',
        path: '/v1/tracking/shares',
        body: JSON.stringify({ specialistId: 's-1', includesNotes: false }),
      }),
    );
  });
});

describe('Accepting new bookings', () => {
  it('pauses new bookings from Today', async () => {
    useSession.setState({ language: 'en', role: 'specialist' });
    answers['GET /v1/me/bookings'] = { status: 200, body: [] };
    answers['GET /v1/me/specialist-application'] = {
      status: 200,
      body: { fullName: 'Dr. Ngo Ebode', status: 'APPROVED' },
    };
    answers['GET /v1/me/accepting-bookings'] = { status: 200, body: { accepting: true } };
    answers['PUT /v1/me/accepting-bookings'] = { status: 200, body: { accepting: false } };
    const screen = await renderRouter(
      { _layout: Layout, index: SpecialistToday },
      { initialUrl: '/' },
    );
    await fireEvent.press(await screen.findByRole('switch', { name: 'Accepting new bookings' }));
    expect(
      await screen.findByText('New bookings are paused. Sessions already booked stay.'),
    ).toBeTruthy();
    expect(calls).toContainEqual({
      method: 'PUT',
      path: '/v1/me/accepting-bookings',
      body: JSON.stringify({ accepting: false }),
    });
  });
});

describe('Urgent help', () => {
  it('starts a text to someone from the safety plan', async () => {
    const open = jest.spyOn(Linking, 'openURL').mockResolvedValue(undefined);
    useSafetyPlan.setState({
      plan: {
        warningSigns: 'Not sleeping',
        calming: 'A walk',
        people: [{ relation: 'Sister', name: 'Estelle', phone: '+237670000111' }],
        updatedAt: start,
      },
    });
    const screen = await renderRouter({ _layout: Layout, index: Crisis }, { initialUrl: '/' });
    expect(await screen.findByText('Message someone you trust')).toBeTruthy();
    await fireEvent.press(screen.getByRole('button', { name: 'Message Estelle' }));
    expect(open).toHaveBeenCalledWith('sms:+237670000111');
  });
});

describe('App lock', () => {
  it('turns on after the phone confirms it is the owner', async () => {
    answers['GET /v1/me/preferences'] = {
      status: 200,
      body: {
        appLanguage: 'en',
        sessionLanguages: ['en'],
        helpTopics: [],
        specialistGender: 'ANY',
      },
    };
    answers['GET /v1/me/notification-settings'] = {
      status: 200,
      body: { sessionReminders: true, discreet: false },
    };
    const screen = await renderRouter({ _layout: Layout, index: Settings }, { initialUrl: '/' });
    await fireEvent.press(await screen.findByRole('switch', { name: 'App lock' }));
    await waitFor(() => expect(useSession.getState().appLock).toBe(true));
  });
});
