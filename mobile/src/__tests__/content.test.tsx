import { QueryClientProvider } from '@tanstack/react-query';
import { fireEvent, waitFor } from '@testing-library/react-native';
import { Slot } from 'expo-router';
import { renderRouter } from 'expo-router/testing-library';

import { queryClient } from '@/api/query-client';
import LegalText from '@/app/legal/[doc]';
import { useAuth } from '@/auth/auth';
import { EMERGENCY_NUMBERS, refreshEmergencyNumbers, useEmergencyNumbers } from '@/content/crisis';
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

const routes = { _layout: Layout, 'legal/[doc]': LegalText, index: () => null };

type Call = { url: string; method: string; body: string };
let calls: Call[];
let answers: Record<string, { status: number; body?: unknown }>;

beforeEach(async () => {
  await initI18n('en');
  useSession.setState({ language: 'en', role: 'seeker' });
  await useAuth.getState().signedIn({
    accessToken: 'access',
    refreshToken: 'refresh',
    accessExpiresAt: Date.now() + 300_000,
  });
  queryClient.clear();
  calls = [];
  answers = {};
  globalThis.fetch = jest.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
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

describe('Legal texts', () => {
  it('says a text is being prepared until it is published', async () => {
    const screen = await renderRouter(routes, { initialUrl: '/legal/terms' });
    expect(await screen.findByText(/This document is being prepared/)).toBeTruthy();
  });

  it('asks a seeker to accept a newer version and records it', async () => {
    answers['GET /v1/content/terms'] = {
      status: 200,
      body: {
        slug: 'terms',
        type: 'LEGAL',
        version: 'v2',
        language: 'en',
        title: 'Terms of use',
        body: 'Be kind.',
      },
    };
    answers['GET /v1/me'] = {
      status: 200,
      body: {
        id: '2c9b6f0e-8d41-4a7a-b3e5-5f0d9c1a7e64',
        role: 'SEEKER',
        displayName: 'Ama',
        appLanguage: 'en',
        onboarding: { preferences: true, privacy: true },
        legalToAccept: [{ slug: 'terms', version: 'v2' }],
      },
    };
    answers['POST /v1/me/legal-acceptance'] = { status: 204 };
    const screen = await renderRouter(routes, { initialUrl: '/legal/terms?accept=1' });

    expect(await screen.findByText('Terms of use')).toBeTruthy();
    expect(screen.getByText('Version v2')).toBeTruthy();
    const accept = await screen.findByRole('button', { name: 'I accept' });
    await waitFor(() => expect(accept.props.accessibilityState?.disabled).toBeFalsy());
    await fireEvent.press(accept);
    await waitFor(() =>
      expect(calls.some((c) => c.url.endsWith('/v1/me/legal-acceptance'))).toBe(true),
    );
    const sent = calls.find((c) => c.url.endsWith('/v1/me/legal-acceptance'))!;
    expect(JSON.parse(sent.body)).toEqual({ accepted: [{ slug: 'terms', version: 'v2' }] });
  });
});

describe('Emergency numbers', () => {
  beforeEach(() => useEmergencyNumbers.setState({ numbers: EMERGENCY_NUMBERS }));

  it('keeps the bundled numbers offline and takes the published ones when it can', async () => {
    await refreshEmergencyNumbers(async () => {
      throw new Error('offline');
    });
    expect(useEmergencyNumbers.getState().numbers).toEqual(EMERGENCY_NUMBERS);

    await refreshEmergencyNumbers(async () => [
      { service: 'police', number: '117' },
      { service: 'helpline', number: '1510' },
    ]);
    expect(useEmergencyNumbers.getState().numbers.map((n) => n.number)).toEqual(['117', '1510']);

    // An empty list never leaves Urgent help without numbers.
    await refreshEmergencyNumbers(async () => []);
    expect(useEmergencyNumbers.getState().numbers).toEqual(EMERGENCY_NUMBERS);
  });
});
