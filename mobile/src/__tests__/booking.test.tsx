import { QueryClientProvider } from '@tanstack/react-query';
import { fireEvent, waitFor } from '@testing-library/react-native';
import { Slot } from 'expo-router';
import { renderRouter } from 'expo-router/testing-library';

import { queryClient } from '@/api/query-client';
import SpecialistCalendar from '@/app/(specialist)/calendar';
import ChooseSession from '@/app/book/[id]';
import { useAuth } from '@/auth/auth';
import { initI18n } from '@/i18n';
import { timeOf } from '@/state/format';
import { useSession } from '@/state/session';

function Layout() {
  return (
    <QueryClientProvider client={queryClient}>
      <Slot />
    </QueryClientProvider>
  );
}

const routes = {
  _layout: Layout,
  'book/[id]': ChooseSession,
  'book/checkout': () => null,
  '(specialist)/calendar': SpecialistCalendar,
};

type Call = { url: string; method: string; body: string };
let calls: Call[];
let answer: (call: Call) => { status: number; body?: unknown };

beforeEach(async () => {
  await initI18n('en');
  useSession.setState({ language: 'en', role: null });
  await useAuth.getState().signOut();
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
    const { status, body } = answer(call);
    return new Response(body === undefined ? null : JSON.stringify(body), {
      status,
      headers: { 'Content-Type': 'application/json' },
    });
  }) as typeof fetch;
});

const id = '2c9b6f0e-8d41-4a7a-b3e5-5f0d9c1a7e64';
// Tomorrow at 09:00 and 10:00 on this machine's clock.
const tomorrow = (hour: number) => {
  const d = new Date();
  d.setDate(d.getDate() + 1);
  d.setHours(hour, 0, 0, 0);
  return d.toISOString();
};

function catalogue(url: string) {
  if (url.includes('/session-languages'))
    return { status: 200, body: [{ code: 'en', name: 'English' }, { code: 'fr', name: 'Français' }] };
  if (url.includes('/slots'))
    return {
      status: 200,
      body: {
        timeZone: 'Africa/Douala',
        durationMinutes: 50,
        price: 15000,
        slots: [
          { start: tomorrow(9), available: false },
          { start: tomorrow(10), available: true },
        ],
      },
    };
  if (url.includes(`/v1/catalogue/specialists/${id}`))
    return {
      status: 200,
      body: {
        id,
        fullName: 'Ngo Ebode Clarisse',
        bio: 'Bio',
        languages: [{ code: 'en', level: 'NATIVE' }],
        offerings: [{ modality: 'AUDIO', durationMinutes: 50, price: 15000 }],
        focusAreas: [],
      },
    };
  return null;
}

describe('Choose a session', () => {
  it('greys taken times, holds the chosen one and opens checkout', async () => {
    answer = ({ url, method }) => {
      if (url.endsWith('/v1/slot-holds') && method === 'POST')
        return {
          status: 201,
          body: {
            holdId: '7f9d1c2e-1111-4a7a-b3e5-5f0d9c1a7e64',
            specialistId: id,
            start: tomorrow(10),
            end: tomorrow(11),
            modality: 'AUDIO',
            expiresAt: new Date(Date.now() + 600_000).toISOString(),
          },
        };
      return catalogue(url) ?? { status: 200, body: [] };
    };
    const screen = await renderRouter(routes, { initialUrl: `/book/${id}` });

    expect(await screen.findByText('Step 1 of 2 · with Ngo Ebode Clarisse')).toBeTruthy();
    const nine = timeOf(new Date(tomorrow(9)), 'en');
    const ten = timeOf(new Date(tomorrow(10)), 'en');
    expect(await screen.findByRole('radio', { name: `${nine}, taken` })).toBeDisabled();
    expect(screen.getByRole('button', { name: 'Continue' })).toBeDisabled();

    await fireEvent.press(screen.getByRole('radio', { name: ten }));
    await fireEvent.press(screen.getByRole('button', { name: 'Continue' }));

    await waitFor(() => expect(screen.getPathname()).toBe('/book/checkout'));
    const hold = calls.find((c) => c.method === 'POST')!;
    expect(JSON.parse(hold.body)).toEqual({ specialistId: id, start: tomorrow(10), modality: 'AUDIO' });
  });

  it('says so when someone else just took the time', async () => {
    answer = ({ url, method }) =>
      url.endsWith('/v1/slot-holds') && method === 'POST'
        ? { status: 409, body: { code: 'slot.taken', title: 'This time was just taken. Pick another.' } }
        : (catalogue(url) ?? { status: 200, body: [] });
    const screen = await renderRouter(routes, { initialUrl: `/book/${id}` });

    await fireEvent.press(await screen.findByRole('radio', { name: timeOf(new Date(tomorrow(10)), 'en') }));
    await fireEvent.press(screen.getByRole('button', { name: 'Continue' }));

    expect(await screen.findByText('This time was just taken. Pick another.')).toBeTruthy();
    expect(screen.getPathname()).toBe(`/book/${id}`);
  });
});

describe('Calendar', () => {
  it("lists the day's open starts and time off, and blocks time off", async () => {
    await useAuth.getState().signedIn({
      accessToken: 'access',
      refreshToken: 'refresh',
      accessExpiresAt: Date.now() + 300_000,
    });
    const today = new Date();
    const key = `${today.getFullYear()}-${String(today.getMonth() + 1).padStart(2, '0')}-${String(today.getDate()).padStart(2, '0')}`;
    const at = (hour: number) => {
      const d = new Date(today);
      d.setHours(hour, 0, 0, 0);
      return d.toISOString();
    };
    answer = ({ url, method }) => {
      if (url.includes('/v1/me/time-off') && method === 'POST')
        return { status: 201, body: { id: 'x', start: at(17), end: at(18), reason: 'PERSONAL' } };
      if (url.includes('/v1/me/calendar'))
        return {
          status: 200,
          body: {
            timeZone: 'Africa/Douala',
            days: [
              {
                date: key,
                openStarts: [at(11)],
                timeOff: [{ id: '5b6c', start: at(14), end: at(15), reason: 'PERSONAL' }],
              },
            ],
          },
        };
      return { status: 200, body: [] };
    };
    const screen = await renderRouter(routes, { initialUrl: '/calendar' });

    expect(await screen.findByText('Open slot')).toBeTruthy();
    expect(screen.getByText(/Blocked · personal/)).toBeTruthy();

    await fireEvent.press(screen.getByRole('button', { name: 'Block time off' }));
    await fireEvent.changeText(screen.getByLabelText('From'), '1700');
    await fireEvent.changeText(screen.getByLabelText('To'), '1800');
    await fireEvent.press(screen.getByRole('button', { name: 'Block' }));

    await waitFor(() => expect(calls.some((c) => c.method === 'POST')).toBe(true));
    const posted = JSON.parse(calls.find((c) => c.method === 'POST')!.body);
    expect(posted).toEqual({ start: at(17), end: at(18), reason: 'PERSONAL' });
  });
});
