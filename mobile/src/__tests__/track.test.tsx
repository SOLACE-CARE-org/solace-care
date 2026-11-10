import { QueryClientProvider } from '@tanstack/react-query';
import { fireEvent, waitFor } from '@testing-library/react-native';
import { Slot } from 'expo-router';
import { renderRouter } from 'expo-router/testing-library';

import { queryClient } from '@/api/query-client';
import Track from '@/app/(seeker)/track';
import RecommendationScreen from '@/app/track/recommendation/[id]';
import Catalogue from '@/app/track/catalogue';
import LogToday from '@/app/track/log/[code]';
import { useAuth } from '@/auth/auth';
import { initI18n } from '@/i18n';
import { useSession } from '@/state/session';
import { flush, useOutbox } from '@/tracking/outbox';
import { pendingStore } from '@/tracking/store';

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
let offline = false;
let trackingDown = false;

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
const note = {
  ...mood,
  code: 'note',
  valueType: 'TEXT',
  constraints: { max: 4000 },
  inputView: 'free_text',
  signals: [],
  labels: { en: { name: 'Note' }, fr: { name: 'Note' } },
};
const checkin = {
  code: 'daily_checkin',
  version: '1',
  accentColour: 'brand',
  name: { en: 'Daily check-in', fr: 'Bilan du jour' },
  description: { en: 'Mood, sleep and a private note', fr: 'Humeur, sommeil et une note privée' },
  audience: { en: 'Anyone', fr: 'Tout le monde' },
  imageAlt: { en: 'Shapes', fr: 'Formes' },
  elements: [
    { code: 'mood', version: '1', required: true, position: 0 },
    { code: 'note', version: '1', required: false, position: 1 },
  ],
  minAppVersion: '1.0.0',
  enrolled: true,
  reminderTime: '20:00',
};
const pcos = {
  ...checkin,
  code: 'pcos',
  accentColour: 'track-pink',
  name: { en: 'PCOS & cycle', fr: 'SOPK et cycle' },
  description: { en: 'Periods, flow, skin and hair', fr: 'Règles, flux, peau et pilosité' },
  enrolled: false,
  reminderTime: undefined,
  reminderDefault: '20:00:00',
  enrolmentQuestion: {
    kind: 'YES_NO',
    question: { en: 'Are you trying to conceive?', fr: 'Avez-vous un projet de grossesse ?' },
  },
};
const invite = {
  id: 'rec-1',
  ruleCode: 'pcos.long_cycles',
  moduleCode: 'pcos',
  outcome: 'INVITE',
  specialtyCodes: ['gynaecology'],
  status: 'NEW',
  message: 'Each of your last three cycles ran longer than 35 days. This is not a diagnosis.',
  evidence: {
    n: 3,
    threshold: 35,
    episodes: [
      { start: '2026-07-01', value: 41 },
      { start: '2026-08-11', value: 39 },
      { start: '2026-09-19', value: 44 },
    ],
  },
  createdAt: '2026-10-15T08:00:00Z',
  dataChanged: false,
};

function consents(tracking: boolean) {
  return {
    status: 200,
    body: {
      consents: [
        { purpose: 'TRACKING', granted: tracking, version: 'v1' },
        { purpose: 'AI_ASSIST', granted: false, version: 'v1' },
      ],
    },
  };
}

beforeEach(async () => {
  await initI18n('en');
  useSession.setState({ language: 'en', role: 'seeker' });
  await useAuth.getState().signedIn({
    accessToken: 'access',
    refreshToken: 'refresh',
    accessExpiresAt: Date.now() + 300_000,
  });
  queryClient.clear();
  await pendingStore.clear();
  useOutbox.setState({ pending: [] });
  calls = [];
  offline = false;
  trackingDown = false;
  answers = {
    'GET /v1/me/consents': consents(true),
    'GET /v1/tracking/modules': { status: 200, body: [checkin, pcos] },
    'GET /v1/tracking/elements': { status: 200, body: [mood, note] },
    'GET /v1/tracking/recommendations': { status: 200, body: [] },
    'GET /v1/tracking/sync/pull': {
      status: 200,
      body: { observations: [], deleted: [], cursor: 0, hasMore: false },
    },
    'GET /v1/tracking/cycles': { status: 200, body: { cycles: [] } },
  };
  globalThis.fetch = jest.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const request = input instanceof Request ? input : new Request(String(input), init);
    const path = new URL(request.url).pathname.replace(/^.*\/api\/(core|tracking)/, '');
    if (trackingDown && path.startsWith('/v1/tracking'))
      throw new TypeError('Network request failed');
    if (offline && path.startsWith('/v1/tracking/sync'))
      throw new TypeError('Network request failed');
    const call = {
      method: request.method,
      path,
      body: request.method === 'GET' ? '' : await request.clone().text(),
    };
    calls.push(call);
    const { status, body } = answers[`${call.method} ${path}`] ?? { status: 404 };
    return new Response(body === undefined ? null : JSON.stringify(body), {
      status,
      headers: { 'Content-Type': 'application/json' },
    });
  }) as typeof fetch;
});

