import { QueryClientProvider } from '@tanstack/react-query';
import { fireEvent, waitFor } from '@testing-library/react-native';
import { Slot } from 'expo-router';
import { renderRouter } from 'expo-router/testing-library';
import * as SecureStore from 'expo-secure-store';

import { guestTokenFor } from '@/api/bookings';
import { queryClient } from '@/api/query-client';
import Sessions from '@/app/(seeker)/sessions';
import Checkout from '@/app/book/checkout';
import GuestJoin from '@/app/guest/[token]';
import SessionDetail from '@/app/session/[id]';
import ChangeSession from '@/app/session/change';
import { useAuth } from '@/auth/auth';
import { initI18n } from '@/i18n';
import { useSession } from '@/state/session';

// The first render of these screens is slow on a busy machine.
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
  '(seeker)/sessions': Sessions,
  'book/checkout': Checkout,
  'book/[id]': () => null,
  'book/pay': () => null,
  'session/[id]': SessionDetail,
  'session/change': ChangeSession,
  'guest/[token]': GuestJoin,
  search: () => null,
  'specialist/[id]': () => null,
};

type Call = { url: string; method: string; body: string; headers: Headers };
let calls: Call[];
let answer: (call: Call) => { status: number; body?: unknown } | undefined;

beforeEach(async () => {
  await initI18n('en');
  useSession.setState({ language: 'en', role: null });
  await useAuth.getState().signOut();
  await SecureStore.deleteItemAsync('solace.guestBookings');
  queryClient.clear();
  calls = [];
  globalThis.fetch = jest.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const request = input instanceof Request ? input : new Request(String(input), init);
    const call = {
      url: request.url,
      method: request.method,
      body: request.method === 'GET' ? '' : await request.clone().text(),
      headers: request.headers,
    };
    calls.push(call);
    const { status, body } = answer(call) ?? shared(call.url);
    return new Response(body === undefined ? null : JSON.stringify(body), {
      status,
      headers: { 'Content-Type': 'application/json' },
    });
  }) as typeof fetch;
});

async function seeker() {
  await useAuth.getState().signedIn({
    accessToken: 'access',
    refreshToken: 'refresh',
    accessExpiresAt: Date.now() + 300_000,
  });
  useSession.setState({ role: 'seeker' });
}

const specialistId = '2c9b6f0e-8d41-4a7a-b3e5-5f0d9c1a7e64';
const bookingId = '0b1c2d3e-4f50-4a7a-b3e5-5f0d9c1a7e64';
const holdId = '7f9d1c2e-1111-4a7a-b3e5-5f0d9c1a7e64';
const inDays = (days: number, hour = 10) => {
  const d = new Date();
  d.setDate(d.getDate() + days);
  d.setHours(hour, 0, 0, 0);
  return d.toISOString();
};

function booking(patch: Record<string, unknown> = {}) {
  return {
    id: bookingId,
    status: 'CONFIRMED',
    specialist: {
      id: specialistId,
      fullName: 'Ngo Ebode Clarisse',
      professionLabel: 'Clinical psychologist',
    },
    start: inDays(3),
    end: inDays(3, 11),
    modality: 'AUDIO',
    sessionLanguage: 'en',
    price: 15000,
    reschedulesLeft: 2,
    sessionNumber: 5,
    ...patch,
  };
}

function shared(url: string): { status: number; body?: unknown } {
  if (url.includes('/session-languages'))
    return {
      status: 200,
      body: [
        { code: 'en', name: 'English' },
        { code: 'fr', name: 'Français' },
      ],
    };
  if (url.includes(`/v1/catalogue/specialists/${specialistId}`))
    return {
      status: 200,
      body: {
        id: specialistId,
        fullName: 'Ngo Ebode Clarisse',
        bio: 'Bio',
        languages: [{ code: 'en', level: 'NATIVE' }],
        offerings: [{ modality: 'AUDIO', durationMinutes: 50, price: 15000 }],
        focusAreas: [],
      },
    };
  return { status: 200, body: [] };
}

const checkoutUrl = () =>
  `/book/checkout?holdId=${holdId}&specialistId=${specialistId}&start=${encodeURIComponent(inDays(3))}` +
  `&end=${encodeURIComponent(inDays(3, 11))}&expiresAt=${encodeURIComponent(new Date(Date.now() + 600_000).toISOString())}` +
  `&modality=AUDIO&language=en`;

