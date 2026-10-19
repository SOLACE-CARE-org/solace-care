import { QueryClientProvider } from '@tanstack/react-query';
import { fireEvent, waitFor } from '@testing-library/react-native';
import { Slot } from 'expo-router';
import { renderRouter } from 'expo-router/testing-library';

import { queryClient } from '@/api/query-client';
import SessionBriefScreen from '@/app/brief/[id]';
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

type Call = { method: string; path: string; body: string };
let calls: Call[];
let answers: Record<string, { status: number; body?: unknown }>;

const brief = {
  startsAt: new Date(Date.now() + 3 * 3_600_000).toISOString(),
  modality: 'AUDIO',
  sessionLanguage: 'en',
  guest: true,
  firstSession: true,
  healthCheckShared: true,
  healthCheckSharedAt: '2026-10-14T09:00:00Z',
  healthCheckSummary: 'Help with: worry or anxiety, stress at work, sleep. For 1 to 6 months.',
  scores: [
    { screener: 'GAD-7', total: 11, band: 'moderate' },
    { screener: 'PHQ-2', total: 2, band: '' },
  ],
  healthCheckSafetyStop: false,
  scribeConsent: false,
};

beforeEach(async () => {
  await initI18n('en');
  useSession.setState({ language: 'en', role: 'specialist' });
  await useAuth.getState().signedIn({
    accessToken: 'access',
    refreshToken: 'refresh',
    accessExpiresAt: Date.now() + 300_000,
  });
  queryClient.clear();
  calls = [];
  answers = {
    'GET /v1/specialist/bookings/b-1/brief': { status: 200, body: brief },
    'GET /v1/specialist/bookings/b-1/prep-note': { status: 204 },
    'GET /v1/catalogue/session-languages': {
      status: 200,
      body: [{ code: 'en', name: 'English' }],
    },
  };
  globalThis.fetch = jest.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const request = input instanceof Request ? input : new Request(String(input), init);
    const path = new URL(request.url).pathname.replace(/^.*\/api\/(core|ai)/, '');
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

describe('Pre-session brief', () => {
  it('shows what the seeker shared, built by rules, and no tracking for a guest', async () => {
    const screen = await renderRouter(
      { _layout: Layout, 'brief/[id]': SessionBriefScreen },
      { initialUrl: '/brief/b-1' },
    );
    expect(await screen.findByText('Pre-session brief')).toBeTruthy();
    expect(screen.getByText('Guest')).toBeTruthy();
    expect(screen.getByText('First session')).toBeTruthy();
    expect(screen.getByText('No safety flags in the health check')).toBeTruthy();
    expect(screen.getByText('AI scribe: not consented')).toBeTruthy();
    expect(screen.getByText(brief.healthCheckSummary)).toBeTruthy();
    expect(screen.getByText('GAD-7 · PHQ-2')).toBeTruthy();
    expect(screen.getByText('11 moderate · 2')).toBeTruthy();
    expect(screen.getByText('None (guest)')).toBeTruthy();
    expect(screen.getByRole('button', { name: /Opens at/ })).toBeDisabled();
  });

  it('marks a tracking summary worded by AI and keeps the facts under it', async () => {
    answers['GET /v1/specialist/bookings/b-1/brief'] = {
      status: 200,
      body: {
        ...brief,
        guest: false,
        tracking: {
          summary: 'Mood was mostly steady over the two weeks.',
          aiWorded: true,
          lines: ['Mood logged on 12 of 14 days.'],
        },
      },
    };
    const screen = await renderRouter(
      { _layout: Layout, 'brief/[id]': SessionBriefScreen },
      { initialUrl: '/brief/b-1' },
    );
    expect(await screen.findByText('Mood was mostly steady over the two weeks.')).toBeTruthy();
    expect(screen.getByText('Mood logged on 12 of 14 days.')).toBeTruthy();
    expect(screen.getByText('Summary worded by AI from these lines only')).toBeTruthy();
  });

  it('saves the private prep notes', async () => {
    answers['PUT /v1/specialist/bookings/b-1/prep-note'] = {
      status: 200,
      body: { body: 'Explore sleep routine.', updatedAt: '2026-10-19T08:00:00Z' },
    };
    const screen = await renderRouter(
      { _layout: Layout, 'brief/[id]': SessionBriefScreen },
      { initialUrl: '/brief/b-1' },
    );
    await fireEvent.changeText(
      await screen.findByLabelText('Your private prep notes'),
      'Explore sleep routine.',
    );
    await fireEvent.press(await screen.findByRole('button', { name: 'Save notes' }));
    await waitFor(() =>
      expect(calls).toContainEqual({
        method: 'PUT',
        path: '/v1/specialist/bookings/b-1/prep-note',
        body: JSON.stringify({ body: 'Explore sleep routine.' }),
      }),
    );
    await waitFor(() => expect(screen.queryByRole('button', { name: 'Save notes' })).toBeNull());
  });

  it('offers to try again when the brief cannot load', async () => {
    answers['GET /v1/specialist/bookings/b-1/brief'] = { status: 503 };
    queryClient.setDefaultOptions({ queries: { retry: false } });
    const screen = await renderRouter(
      { _layout: Layout, 'brief/[id]': SessionBriefScreen },
      { initialUrl: '/brief/b-1' },
    );
    expect(await screen.findByRole('button', { name: 'Try again' })).toBeTruthy();
    queryClient.setDefaultOptions({
      queries: { retry: 1, staleTime: 30_000, refetchOnWindowFocus: false },
    });
  });
});