describe('Track tab', () => {
  it('shows the modules the person tracks, and invites them to add one when there are none', async () => {
    const screen = await renderRouter({ _layout: Layout, index: Track }, { initialUrl: '/' });
    expect(await screen.findByText('Your modules')).toBeTruthy();
    expect(screen.getByText('Daily check-in')).toBeTruthy();
    expect(screen.getByText('Not logged today')).toBeTruthy();
    expect(screen.getByRole('button', { name: 'Log today, Daily check-in' })).toBeTruthy();

    queryClient.clear();
    answers['GET /v1/tracking/modules'] = { status: 200, body: [{ ...checkin, enrolled: false }] };
    const empty = await renderRouter({ _layout: Layout, index: Track }, { initialUrl: '/' });
    expect(await empty.findByText("You're not tracking anything yet")).toBeTruthy();
  });

  it('keeps the modules and labels on the phone for logging offline', async () => {
    const first = await renderRouter({ _layout: Layout, index: Track }, { initialUrl: '/' });
    expect(await first.findByText('Daily check-in')).toBeTruthy();

    queryClient.clear();
    trackingDown = true;
    const again = await renderRouter({ _layout: Layout, index: Track }, { initialUrl: '/' });
    expect(await again.findByText('Daily check-in')).toBeTruthy();
    expect(again.getByRole('button', { name: 'Log today, Daily check-in' })).toBeTruthy();
  });

  it('asks for the tracking consent before anything else', async () => {
    answers['GET /v1/me/consents'] = consents(false);
    const screen = await renderRouter({ _layout: Layout, index: Track }, { initialUrl: '/' });
    expect(await screen.findByText('Health tracking is off')).toBeTruthy();
    expect(calls.some((c) => c.path === '/v1/tracking/modules')).toBe(false);
  });

  it('opens an invitation from its card', async () => {
    answers['GET /v1/tracking/recommendations'] = { status: 200, body: [invite] };
    const screen = await renderRouter(
      { _layout: Layout, index: Track, 'track/recommendation/[id]': () => null },
      { initialUrl: '/' },
    );
    expect(await screen.findByText('PCOS & cycle · see the pattern')).toBeTruthy();
    await fireEvent.press(screen.getByText(invite.message));
    expect(screen).toHavePathname('/track/recommendation/rec-1');
  });
});