describe('Checkout', () => {
  it('lets a guest prove their phone with a code, then books', async () => {
    answer = ({ url, method }) => {
      if (url.endsWith('/v1/guest/codes') && method === 'POST') return { status: 202 };
      if (url.endsWith('/v1/guest/booking/payments') && method === 'POST')
        return {
          status: 201,
          body: {
            id: 'p1',
            bookingId,
            method: 'MTN_MOMO',
            amount: 15000,
            status: 'PENDING',
            phone: '+237677000000',
            createdAt: new Date().toISOString(),
          },
        };
      if (url.endsWith('/v1/guest/bookings') && method === 'POST')
        return {
          status: 201,
          body: {
            booking: booking({ status: 'PENDING_PAYMENT' }),
            guestToken: 'guest-token',
            expiresAt: inDays(4),
          },
        };
      if (url.endsWith('/v1/guest/booking'))
        return { status: 200, body: booking({ status: 'PENDING_PAYMENT' }) };
      return undefined;
    };
    const screen = await renderRouter(routes, { initialUrl: checkoutUrl() });

    expect(await screen.findByText('Where should we reach you?')).toBeTruthy();
    expect(await screen.findByRole('button', { name: 'Pay 15,000 FCFA' })).toBeDisabled();
    await fireEvent.changeText(screen.getByLabelText('Phone number, +237'), '677000000');
    await fireEvent.press(screen.getByRole('button', { name: 'Send code' }));
    expect(await screen.findByText('We sent a code to +237 6 77 00 00 00.')).toBeTruthy();
    expect(JSON.parse(calls.find((c) => c.url.endsWith('/v1/guest/codes'))!.body)).toEqual({
      phone: '+237677000000',
      appLanguage: 'en',
    });

    await fireEvent.changeText(screen.getByLabelText('Enter the 6-digit code we sent'), '482051');
    await fireEvent.press(screen.getByRole('button', { name: 'Pay 15,000 FCFA' }));

    await waitFor(() => expect(screen.getPathname()).toBe('/book/pay'));
    const made = JSON.parse(calls.find((c) => c.url.endsWith('/v1/guest/bookings'))!.body);
    expect(made).toEqual({
      holdId,
      sessionLanguage: 'en',
      phone: '+237677000000',
      code: '482051',
      appLanguage: 'en',
    });
    expect(await guestTokenFor(bookingId)).toBe('guest-token');
    // The wallet is asked with the guest token, on the number the guest proved.
    const pay = calls.find((c) => c.url.endsWith('/v1/guest/booking/payments'))!;
    expect(pay.headers.get('X-Guest-Token')).toBe('guest-token');
    expect(JSON.parse(pay.body)).toEqual({ method: 'MTN_MOMO' });
  });

  it('books straight away for a signed-in seeker', async () => {
    await seeker();
    answer = ({ url, method }) =>
      url.endsWith('/v1/bookings') && method === 'POST'
        ? { status: 201, body: booking({ status: 'PENDING_PAYMENT' }) }
        : undefined;
    const screen = await renderRouter(routes, { initialUrl: checkoutUrl() });

    await fireEvent.press(await screen.findByRole('button', { name: 'Pay 15,000 FCFA' }));

    await waitFor(() => expect(screen.getPathname()).toBe('/book/pay'));
    expect(screen.queryByText('Where should we reach you?')).toBeNull();
    expect(JSON.parse(calls.find((c) => c.url.endsWith('/v1/bookings'))!.body)).toEqual({
      holdId,
      sessionLanguage: 'en',
      source: 'SEARCH',
    });
  });
});

describe('My sessions', () => {
  it('shows the empty state before any booking', async () => {
    await seeker();
    answer = () => undefined;
    const screen = await renderRouter(routes, { initialUrl: '/sessions' });

    expect(await screen.findByText('No sessions yet')).toBeTruthy();
    await fireEvent.press(screen.getByRole('button', { name: 'Find a specialist' }));
    expect(screen.getPathname()).toBe('/search');
  });

  it('lists upcoming sessions with recent past ones, and opens one', async () => {
    await seeker();
    answer = ({ url }) => {
      if (url.includes('scope=UPCOMING')) return { status: 200, body: [booking()] };
      if (url.includes('scope=PAST'))
        return {
          status: 200,
          body: [
            booking({ id: 'past-1', status: 'COMPLETED', start: inDays(-7), end: inDays(-7, 11) }),
          ],
        };
      if (url.endsWith(`/v1/bookings/${bookingId}`)) return { status: 200, body: booking() };
      return undefined;
    };
    const screen = await renderRouter(routes, { initialUrl: '/sessions' });

    expect(await screen.findByText('Ngo Ebode Clarisse')).toBeTruthy();
    expect(screen.getByText('Confirmed')).toBeTruthy();
    expect(screen.getByText('RECENTLY')).toBeTruthy();
    expect(screen.getByText('Completed · Audio')).toBeTruthy();

    await fireEvent.press(screen.getByRole('button', { name: /^Ngo Ebode Clarisse, / }));
    await waitFor(() => expect(screen.getPathname()).toBe(`/session/${bookingId}`));
    expect(await screen.findByText('Your 5th session together')).toBeTruthy();
    expect(screen.getByText('Before you join')).toBeTruthy();
    expect(screen.getByRole('button', { name: 'Join session' })).toBeDisabled();
  });
});

