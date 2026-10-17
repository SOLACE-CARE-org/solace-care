import { QueryClientProvider } from '@tanstack/react-query';
import { fireEvent, waitFor } from '@testing-library/react-native';
import { Slot } from 'expo-router';
import { renderRouter } from 'expo-router/testing-library';

import { queryClient } from '@/api/query-client';
import { resumeRoute, type SpecialistApplication } from '@/api/specialist';
import Account from '@/app/specialist-signup';
import Documents from '@/app/specialist-signup/documents';
import Payout from '@/app/specialist-signup/payout';
import Practice from '@/app/specialist-signup/practice';
import Resume from '@/app/specialist-signup/resume';
import Review from '@/app/specialist-signup/review';
import Verify from '@/app/verify';
import { useAuth } from '@/auth/auth';
import { initI18n } from '@/i18n';
import { useSession } from '@/state/session';
import { maskTime } from '@/state/signup';

function Layout() {
  return (
    <QueryClientProvider client={queryClient}>
      <Slot />
    </QueryClientProvider>
  );
}

const routes = {
  _layout: Layout,
  'specialist-signup/index': Account,
  'specialist-signup/resume': Resume,
  'specialist-signup/practice': Practice,
  'specialist-signup/documents': Documents,
  'specialist-signup/payout': Payout,
  'specialist-signup/review': Review,
  verify: Verify,
  '(specialist)/today': () => null,
};

type Call = { url: string; method: string; body: string };
let calls: Call[];
let answer: (call: Call) => { status: number; body?: unknown };

function stubNetwork() {
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
}

const pricing = { minPrice: 5000, maxPrice: 20000, feePercent: 20, durations: [30, 50, 80] };
const noSteps = {
  practice: false,
  languages: false,
  documents: false,
  services: false,
  hours: false,
  payout: false,
};

function application(patch: Partial<SpecialistApplication> = {}): SpecialistApplication {
  return {
    status: 'DRAFT',
    fullName: 'Ngo Ebode Clarisse',
    steps: noSteps,
    languages: [],
    documents: [],
    offerings: [],
    pricing,
    ...patch,
  };
}

async function signedIn() {
  await useAuth.getState().signedIn({
    accessToken: 'access',
    refreshToken: 'refresh',
    accessExpiresAt: Date.now() + 300_000,
  });
}

beforeEach(async () => {
  await initI18n('en');
  useSession.setState({ language: 'en', role: null });
  await useAuth.getState().signOut();
  queryClient.clear();
  stubNetwork();
});

describe('where an applicant resumes', () => {
  it('is the first unfinished step, or the review once submitted', () => {
    expect(resumeRoute(application())).toBe('/specialist-signup/practice');
    expect(
      resumeRoute(application({ steps: { ...noSteps, practice: true, languages: true } })),
    ).toBe('/specialist-signup/documents');
    expect(resumeRoute(application({ status: 'SUBMITTED' }))).toBe('/specialist-signup/review');
  });

  it('types hours as HH:MM', () => {
    expect(maskTime('0930')).toBe('09:30');
    expect(maskTime('9')).toBe('9');
    expect(maskTime('17:0')).toBe('17:0');
  });
});

