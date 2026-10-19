import { QueryClientProvider } from '@tanstack/react-query';
import { fireEvent, waitFor } from '@testing-library/react-native';
import { Slot } from 'expo-router';
import { renderRouter } from 'expo-router/testing-library';

import { queryClient } from '@/api/query-client';
import SpecialistNotes from '@/app/(specialist)/notes';
import CompanionScreen from '@/app/homework/[id]';
import NoteReview from '@/app/note/[id]';
import SafetyPlanScreen from '@/app/safety-plan';
import { useAuth } from '@/auth/auth';
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
let offline = false;

const past = {
  id: 'b-1',
  status: 'COMPLETED',
  specialist: { id: 's-1', fullName: 'Dr. Ngo Ebode' },
  seeker: { label: 'Awa N.' },
  start: '2026-10-19T09:00:00Z',
  end: '2026-10-19T09:50:00Z',
  modality: 'AUDIO',
  sessionLanguage: 'en',
  price: 15000,
  reschedulesLeft: 0,
};
const draft = {
  id: 'n-1',
  bookingId: 'b-1',
  status: 'DRAFT',
  format: 'SOAP',
  sections: [
    { key: 'S', text: 'Reports better sleep since last session.' },
    { key: 'O', text: 'Engaged and reflective.' },
    { key: 'A', text: '' },
    { key: 'P', text: 'One thought record before each team meeting.' },
  ],
  aiDrafted: true,
  riskSignals: false,
  canRedraft: true,
  createdAt: '2026-10-19T10:00:00Z',
};

