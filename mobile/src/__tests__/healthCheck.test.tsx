import { QueryClientProvider } from '@tanstack/react-query';
import { fireEvent, waitFor } from '@testing-library/react-native';
import * as SecureStore from 'expo-secure-store';
import { Slot } from 'expo-router';
import { renderRouter } from 'expo-router/testing-library';

import { queryClient } from '@/api/query-client';
import HealthCheckIntro from '@/app/health-check';
import HealthCheckMatches from '@/app/health-check/matches';
import HealthCheckPreferencesScreen from '@/app/health-check/preferences';
import HealthCheckQuestion from '@/app/health-check/question';
import HealthCheckResultScreen from '@/app/health-check/result';
import HealthCheckUrgent from '@/app/health-check/urgent';
import { useAuth } from '@/auth/auth';
import { initI18n } from '@/i18n';
import { useHealthCheck } from '@/state/healthCheck';
import { useSession } from '@/state/session';

jest.setTimeout(30_000);

function Layout() {
  return (
    <QueryClientProvider client={queryClient}>
      <Slot />
    </QueryClientProvider>
  );
}

type Call = { method: string; path: string; body: string; token: string | null };
let calls: Call[];
let answers: Record<
  string,
  { status: number; body?: unknown } | ((body: string) => { status: number; body?: unknown })
>;

const form = {
  version: '1.0',
  priceBands: [15000, 25000],
  questions: [
    {
      code: 'topics',
      kind: 'CHOICE',
      multi: true,
      step: 1,
      text: 'What would you like help with?',
      options: [
        { code: 'worry', label: 'Worry or anxiety' },
        { code: 'stress', label: 'Stress at work or school' },
      ],
    },
    {
      code: 'safety',
      kind: 'SAFETY',
      multi: false,
      step: 2,
      text: 'In the last 2 weeks, have you had thoughts that you would be better off dead, or of hurting yourself in some way?',
      options: [
        { code: 'no', label: 'No' },
        { code: 'yes_sometimes', label: 'Yes, sometimes' },
      ],
    },
    {
      code: 'gad7_1',
      kind: 'SCREENER',
      multi: false,
      step: 2,
      text: 'Feeling nervous, anxious or on edge',
      intro: 'Over the last 2 weeks, how often have you been bothered by:',
      source: 'GAD-7',
      options: [
        { code: 'not_at_all', label: 'Not at all' },
        { code: 'nearly_every_day', label: 'Nearly every day' },
      ],
    },
  ],
};
const result = {
  responseId: 'r1',
  specialty: {
    code: 'clinical-psychology',
    label: 'Clinical psychology',
    person: 'Clinical psychologist',
  },
  alsoFit: { code: 'counselling', label: 'Counselling', person: 'Licensed counsellor' },
  reasons: ['You said worry is affecting your daily life most days.'],
  alsoFitReason: 'You said stress at work or school is part of it.',
  completedAt: '2026-10-18T10:00:00Z',
  stale: false,
  preferences: { sessionLanguage: 'en', modality: 'AUDIO' },
};

beforeEach(async () => {
  await initI18n('en');
  useHealthCheck.getState().reset();
  queryClient.clear();
  calls = [];
  answers = {
    'GET /v1/health-check': { status: 200, body: form },
    'POST /v1/health-check/responses': { status: 201, body: { id: 'r1', token: 'guest-token' } },
  };
  globalThis.fetch = jest.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const request = input instanceof Request ? input : new Request(String(input), init);
    const path = new URL(request.url).pathname.replace(/^.*\/api\/core/, '');
    const body = request.method === 'GET' ? '' : await request.clone().text();
    calls.push({
      method: request.method,
      path,
      body,
      token: request.headers.get('X-Health-Check-Token'),
    });
    const answer = answers[`${request.method} ${path}`];
    const { status, body: out } =
      typeof answer === 'function' ? answer(body) : (answer ?? { status: 404 });
    return new Response(out === undefined ? null : JSON.stringify(out), {
      status,
      headers: { 'Content-Type': 'application/json' },
    });
  }) as typeof fetch;
});