describe('Change or cancel', () => {
  it('shows the refund and cancels with the reason given', async () => {
    await seeker();
    answer = ({ url, method }) => {
      if (url.endsWith('/cancellation') && method === 'GET')
        return { status: 200, body: { refundAmount: 15000, refundPercent: 100, policyVersion: 1 } };
      if (url.endsWith('/cancellation') && method === 'POST')
        return {
          status: 200,
          body: booking({
            status: 'CANCELLED',
            cancelledBy: 'SEEKER',
            refundAmount: 15000,
            reschedulesLeft: 0,
          }),
        };
      if (url.endsWith(`/v1/bookings/${bookingId}`)) return { status: 200, body: booking() };
      return undefined;
    };
    const screen = await renderRouter(routes, { initialUrl: `/session/${bookingId}` });

    await fireEvent.press(await screen.findByRole('button', { name: 'Change or cancel' }));
    expect(await screen.findByText('Change your session?')).toBeTruthy();
    expect(await screen.findByText('15,000 FCFA')).toBeTruthy();
    expect(screen.getByRole('button', { name: /^Pick a new time/ })).toBeTruthy();

    await fireEvent.press(screen.getByRole('radio', { name: 'Feeling better' }));
    await fireEvent.press(screen.getByRole('button', { name: 'Cancel session' }));

    await waitFor(() => expect(calls.some((c) => c.method === 'POST')).toBe(true));
    expect(JSON.parse(calls.find((c) => c.method === 'POST')!.body)).toEqual({
      reason: 'FEELING_BETTER',
    });
    expect(await screen.findByText('Refund: 15,000 FCFA')).toBeTruthy();
    expect(screen.getByText('Cancelled')).toBeTruthy();
  });

  it('opens Choose a session to move it, with the same format', async () => {
    await seeker();
    answer = ({ url }) => {
      if (url.endsWith('/cancellation'))
        return { status: 200, body: { refundAmount: 15000, refundPercent: 100, policyVersion: 1 } };
      if (url.endsWith(`/v1/bookings/${bookingId}`)) return { status: 200, body: booking() };
      return undefined;
    };
    const screen = await renderRouter(routes, { initialUrl: `/session/change?id=${bookingId}` });

    await fireEvent.press(await screen.findByRole('button', { name: /^Pick a new time/ }));
    expect(screen.getPathname()).toBe(`/book/${specialistId}`);
    expect(screen.getSearchParams()).toMatchObject({ reschedule: bookingId, modality: 'AUDIO' });
  });
});

describe('Guest booking from SMS', () => {
  it('opens the booking the link is for and keeps the token', async () => {
    answer = ({ url, headers }) =>
      url.endsWith('/v1/guest/booking') && headers.get('X-Guest-Token') === 'sms-token'
        ? { status: 200, body: booking({ guestPhone: '+237 6 •• •• •• 00' }) }
        : undefined;
    const screen = await renderRouter(routes, { initialUrl: '/guest/sms-token' });

    expect(
      await screen.findByText('Opened from your SMS link for +237 6 •• •• •• 00.'),
    ).toBeTruthy();
    expect(screen.getByText('Clinical psychologist')).toBeTruthy();
    expect(screen.getByRole('button', { name: 'Go to waiting room' })).toBeDisabled();
    await waitFor(async () => expect(await guestTokenFor(bookingId)).toBe('sms-token'));
  });

  it('says so when the link is no longer valid', async () => {
    answer = ({ url }) =>
      url.endsWith('/v1/guest/booking')
        ? {
            status: 401,
            body: { code: 'guest.token-invalid', title: 'This booking link is no longer valid.' },
          }
        : undefined;
    const screen = await renderRouter(routes, { initialUrl: '/guest/old-token' });

    expect(await screen.findByText('This booking link is no longer valid.')).toBeTruthy();
  });
});