beforeEach(async () => {
  await initI18n('en');
  useSession.setState({ language: 'en', role: 'specialist' });
  useSafetyPlan.setState({ plan: undefined });
  await useAuth.getState().signedIn({
    accessToken: 'access',
    refreshToken: 'refresh',
    accessExpiresAt: Date.now() + 300_000,
  });
  queryClient.clear();
  queryClient.setDefaultOptions({ queries: { retry: false } });
  calls = [];
  offline = false;
  answers = {
    'GET /v1/me/bookings': { status: 200, body: [past] },
    'GET /v1/catalogue/session-languages': { status: 200, body: [{ code: 'en', name: 'English' }] },
    'GET /v1/specialist/notes': {
      status: 200,
      body: [
        {
          id: 'n-1',
          bookingId: 'b-1',
          status: 'DRAFT',
          format: 'SOAP',
          preview: 'Reports better sleep since last session.',
          createdAt: '2026-10-19T10:00:00Z',
        },
      ],
    },
    'GET /v1/specialist/notes/n-1': { status: 200, body: draft },
  };
  globalThis.fetch = jest.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const request = input instanceof Request ? input : new Request(String(input), init);
    if (offline) throw new TypeError('Network request failed');
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

afterAll(() => {
  queryClient.setDefaultOptions({
    queries: { retry: 1, staleTime: 30_000, refetchOnWindowFocus: false },
  });
});

describe('Session notes', () => {
  it('lists drafts to review with the seeker and an AI draft label', async () => {
    const screen = await renderRouter(
      { _layout: Layout, index: SpecialistNotes },
      { initialUrl: '/' },
    );
    expect(await screen.findByText('Awa N.')).toBeTruthy();
    expect(screen.getByText('AI draft')).toBeTruthy();
    expect(screen.getByText('Reports better sleep since last session.')).toBeTruthy();
    expect(
      screen.getByText(
        'Drafts are deleted after 7 days if not signed, along with the session audio.',
      ),
    ).toBeTruthy();
  });

  it('waits for the assessment before signing, then saves and signs', async () => {
    answers['PUT /v1/specialist/notes/n-1'] = {
      status: 200,
      body: {
        ...draft,
        sections: draft.sections.map((s) => (s.key === 'A' ? { ...s, text: 'Progressing.' } : s)),
      },
    };
    answers['POST /v1/specialist/notes/n-1/signature'] = {
      status: 200,
      body: { ...draft, status: 'SIGNED', canRedraft: false, signedAt: '2026-10-19T11:00:00Z' },
    };
    const screen = await renderRouter(
      { _layout: Layout, 'note/[id]': NoteReview },
      { initialUrl: '/note/n-1' },
    );
    expect(await screen.findByText('Review note draft')).toBeTruthy();
    expect(screen.getByText('No risk signals flagged')).toBeTruthy();
    expect(screen.getByRole('button', { name: 'Sign note' })).toBeDisabled();

    await fireEvent.changeText(screen.getByLabelText('Assessment'), 'Progressing.');
    await fireEvent.press(screen.getByRole('button', { name: 'Sign note' }));
    await waitFor(() =>
      expect(calls.map((c) => `${c.method} ${c.path}`)).toEqual(
        expect.arrayContaining([
          'PUT /v1/specialist/notes/n-1',
          'POST /v1/specialist/notes/n-1/signature',
        ]),
      ),
    );
    expect(await screen.findByText('Session note')).toBeTruthy();
  });
});

describe('Companion', () => {
  it('asks the next step and opens urgent help on a risk signal', async () => {
    useSession.setState({ language: 'en', role: 'seeker' });
    answers['GET /v1/me/homework'] = {
      status: 200,
      body: [
        {
          id: 'h-1',
          bookingId: 'b-1',
          exercise: 'THOUGHT_RECORD',
          frequency: 'TIMES',
          target: 3,
          done: 2,
          companionGuided: true,
          dueBy: '2026-10-26T09:00:00Z',
          createdAt: '2026-10-19T10:00:00Z',
        },
      ],
    };
    answers['GET /v1/me/homework/h-1/companion'] = {
      status: 200,
      body: {
        step: 'SITUATION',
        prompt: "Let's do your third thought record. What happened that upset you today?",
        record: {},
        done: 2,
        target: 3,
        finished: false,
        crisis: false,
      },
    };
    answers['POST /v1/me/homework/h-1/companion'] = {
      status: 200,
      body: {
        step: 'DONE',
        prompt: 'Urgent help is one tap away.',
        record: {},
        done: 2,
        target: 3,
        finished: false,
        crisis: true,
      },
    };
    const screen = await renderRouter(
      { _layout: Layout, 'homework/[id]': CompanionScreen, crisis: () => null },
      { initialUrl: '/homework/h-1' },
    );
    expect(
      await screen.findByText(
        "Let's do your third thought record. What happened that upset you today?",
      ),
    ).toBeTruthy();
    expect(await screen.findByText('Thought record · 2 of 3 done')).toBeTruthy();
    await fireEvent.changeText(screen.getByLabelText('Your answer'), 'Something happened.');
    await fireEvent.press(screen.getByRole('button', { name: 'Send' }));
    await waitFor(() => expect(screen).toHavePathname('/crisis'));
  });
});

describe('Safety plan', () => {
  it('shows the plan kept on the phone with no connection, with help always last', async () => {
    useSafetyPlan.setState({
      plan: {
        warningSigns: 'Not sleeping, skipping meals',
        calming: 'A walk outside',
        people: [{ relation: 'Sister', name: 'Estelle', phone: '+237670000111' }],
        madeWith: { id: 's-1', name: 'Dr. Ngo Ebode' },
        updatedAt: '2026-10-19T10:00:00Z',
      },
    });
    offline = true;
    const screen = await renderRouter(
      { _layout: Layout, index: SafetyPlanScreen },
      { initialUrl: '/' },
    );
    expect(await screen.findByText('Not sleeping, skipping meals')).toBeTruthy();
    expect(screen.getByText('Sister · Estelle')).toBeTruthy();
    expect(screen.getByRole('button', { name: 'Call Estelle' })).toBeTruthy();
    expect(screen.getByText(/Made with Dr. Ngo Ebode/)).toBeTruthy();
    expect(screen.getByRole('button', { name: 'I need help now' })).toBeTruthy();
  });

  it('saves a new plan', async () => {
    answers['GET /v1/me/safety-plan'] = { status: 204 };
    answers['PUT /v1/me/safety-plan'] = {
      status: 200,
      body: {
        warningSigns: 'Not sleeping',
        calming: 'Music',
        people: [],
        updatedAt: '2026-10-19T10:00:00Z',
      },
    };
    const screen = await renderRouter(
      { _layout: Layout, index: SafetyPlanScreen },
      { initialUrl: '/' },
    );
    await fireEvent.press(await screen.findByRole('button', { name: 'Make my plan' }));
    await fireEvent.changeText(screen.getByLabelText('My warning signs'), 'Not sleeping');
    await fireEvent.changeText(screen.getByLabelText('Things that calm me'), 'Music');
    await fireEvent.press(screen.getByRole('button', { name: 'Save' }));
    expect(await screen.findByText('Not sleeping')).toBeTruthy();
    expect(calls).toContainEqual({
      method: 'PUT',
      path: '/v1/me/safety-plan',
      body: JSON.stringify({ warningSigns: 'Not sleeping', calming: 'Music', people: [] }),
    });
  });
});