describe('Logging today', () => {
  it('saves on the phone first and syncs when it can', async () => {
    offline = true;
    answers['POST /v1/tracking/sync/push'] = {
      status: 200,
      body: { accepted: [], rejected: [], cursor: 1 },
    };
    const screen = await renderRouter(
      { _layout: Layout, index: () => null, 'track/log/[code]': LogToday },
      { initialUrl: '/track/log/daily_checkin' },
    );
    expect(await screen.findByText('How was today?')).toBeTruthy();
    const save = screen.getByRole('button', { name: 'Save check-in' });
    expect(save.props.accessibilityState.disabled).toBe(true);
    await fireEvent.press(await screen.findByRole('radio', { name: 'Good' }));
    await fireEvent.changeText(screen.getByLabelText('Note'), 'Long day at work');
    await fireEvent.press(screen.getByRole('button', { name: 'Save check-in' }));

    // Offline: both entries stay on the phone.
    await waitFor(() => expect(useOutbox.getState().pending).toHaveLength(2));
    expect(
      useOutbox
        .getState()
        .pending.map((p) => p.element)
        .sort(),
    ).toEqual(['mood', 'note']);

    // Back online: the push carries both entries, and what Tracking accepts leaves the phone.
    offline = false;
    const rows = await pendingStore.all();
    answers['POST /v1/tracking/sync/push'] = {
      status: 200,
      body: {
        accepted: rows.map((r) => ({
          clientEntryId: r.clientEntryId,
          observationId: r.clientEntryId,
        })),
        rejected: [],
        cursor: 2,
      },
    };
    expect(await flush()).toBe(true);
    const push = calls.find((c) => c.path === '/v1/tracking/sync/push')!;
    const body = JSON.parse(push.body) as {
      entries: { element: { code: string }; value: unknown }[];
    };
    expect(body.entries.find((e) => e.element.code === 'mood')?.value).toBe(4);
    expect(body.entries.find((e) => e.element.code === 'note')?.value).toBe('Long day at work');
    expect(useOutbox.getState().pending).toHaveLength(0);
  });

  it('shows what is waiting to sync on the Track tab', async () => {
    await pendingStore.put([
      {
        clientEntryId: 'c1',
        body: JSON.stringify({
          clientEntryId: 'c1',
          element: { code: 'mood', version: '1' },
          observedOn: localToday(),
          timeZone: 'Africa/Douala',
          value: 3,
          recordedAt: new Date().toISOString(),
        }),
        savedAt: new Date().toISOString(),
      },
    ]);
    await useOutbox.getState().refresh();
    const screen = await renderRouter({ _layout: Layout, index: Track }, { initialUrl: '/' });
    expect(await screen.findByText('Waiting to sync')).toBeTruthy();
    expect(screen.getByText("Saved on this phone. It syncs when you're back online.")).toBeTruthy();
    expect(await screen.findByText('Logged today')).toBeTruthy();
  });
});

describe('Adding a module', () => {
  it('asks the enrolment question, then adds the module with its answer', async () => {
    answers['POST /v1/tracking/modules/pcos/enrolment'] = { status: 204 };
    const screen = await renderRouter(
      { _layout: Layout, 'track/catalogue': Catalogue },
      { initialUrl: '/track/catalogue' },
    );
    expect(await screen.findByText('PCOS & cycle')).toBeTruthy();
    expect(screen.getByText('Tracking')).toBeTruthy();
    await fireEvent.press(screen.getByRole('button', { name: 'Add PCOS & cycle' }));
    expect(screen.getByText('Are you trying to conceive?')).toBeTruthy();
    await fireEvent.press(screen.getByText('No'));
    await fireEvent.press(screen.getByRole('button', { name: 'Add PCOS & cycle' }));
    await waitFor(() =>
      expect(calls.find((c) => c.path === '/v1/tracking/modules/pcos/enrolment')?.body).toBe(
        JSON.stringify({ answer: 'no', reminderTime: '20:00' }),
      ),
    );
  });
});

describe('An invitation', () => {
  it('states the pattern against its threshold, marks it seen and books with the invite', async () => {
    answers['GET /v1/tracking/recommendations'] = { status: 200, body: [invite] };
    answers['POST /v1/tracking/recommendations/rec-1/seen'] = { status: 204 };
    answers['GET /v1/me/invites/rec-1/specialists'] = {
      status: 200,
      body: {
        specialties: [{ code: 'gynaecology', label: 'Gynaecology' }],
        specialists: [
          {
            id: 'sp-1',
            fullName: 'Dr. Mbarga Solange',
            professionLabel: 'Gynaecologist',
            languages: ['en'],
          },
        ],
      },
    };
    const screen = await renderRouter(
      {
        _layout: Layout,
        'track/recommendation/[id]': RecommendationScreen,
        'book/[id]': () => null,
      },
      { initialUrl: '/track/recommendation/rec-1' },
    );
    expect(await screen.findByText('Consider talking to a specialist in gynaecology')).toBeTruthy();
    // The chart is one summary for screen readers; its parts are drawn for the eye only.
    expect(screen.getByText('35-day guide', { includeHiddenElements: true })).toBeTruthy();
    expect(screen.getByLabelText('Your last 3 cycles: 41, 39, 44 days')).toBeTruthy();
    expect(screen.getByText(invite.message)).toBeTruthy();
    await waitFor(() =>
      expect(calls.some((c) => c.path === '/v1/tracking/recommendations/rec-1/seen')).toBe(true),
    );
    await fireEvent.press(screen.getByRole('button', { name: 'Book a session' }));
    expect(screen).toHavePathname('/book/sp-1');
    expect(screen).toHaveSearchParams({ id: 'sp-1', recommendationId: 'rec-1' });
  });
});

function localToday(): string {
  const d = new Date();
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`;
}