describe('Health check as a guest', () => {
  beforeEach(async () => {
    await useAuth.getState().signOut();
    useSession.setState({ language: 'en', role: null as never });
  });

  it('starts only after the consent is ticked, and keeps the guest token in secure storage', async () => {
    const screen = await renderRouter(
      {
        _layout: Layout,
        'health-check/index': HealthCheckIntro,
        'health-check/question': () => null,
      },
      { initialUrl: '/health-check' },
    );
    expect(await screen.findByText('Find the right kind of specialist')).toBeTruthy();
    const start = screen.getByRole('button', { name: 'Start' });
    expect(start.props.accessibilityState.disabled).toBe(true);
    expect(screen.getByText('Tick the box above to start.')).toBeTruthy();
    await fireEvent.press(screen.getByRole('checkbox'));
    await waitFor(() =>
      expect(screen.getByRole('button', { name: 'Start' }).props.accessibilityState.disabled).toBe(
        false,
      ),
    );
    await fireEvent.press(screen.getByRole('button', { name: 'Start' }));
    await waitFor(() => expect(screen).toHavePathname('/health-check/question'));
    expect(JSON.parse(calls.find((c) => c.path === '/v1/health-check/responses')!.body)).toEqual({
      consentVersion: 'v1',
    });
    expect(await SecureStore.getItemAsync('solace.health-check.guest')).toBe(
      JSON.stringify({ id: 'r1', token: 'guest-token' }),
    );
    expect(useHealthCheck.getState().token).toBe('guest-token');
  });

  it('asks what Core says comes next and opens urgent help on the safety answer', async () => {
    useHealthCheck
      .getState()
      .begin({
        responseId: 'r1',
        token: 'guest-token',
        questions: form.questions as never,
        priceBands: form.priceBands,
      });
    answers['PUT /v1/health-check/responses/r1/answers'] = (body) => {
      const sent = JSON.parse(body).answers as Record<string, string[]>;
      return sent.safety?.includes('yes_sometimes')
        ? { status: 200, body: { status: 'SAFETY_STOP', next: ['gad7_1'] } }
        : { status: 200, body: { status: 'IN_PROGRESS', next: ['safety', 'gad7_1'] } };
    };
    answers['POST /v1/health-check/responses/r1/callback'] = { status: 202 };
    const screen = await renderRouter(
      {
        _layout: Layout,
        'health-check/question': HealthCheckQuestion,
        'health-check/urgent': HealthCheckUrgent,
      },
      { initialUrl: '/health-check/question' },
    );
    expect(await screen.findByText('What would you like help with?')).toBeTruthy();
    expect(screen.getByText('Choose all that apply.')).toBeTruthy();
    await fireEvent.press(screen.getByText('Worry or anxiety'));
    await fireEvent.press(screen.getByText('Stress at work or school'));
    await fireEvent.press(screen.getByRole('button', { name: 'Next' }));
    expect(await screen.findByText(/better off dead/)).toBeTruthy();
    const first = calls.find((c) => c.path === '/v1/health-check/responses/r1/answers')!;
    expect(first.token).toBe('guest-token');
    expect(JSON.parse(first.body)).toEqual({ answers: { topics: ['worry', 'stress'] } });

    await fireEvent.press(screen.getByText('Yes, sometimes'));
    await fireEvent.press(screen.getByRole('button', { name: 'Next' }));
    expect(
      await screen.findByText("Thank you for telling us. You don't have to wait."),
    ).toBeTruthy();
    expect(screen.getByRole('button', { name: /Call 119/ })).toBeTruthy();
    await fireEvent.changeText(screen.getByLabelText('Phone number'), '+237 670 000 904');
    await fireEvent.press(screen.getByRole('button', { name: 'Call me back' }));
    expect(
      await screen.findByText('Thank you. A Solace specialist on call will call you back.'),
    ).toBeTruthy();
    expect(JSON.parse(calls.find((c) => c.path.endsWith('/callback'))!.body)).toEqual({
      phone: '+237670000904',
    });
  });

  it('shows the suggestion with its reasons and never a score', async () => {
    useHealthCheck
      .getState()
      .begin({
        responseId: 'r1',
        token: 'guest-token',
        questions: form.questions as never,
        priceBands: form.priceBands,
      });
    answers['POST /v1/health-check/responses/r1/completion'] = { status: 200, body: result };
    const screen = await renderRouter(
      {
        _layout: Layout,
        'health-check/preferences': HealthCheckPreferencesScreen,
        'health-check/result': HealthCheckResultScreen,
      },
      { initialUrl: '/health-check/preferences' },
    );
    expect(await screen.findByText('A few preferences')).toBeTruthy();
    expect(screen.getByText('Up to 15,000 FCFA')).toBeTruthy();
    await fireEvent.press(screen.getByText('Video'));
    await fireEvent.press(screen.getByRole('button', { name: 'See my suggestion' }));
    expect(await screen.findByText('A clinical psychologist is a good fit')).toBeTruthy();
    expect(JSON.parse(calls.find((c) => c.path.endsWith('/completion'))!.body)).toEqual({
      modality: 'VIDEO',
    });
    expect(
      screen.getByText('• You said worry is affecting your daily life most days.'),
    ).toBeTruthy();
    expect(screen.getByText('Licensed counsellor')).toBeTruthy();
    expect(
      screen.getByText(
        'This is not a diagnosis. The specialist you book will talk it through with you.',
      ),
    ).toBeTruthy();
    expect(
      screen.getByRole('button', { name: 'Keep my answers: create a free account' }),
    ).toBeTruthy();
  });
});