describe('specialist sign-up', () => {
  it('asks for no password, starts the application, then asks Keycloak for a code', async () => {
    answer = ({ url }) =>
      url.endsWith('/v1/specialist-signups')
        ? { status: 202 }
        : { status: 401, body: { error: 'code_required' } };
    const screen = await renderRouter(routes, { initialUrl: '/specialist-signup' });

    expect(screen.getByLabelText('Step 1 of 7')).toBeTruthy();
    expect(screen.queryByText(/password/i)).toBeNull();
    await fireEvent.changeText(
      screen.getByLabelText('Full name, as on your licence'),
      'Ngo Ebode Clarisse',
    );
    await fireEvent.changeText(
      screen.getByLabelText('Professional email'),
      'c.ngoebode@example.com',
    );
    await fireEvent.changeText(screen.getByLabelText('Phone number, +237'), '699000000');
    expect(screen.getByRole('button', { name: 'Verify and continue' })).toBeDisabled();
    await fireEvent.press(screen.getByRole('checkbox'));
    await fireEvent.press(screen.getByRole('button', { name: 'Verify and continue' }));

    await waitFor(() => expect(screen.getPathname()).toBe('/verify'));
    expect(JSON.parse(calls[0].body)).toEqual({
      fullName: 'Ngo Ebode Clarisse',
      phone: '+237699000000',
      email: 'c.ngoebode@example.com',
      termsVersion: 'v1',
      codeOfConductVersion: 'v1',
      appLanguage: 'en',
    });
    expect(calls[1].url).toContain('/realms/solace-members/protocol/openid-connect/token');
  });

  it('signs an applicant in to their first unfinished step, not the specialist app', async () => {
    answer = ({ url, body }) => {
      if (url.includes('/token') && !body.includes('code=')) return { status: 401 };
      if (url.includes('/token'))
        return {
          status: 200,
          body: { access_token: 'access', refresh_token: 'refresh', expires_in: 300 },
        };
      if (url.endsWith('/v1/me'))
        return {
          status: 200,
          body: {
            id: '2c9b6f0e-8d41-4a7a-b3e5-5f0d9c1a7e64',
            role: 'SPECIALIST',
            displayName: 'Ngo Ebode Clarisse',
            appLanguage: 'en',
            onboarding: { preferences: true, privacy: true },
            applicationStatus: 'DRAFT',
          },
        };
      if (url.endsWith('/v1/me/specialist-application')) return { status: 200, body: application() };
      return { status: 200, body: [] };
    };
    const screen = await renderRouter(routes, {
      initialUrl: '/verify?contact=%2B237699000000&channel=phone',
    });

    await fireEvent.changeText(screen.getByLabelText('6-digit code'), '482913');
    await fireEvent.press(screen.getByRole('button', { name: 'Verify' }));

    await waitFor(() => expect(screen.getPathname()).toBe('/specialist-signup/practice'));
    expect(useSession.getState().role).toBe('applicant');
  });

  it('saves the payout details and submits the application', async () => {
    await signedIn();
    const almost = application({
      steps: { ...noSteps, practice: true, languages: true, documents: true, services: true, hours: true },
    });
    const done = { ...almost, steps: { ...almost.steps, payout: true } };
    answer = ({ url, method }) => {
      if (url.endsWith('/payout') && method === 'PUT') return { status: 200, body: done };
      if (url.endsWith('/submission'))
        return { status: 200, body: { ...done, status: 'SUBMITTED', submittedAt: new Date().toISOString() } };
      return { status: 200, body: almost };
    };
    const screen = await renderRouter(routes, { initialUrl: '/specialist-signup/payout' });

    await waitFor(() => expect(screen.getByLabelText('Account holder name')).toBeTruthy());
    // Bank transfer waits behind its flag (D26).
    expect(screen.queryByText('Bank transfer')).toBeNull();
    await fireEvent.press(screen.getByRole('radio', { name: 'Orange Money' }));
    await fireEvent.changeText(screen.getByLabelText('Mobile Money number, +237'), '699000001');
    await fireEvent.press(screen.getByRole('button', { name: 'Submit application' }));

    await waitFor(() => expect(screen.getPathname()).toBe('/specialist-signup/review'));
    const saved = calls.find((c) => c.method === 'PUT');
    expect(JSON.parse(saved!.body)).toEqual({
      method: 'ORANGE_MONEY',
      phone: '+237699000001',
      holderName: 'Ngo Ebode Clarisse',
    });
    expect(calls.some((c) => c.url.endsWith('/submission') && c.method === 'POST')).toBe(true);
  });
});

describe('application under review', () => {
  it('shows where the review stands, without the interview picker', async () => {
    await signedIn();
    answer = () => ({
      status: 200,
      body: application({
        status: 'UNDER_REVIEW',
        submittedAt: new Date().toISOString(),
      }),
    });
    const screen = await renderRouter(routes, { initialUrl: '/specialist-signup/review' });

    expect(screen.getByText('Application received')).toBeTruthy();
    expect(await screen.findByText('In progress')).toBeTruthy();
    expect(screen.getByText(/^Today, /)).toBeTruthy();
    // Staff book the interview (D25); the picker waits behind its flag.
    expect(screen.queryByRole('button', { name: 'Pick an interview time' })).toBeNull();
    expect(screen.getByRole('link', { name: 'Preview your dashboard' })).toBeTruthy();
  });
});
