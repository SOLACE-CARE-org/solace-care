import { QueryClientProvider } from '@tanstack/react-query';
import { fireEvent, waitFor } from '@testing-library/react-native';
import { Slot } from 'expo-router';
import { renderRouter } from 'expo-router/testing-library';
import * as Sharing from 'expo-sharing';
import { Alert } from 'react-native';

import { queryClient } from '@/api/query-client';
import PrivacyCentre from '@/app/privacy';
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

const routes = {
  _layout: Layout,
  privacy: PrivacyCentre,
  'legal/[doc]': () => null,
  welcome: () => null,
};

type Call = { url: string; method: string; body: string };
let calls: Call[];
let answer: (call: Call) => { status: number; body?: unknown; text?: string } | undefined;

const consents = {
  consents: [
    { purpose: 'AI_ASSIST', granted: false, version: 'v1' },
    { purpose: 'AI_SCRIBE', granted: true, version: 'v1' },
  ],
};

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
  globalThis.fetch = jest.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const request = input instanceof Request ? input : new Request(String(input), init);
    const call = {
      url: request.url,
      method: request.method,
      body: request.method === 'GET' ? '' : await request.clone().text(),
    };
    calls.push(call);
    if (call.url.endsWith('/v1/me/consents') && call.method === 'GET') {
      return new Response(JSON.stringify(consents), {
        status: 200,
        headers: { 'Content-Type': 'application/json' },
      });
    }
    const { status, body, text } = answer(call) ?? { status: 404 };
    if (text !== undefined) {
      return new Response(text, { status, headers: { 'Content-Type': 'application/json' } });
    }
    return new Response(body === undefined ? null : JSON.stringify(body), {
      status,
      headers: { 'Content-Type': 'application/problem+json' },
    });
  }) as typeof fetch;
});

/** Press the destructive button of the last Alert shown. */
function confirmAlert() {
  const spy = Alert.alert as jest.Mock;
  const buttons = spy.mock.calls.at(-1)?.[2] as { style?: string; onPress?: () => void }[];
  buttons.find((b) => b.style === 'destructive')?.onPress?.();
}

beforeAll(() => {
  jest.spyOn(Alert, 'alert').mockImplementation(() => undefined);
});

describe('Privacy & data', () => {
  it('shows only the purposes Core offers and saves a change', async () => {
    answer = (call) =>
      call.method === 'PUT'
        ? {
            status: 200,
            body: {
              consents: [
                { purpose: 'AI_ASSIST', granted: true, version: 'v1' },
                consents.consents[1],
              ],
            },
          }
        : undefined;
    const screen = await renderRouter(routes, { initialUrl: '/privacy' });

    expect(await screen.findByText('AI assistance')).toBeTruthy();
    expect(screen.getByText('AI session notes')).toBeTruthy();
    expect(screen.queryByText('Health tracking')).toBeNull();

    await fireEvent.press(screen.getByRole('switch', { name: 'AI assistance' }));
    await waitFor(() => expect(calls.some((c) => c.method === 'PUT')).toBe(true));
    const put = calls.find((c) => c.method === 'PUT')!;
    expect(JSON.parse(put.body)).toEqual({ choices: [{ purpose: 'AI_ASSIST', granted: true }] });
  });

  it('asks for a copy, then saves and shares it when ready', async () => {
    const ready = {
      id: 'x1',
      status: 'READY',
      requestedAt: new Date().toISOString(),
      expiresAt: '2026-10-25T10:00:00Z',
    };
    answer = (call) => {
      if (call.url.endsWith('/v1/me/data-exports') && call.method === 'POST') {
        return { status: 202, text: JSON.stringify(ready) };
      }
      if (call.url.endsWith('/v1/me/data-exports/x1/file')) {
        return { status: 200, text: '{"account":{}}' };
      }
      return undefined;
    };
    const screen = await renderRouter(routes, { initialUrl: '/privacy' });

    await fireEvent.press(
      await screen.findByRole('button', { name: 'Download a copy of my data' }),
    );
    expect(
      await screen.findByText(/Your copy is ready until (25 October|October 25)./),
    ).toBeTruthy();

    await fireEvent.press(screen.getByRole('button', { name: 'Save the copy' }));
    await waitFor(() => expect(Sharing.shareAsync).toHaveBeenCalled());
    expect((Sharing.shareAsync as jest.Mock).mock.calls[0][0]).toBe(
      'file:///cache/solace-data.json',
    );
  });

  it('deletes a seeker account after confirming, then signs out', async () => {
    answer = (call) =>
      call.url.endsWith('/v1/me/erasure')
        ? { status: 202, text: JSON.stringify({ outcome: 'ERASED' }) }
        : undefined;
    const screen = await renderRouter(routes, { initialUrl: '/privacy' });

    await fireEvent.press(
      await screen.findByRole('button', { name: 'Delete my account and data' }),
    );
    expect(Alert.alert).toHaveBeenCalledWith(
      'Delete your account?',
      expect.any(String),
      expect.any(Array),
    );
    confirmAlert();

    await waitFor(() => expect(screen.getPathname()).toBe('/welcome'));
    const erase = calls.find((c) => c.url.endsWith('/v1/me/erasure'))!;
    expect(JSON.parse(erase.body)).toEqual({ confirm: true });
    expect(useAuth.getState().status).toBe('signedOut');
  });

  it('explains why deleting has to wait', async () => {
    answer = (call) =>
      call.url.endsWith('/v1/me/erasure')
        ? {
            status: 409,
            body: {
              code: 'privacy.upcoming-sessions',
              title: 'Cancel or finish your upcoming sessions first.',
              status: 409,
            },
          }
        : undefined;
    const screen = await renderRouter(routes, { initialUrl: '/privacy' });

    await fireEvent.press(
      await screen.findByRole('button', { name: 'Delete my account and data' }),
    );
    confirmAlert();

    expect(await screen.findByText('Cancel or finish your upcoming sessions first.')).toBeTruthy();
    expect(screen.getPathname()).toBe('/privacy');
  });
});
