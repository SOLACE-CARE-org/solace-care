import { QueryClientProvider } from '@tanstack/react-query';
import { fireEvent, waitFor } from '@testing-library/react-native';
import { Slot } from 'expo-router';
import { renderRouter } from 'expo-router/testing-library';
import * as SecureStore from 'expo-secure-store';

import { queryClient } from '@/api/query-client';
import RateSession from '@/app/rate/[id]';
import Reviews from '@/app/reviews';
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
  'rate/[id]': RateSession,
  reviews: Reviews,
  'book/[id]': () => null,
  'session/[id]': () => null,
  '(seeker)/sessions': () => null,
};

type Call = { url: string; method: string; body: string };
let calls: Call[];
let answer: (call: Call) => { status: number; body?: unknown } | undefined;

const bookingId = '0b1c2d3e-4f50-4a7a-b3e5-5f0d9c1a7e64';

beforeEach(async () => {
  await initI18n('en');
  useSession.setState({ language: 'en', role: 'seeker' });
  await useAuth.getState().signedIn({
    accessToken: 'access',
    refreshToken: 'refresh',
    accessExpiresAt: Date.now() + 300_000,
  });
  await SecureStore.deleteItemAsync('solace.guestBookings');
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
    const languages = call.url.includes('/session-languages')
      ? {
          status: 200,
          body: [
            { code: 'en', name: 'English' },
            { code: 'fr', name: 'Français' },
          ],
        }
      : undefined;
    const { status, body } = languages ?? answer(call) ?? { status: 200, body: [] };
    return new Response(body === undefined ? null : JSON.stringify(body), {
      status,
      headers: { 'Content-Type': 'application/json' },
    });
  }) as typeof fetch;
});

describe('Rate your session', () => {
  it('needs stars, then sends what stood out, the review and the private note', async () => {
    answer = ({ url, method }) => {
      if (url.endsWith('/rating') && method === 'POST') return { status: 201 };
      if (url.endsWith(`/v1/bookings/${bookingId}`))
        return {
          status: 200,
          body: {
            id: bookingId,
            status: 'COMPLETED',
            specialist: { id: 's1', fullName: 'Dr. Ngo Ebode' },
            start: new Date(Date.now() - 3_600_000).toISOString(),
            end: new Date(Date.now() - 600_000).toISOString(),
            modality: 'AUDIO',
            sessionLanguage: 'en',
            price: 15000,
            reschedulesLeft: 0,
            rated: false,
          },
        };
      return undefined;
    };
    const screen = await renderRouter(routes, { initialUrl: `/rate/${bookingId}` });

    expect(await screen.findByText('How was your session with Dr. Ngo Ebode?')).toBeTruthy();
    expect(screen.getByRole('button', { name: 'Submit' })).toBeDisabled();
    await fireEvent.press(screen.getByRole('radio', { name: '4 stars' }));
    await fireEvent.press(screen.getByRole('checkbox', { name: 'Felt listened to' }));
    await fireEvent.press(screen.getByRole('checkbox', { name: 'On time' }));
    await fireEvent.changeText(screen.getByLabelText('Write a review'), 'Patient and practical.');
    await fireEvent.press(
      screen.getByRole('button', { name: 'Something went wrong? Tell the Solace team privately' }),
    );
    await fireEvent.changeText(
      screen.getByLabelText('For the Solace team only'),
      'It started late.',
    );
    await fireEvent.press(screen.getByRole('button', { name: 'Submit' }));

    expect(
      await screen.findByText('Thank you. Your review will appear once Solace has checked it.'),
    ).toBeTruthy();
    expect(JSON.parse(calls.find((c) => c.method === 'POST')!.body)).toEqual({
      stars: 4,
      tags: ['LISTENED', 'ON_TIME'],
      text: 'Patient and practical.',
      privateNote: 'It started late.',
    });
  });
});

describe('Reviews', () => {
  it('shows the average and lets the specialist reply once', async () => {
    useSession.setState({ role: 'specialist' });
    let replied = false;
    const review = {
      id: 'r1',
      stars: 5,
      text: 'Patient and practical.',
      at: new Date('2026-10-13T10:00:00Z').toISOString(),
      sessionLanguage: 'en',
    };
    answer = ({ url, method }) => {
      if (url.endsWith('/reply') && method === 'PUT') {
        replied = true;
        return { status: 200, body: { ...review, reply: 'Thank you.' } };
      }
      if (url.endsWith('/v1/me/reviews'))
        return {
          status: 200,
          body: {
            rating: { average: 4.8, count: 23 },
            items: [replied ? { ...review, reply: 'Thank you.' } : review],
          },
        };
      return undefined;
    };
    const screen = await renderRouter(routes, { initialUrl: '/reviews' });

    expect(await screen.findByText('4.8')).toBeTruthy();
    expect(screen.getByText('From 23 rated sessions')).toBeTruthy();
    expect(screen.getByText('Patient and practical.')).toBeTruthy();

    await fireEvent.press(screen.getByRole('button', { name: 'Reply publicly' }));
    await fireEvent.changeText(screen.getByLabelText('Your public reply'), 'Thank you.');
    await fireEvent.press(screen.getByRole('button', { name: 'Post reply' }));

    await waitFor(() => expect(screen.getByText('Your reply')).toBeTruthy());
    expect(screen.queryByRole('button', { name: 'Reply publicly' })).toBeNull();
  });
});
