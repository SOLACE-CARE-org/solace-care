import { QueryClientProvider } from '@tanstack/react-query';
import { fireEvent, waitFor } from '@testing-library/react-native';
import { Slot } from 'expo-router';
import { renderRouter } from 'expo-router/testing-library';
import * as SecureStore from 'expo-secure-store';

import { queryClient } from '@/api/query-client';
import SpecialistEarnings from '@/app/(specialist)/earnings';
import BookingConfirmed from '@/app/book/confirmed';
import ApprovePayment from '@/app/book/pay';
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
  'book/pay': ApprovePayment,
  'book/confirmed': BookingConfirmed,
  'book/[id]': () => null,
  'session/[id]': () => null,
  '(specialist)/earnings': SpecialistEarnings,
};

type Call = { url: string; method: string; body: string };
let calls: Call[];
let answer: (call: Call) => { status: number; body?: unknown } | undefined;

const bookingId = '0b1c2d3e-4f50-4a7a-b3e5-5f0d9c1a7e64';
const specialistId = '2c9b6f0e-8d41-4a7a-b3e5-5f0d9c1a7e64';

function booking(patch: Record<string, unknown> = {}) {
  const start = new Date(Date.now() + 3 * 86_400_000);
  return {
    id: bookingId,
    status: 'PENDING_PAYMENT',
    specialist: {
      id: specialistId,
      fullName: 'Ngo Ebode Clarisse',
      professionLabel: 'Clinical psychologist',
    },
    start: start.toISOString(),
    end: new Date(start.getTime() + 50 * 60_000).toISOString(),
    modality: 'AUDIO',
    sessionLanguage: 'en',
    price: 15000,
    payBy: new Date(Date.now() + 8 * 60_000).toISOString(),
    reschedulesLeft: 0,
    ...patch,
  };
}

function payment(status: string) {
  return {
    id: 'p1',
    bookingId,
    method: 'MTN_MOMO',
    amount: 15000,
    status,
    phone: '+237677000000',
    createdAt: new Date().toISOString(),
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
    const { status, body } = answer(call) ?? { status: 200, body: [] };
    return new Response(body === undefined ? null : JSON.stringify(body), {
      status,
      headers: { 'Content-Type': 'application/json' },
    });
  }) as typeof fetch;
});

describe('Approve payment', () => {
  it('waits for the approval, checks on request and opens the confirmation', async () => {
    let approved = false;
    answer = ({ url }) => {
      if (url.includes('/payment?check=true')) {
        approved = true;
        return { status: 200, body: payment('COMPLETE') };
      }
      if (url.includes('/payment'))
        return { status: 200, body: payment(approved ? 'COMPLETE' : 'PENDING') };
      if (url.endsWith(`/v1/bookings/${bookingId}`))
        return {
          status: 200,
          body: booking(approved ? { status: 'CONFIRMED', payBy: undefined } : {}),
        };
      return undefined;
    };
    const screen = await renderRouter(routes, { initialUrl: `/book/pay?id=${bookingId}` });

    expect(await screen.findByText('Approve the payment on your phone')).toBeTruthy();
    expect(
      await screen.findByText(
        'We sent a request for 15,000 FCFA to MTN Mobile Money on +237 6 77 00 00 00. Enter your PIN to approve it.',
      ),
    ).toBeTruthy();
    expect(screen.getByText(/^0[78]:\d\d$/)).toBeTruthy();

    await fireEvent.press(screen.getByRole('button', { name: "I've approved it" }));
    await waitFor(() => expect(screen.getPathname()).toBe('/book/confirmed'));
    expect(await screen.findByText("You're booked")).toBeTruthy();
    expect(screen.getByLabelText('Paid: 15,000 FCFA')).toBeTruthy();
  });

  it('offers another wallet when a request fails', async () => {
    let failed = true;
    answer = ({ url, method }) => {
      if (url.endsWith('/payments') && method === 'POST') {
        failed = false;
        return { status: 201, body: { ...payment('PENDING'), method: 'ORANGE_MONEY' } };
      }
      if (url.includes('/payment'))
        return { status: 200, body: payment(failed ? 'FAILED' : 'PENDING') };
      if (url.endsWith(`/v1/bookings/${bookingId}`)) return { status: 200, body: booking() };
      return undefined;
    };
    const screen = await renderRouter(routes, { initialUrl: `/book/pay?id=${bookingId}` });

    expect(
      await screen.findByText(/^Payment not completed\. Your time is held until/),
    ).toBeTruthy();
    await fireEvent.press(screen.getByRole('button', { name: 'Pay another way' }));
    await fireEvent.press(screen.getByRole('radio', { name: 'Orange Money' }));
    await fireEvent.press(screen.getByRole('button', { name: 'Resend request' }));

    await waitFor(() => expect(calls.some((c) => c.method === 'POST')).toBe(true));
    expect(JSON.parse(calls.find((c) => c.method === 'POST')!.body)).toEqual({
      method: 'ORANGE_MONEY',
    });
  });

  it('sends the seeker back to pick a time once the hold is gone', async () => {
    answer = ({ url }) => {
      if (url.includes('/payment')) return { status: 200, body: payment('FAILED') };
      if (url.endsWith(`/v1/bookings/${bookingId}`))
        return { status: 200, body: booking({ status: 'EXPIRED' }) };
      return undefined;
    };
    const screen = await renderRouter(routes, { initialUrl: `/book/pay?id=${bookingId}` });

    expect(await screen.findByText('Your time is no longer held. Pick a time again.')).toBeTruthy();
    await fireEvent.press(screen.getByRole('button', { name: 'Pick a time again' }));
    expect(screen.getPathname()).toBe(`/book/${specialistId}`);
  });
});

describe('Earnings', () => {
  it('shows the balance, the payout number and recent activity', async () => {
    useSession.setState({ role: 'specialist' });
    answer = ({ url }) =>
      url.endsWith('/v1/me/earnings')
        ? {
            status: 200,
            body: {
              available: 24000,
              onHold: 12000,
              onHoldSessions: 1,
              sessionsThisMonth: 12,
              noShowsThisMonth: 1,
              payoutTo: { method: 'MTN_MOMO', phoneEnd: '00' },
              nextPayout: '2026-11-01',
              activity: [
                {
                  kind: 'SESSION',
                  at: new Date().toISOString(),
                  amount: 12000,
                  seekerLabel: 'Jean-Paul K.',
                },
                {
                  kind: 'PAYOUT',
                  at: new Date(Date.now() - 6 * 86_400_000).toISOString(),
                  amount: -36000,
                  method: 'MTN_MOMO',
                  status: 'PAID',
                },
              ],
            },
          }
        : undefined;
    const screen = await renderRouter(routes, { initialUrl: '/earnings' });

    expect(await screen.findByText('24,000 FCFA')).toBeTruthy();
    expect(screen.getByText('To MTN MoMo •• 00 · next automatic payout Nov 1')).toBeTruthy();
    expect(screen.getByLabelText('This month: 12 sessions')).toBeTruthy();
    expect(screen.getByText('Session · Jean-Paul K.')).toBeTruthy();
    expect(screen.getByText('+12,000')).toBeTruthy();
    expect(screen.getByText('−36,000')).toBeTruthy();
    expect(screen.queryByRole('button', { name: 'Pay out now' })).toBeNull();
  });
});