describe('Health check matches for a seeker', () => {
  beforeEach(async () => {
    useSession.setState({ language: 'en', role: 'seeker' });
    await useAuth
      .getState()
      .signedIn({
        accessToken: 'access',
        refreshToken: 'refresh',
        accessExpiresAt: Date.now() + 300_000,
      });
  });

  it('books with the answers shared only when ticked', async () => {
    useHealthCheck
      .getState()
      .begin({
        responseId: 'r1',
        token: null,
        questions: form.questions as never,
        priceBands: form.priceBands,
      });
    useHealthCheck.getState().setPreferences({ sessionLanguage: 'en', modality: 'AUDIO' });
    useHealthCheck.getState().finish(result as never);
    answers['GET /v1/health-check/responses/r1/matches'] = {
      status: 200,
      body: [
        {
          id: 'sp-1',
          fullName: 'Dr. Ngo Ebode Clarisse',
          professionLabel: 'Clinical psychologist',
          languages: ['en', 'fr'],
          specialty: 'clinical-psychology',
          alsoFit: false,
          focusAreas: ['Anxiety', 'Sleep'],
        },
      ],
    };
    const screen = await renderRouter(
      { _layout: Layout, 'health-check/matches': HealthCheckMatches, 'book/[id]': () => null },
      { initialUrl: '/health-check/matches' },
    );
    expect(await screen.findByText('Dr. Ngo Ebode Clarisse')).toBeTruthy();
    expect(screen.getByText('English · Audio · Any price')).toBeTruthy();
    expect(screen.getByText('Works with anxiety, sleep')).toBeTruthy();
    await fireEvent.press(
      screen.getByRole('switch', { name: 'Share my answers with the specialist I book' }),
    );
    await fireEvent.press(screen.getByText('Dr. Ngo Ebode Clarisse'));
    expect(screen).toHavePathname('/book/sp-1');
    expect(screen).toHaveSearchParams({
      id: 'sp-1',
      healthCheckResponseId: 'r1',
      shareHealthCheck: '1',
    });
  });
});
