import { QueryClientProvider } from '@tanstack/react-query';
import { fireEvent, waitFor } from '@testing-library/react-native';
import { Slot } from 'expo-router';
import { renderRouter } from 'expo-router/testing-library';

import { queryClient } from '@/api/query-client';
import You from '@/app/(seeker)/you';
import Notifications from '@/app/notifications';
import AllowReminders from '@/app/reminders';
import Settings from '@/app/settings';
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
  reminders: AllowReminders,
  notifications: Notifications,
  settings: Settings,
  '(seeker)/you': You,
  '(seeker)/home': () => null,
  'session/[id]': () => null,
  welcome: () => null,
};

type Call = { url: string; method: string; body: string };
let calls: Call[];
let answer: (call: Call) => { status: number; body?: unknown } | undefined;

beforeEach(async () => {
  await initI18n('en');
  useSession.setState({ language: 'en', role: 'seeker', remindersAsked: false, dataSaver: false });
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
    const { status, body } = answer(call) ?? { status: 204 };
    return new Response(body === undefined ? null : JSON.stringify(body), {
      status,
      headers: { 'Content-Type': 'application/json' },
    });
  }) as typeof fetch;
});

const inbox = [
  {
    id: 'n1',
    kind: 'BOOKING_CONFIRMED',
    text: 'Appointment booked: Mon 19 Oct at 18:00.',
    bookingId: 'b1',
    at: new Date().toISOString(),
    read: false,
  },
  {
    id: 'n2',
    kind: 'PAYMENT_RECEIVED',
    text: 'Payment received: 15,000 FCFA. Your receipt is in the app.',
    at: new Date(Date.now() - 3 * 86_400_000).toISOString(),
    read: true,
  },
];

describe('Turn on reminders', () => {
  it('asks once, registers the device with its time zone and goes on', async () => {
    answer = () => undefined;
    const screen = await renderRouter(routes, { initialUrl: '/reminders?next=/home' });

    expect(await screen.findByText('Turn on reminders?')).toBeTruthy();
    await fireEvent.press(screen.getByRole('button', { name: 'Turn on reminders' }));

    await waitFor(() => expect(screen.getPathname()).toBe('/home'));
    const device = JSON.parse(calls.find((c) => c.url.endsWith('/v1/me/devices'))!.body);
    expect(device).toMatchObject({ token: 'ExponentPushToken[test]' });
    expect(['IOS', 'ANDROID']).toContain(device.platform);
    expect(device.timeZone).toBeTruthy();
    expect(useSession.getState().remindersAsked).toBe(true);
  });
});

describe('Notifications', () => {
  it('lists today and earlier, marks all read and opens a booking', async () => {
    answer = ({ url, method }) =>
      url.endsWith('/v1/me/notifications') && method === 'GET'
        ? { status: 200, body: inbox }
        : undefined;
    const screen = await renderRouter(routes, { initialUrl: '/notifications' });

    expect(await screen.findByText('Appointment booked: Mon 19 Oct at 18:00.')).toBeTruthy();
    expect(screen.getByText('TODAY')).toBeTruthy();
    expect(screen.getByText('EARLIER')).toBeTruthy();
    await fireEvent.press(screen.getByRole('button', { name: 'Mark all read' }));
    await waitFor(() =>
      expect(calls.some((c) => c.url.endsWith('/notifications/read') && c.method === 'POST')).toBe(
        true,
      ),
    );
    await fireEvent.press(screen.getByRole('button', { name: /Appointment booked/ }));
    expect(screen.getPathname()).toBe('/session/b1');
  });
});

describe('Settings', () => {
  it('turns on discreet notifications and data saver', async () => {
    answer = ({ url, method }) => {
      if (url.endsWith('/v1/me/notification-settings') && method === 'GET')
        return { status: 200, body: { sessionReminders: true, discreet: false } };
      if (url.endsWith('/v1/me/notification-settings') && method === 'PUT')
        return { status: 200, body: JSON.parse(calls[calls.length - 1].body) };
      return undefined;
    };
    const screen = await renderRouter(routes, { initialUrl: '/settings' });

    await waitFor(() =>
      expect(screen.getByRole('switch', { name: 'Session reminders' })).toBeChecked(),
    );
    await fireEvent.press(screen.getByRole('switch', { name: 'Discreet notifications' }));
    await waitFor(() =>
      expect(JSON.parse(calls.find((c) => c.method === 'PUT')!.body)).toEqual({
        sessionReminders: true,
        discreet: true,
      }),
    );
    await fireEvent.press(screen.getByRole('switch', { name: 'Data saver' }));
    expect(useSession.getState().dataSaver).toBe(true);
  });
});

describe('You', () => {
  it('shows the person, what is new, and signs out', async () => {
    answer = ({ url, method }) => {
      if (url.endsWith('/v1/me') && method === 'GET')
        return {
          status: 200,
          body: {
            id: 'me',
            role: 'SEEKER',
            displayName: 'Mireille',
            phone: '+237677000000',
            appLanguage: 'en',
            onboarding: { preferences: true, privacy: true },
          },
        };
      if (url.endsWith('/v1/me/notifications')) return { status: 200, body: inbox };
      return undefined;
    };
    const screen = await renderRouter(routes, { initialUrl: '/you' });

    expect(await screen.findByText('Mireille')).toBeTruthy();
    expect(screen.getByText('+237 6 77 00 00 00')).toBeTruthy();
    expect(await screen.findByRole('button', { name: 'Notifications, 1 new' })).toBeTruthy();

    await fireEvent.press(screen.getByRole('button', { name: 'Sign out' }));
    await waitFor(() => expect(screen.getPathname()).toBe('/welcome'));
    expect(useAuth.getState().status).toBe('signedOut');
  });
});
