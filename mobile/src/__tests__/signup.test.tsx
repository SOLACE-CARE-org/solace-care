import { QueryClientProvider } from '@tanstack/react-query';
import { fireEvent, waitFor } from '@testing-library/react-native';
import { Slot } from 'expo-router';
import { renderRouter } from 'expo-router/testing-library';

import { queryClient } from '@/api/query-client';
import RoleChoice from '@/app/role-choice';
import SignIn from '@/app/sign-in';
import Done from '@/app/signup/done';
import Details from '@/app/signup/details';
import Preferences from '@/app/signup/preferences';
import Privacy from '@/app/signup/privacy';
import Verify from '@/app/verify';
import { useAuth } from '@/auth/auth';
import { initI18n } from '@/i18n';
import { useSession } from '@/state/session';
import { age, maskDate, parseDate } from '@/state/signup';

/** The root layout's providers, without its font and splash gating. */
function Layout() {
  return (
    <QueryClientProvider client={queryClient}>
      <Slot />
    </QueryClientProvider>
  );
}

const routes = {
  _layout: Layout,
  'role-choice': RoleChoice,
  'sign-in': SignIn,
  verify: Verify,
  'signup/details': Details,
  'signup/preferences': Preferences,
  'signup/privacy': Privacy,
  'signup/done': Done,
  'home-stand-in': () => null,
};

type Call = { url: string; method: string; body: string };
let calls: Call[];
let answer: (call: Call) => { status: number; body?: unknown };

/** Keycloak and Core as the app sees them, scripted per test. */
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

const tokens = { access_token: 'access', refresh_token: 'refresh', expires_in: 300 };
const me = (preferences: boolean, privacy: boolean) => ({
  id: '2c9b6f0e-8d41-4a7a-b3e5-5f0d9c1a7e64',
  role: 'SEEKER',
  displayName: 'Mireille',
  appLanguage: 'en',
  onboarding: { preferences, privacy },
});

beforeEach(async () => {
  await initI18n('en');
  useSession.setState({ language: 'en', role: null });
  await useAuth.getState().signOut();
  queryClient.clear();
  stubNetwork();
});

describe('dates on the sign-up form', () => {
  it('lays digits out as DD / MM / YYYY and reads only real past dates', () => {
    expect(maskDate('12031996')).toBe('12 / 03 / 1996');
    expect(parseDate('12 / 03 / 1996')).toBe('1996-03-12');
    expect(parseDate('31 / 02 / 1996')).toBeNull();
    expect(parseDate('01 / 01 / 2999')).toBeNull();
    expect(age('2008-10-18', new Date(2026, 9, 17))).toBe(17);
    expect(age('2008-10-17', new Date(2026, 9, 17))).toBe(18);
  });
});

describe('signing in', () => {
  it('sends a code to the phone number, then opens the next unfinished sign-up step', async () => {
    answer = ({ url, body }) => {
      if (url.includes('/token') && !body.includes('code=')) return { status: 401, body: { error: 'code_required' } };
      if (url.includes('/token')) return { status: 200, body: tokens };
      if (url.endsWith('/v1/me')) return { status: 200, body: me(false, false) };
      return { status: 200, body: [] };
    };
    const screen = await renderRouter(routes, { initialUrl: '/sign-in' });

    await fireEvent.changeText(screen.getByLabelText('Phone number, +237'), '6 77 00 00 00');
    await fireEvent.press(screen.getByRole('button', { name: 'Send code' }));

    await waitFor(() => expect(screen.getPathname()).toBe('/verify'));
    expect(calls[0].body).toContain('username=%2B237677000000');
    expect(screen.getByText('We sent a 6-digit code by SMS to +237 6 77 00 00 00.')).toBeTruthy();

    await fireEvent.changeText(screen.getByLabelText('6-digit code'), '482913');
    await fireEvent.press(screen.getByRole('button', { name: 'Verify' }));

    await waitFor(() => expect(screen.getPathname()).toBe('/signup/preferences'));
    expect(useAuth.getState().status).toBe('signedIn');
    expect(useSession.getState().role).toBe('seeker');
    // Core was called with the token, in the app language.
    const meCall = (globalThis.fetch as jest.Mock).mock.calls.find(([r]) =>
      String(r instanceof Request ? r.url : r).endsWith('/v1/me'),
    );
    expect((meCall[0] as Request).headers.get('Authorization')).toBe('Bearer access');
  });

  it('says how many tries are left after a wrong code', async () => {
    answer = ({ body }) =>
      body.includes('code=')
        ? { status: 401, body: { error: 'invalid_grant', attempts_left: 2 } }
        : { status: 401, body: { error: 'code_required' } };
    const screen = await renderRouter(routes, {
      initialUrl: '/verify?contact=%2B237677000000&channel=phone',
    });

    await fireEvent.changeText(screen.getByLabelText('6-digit code'), '000000');
    await fireEvent.press(screen.getByRole('button', { name: 'Verify' }));

    expect(await screen.findByText("That code isn't right. 2 tries left.")).toBeTruthy();
    expect(
      screen.getByText("After 3 wrong tries we'll send a new code. Codes expire after 10 minutes."),
    ).toBeTruthy();
    expect(useAuth.getState().status).toBe('signedOut');
  });
});

describe('signing up', () => {
  it('refuses under-eighteens on the form, without calling Core', async () => {
    const screen = await renderRouter(routes, { initialUrl: '/signup/details' });
    const young = new Date();
    young.setFullYear(young.getFullYear() - 17);
    const dd = String(young.getDate()).padStart(2, '0');
    const mm = String(young.getMonth() + 1).padStart(2, '0');

    await fireEvent.changeText(screen.getByLabelText('What should we call you?'), 'Junior');
    await fireEvent.changeText(screen.getByLabelText('Phone number, +237'), '677000001');
    await fireEvent.changeText(screen.getByLabelText('Date of birth'), `${dd}${mm}${young.getFullYear()}`);
    await fireEvent.press(screen.getByRole('checkbox'));
    await fireEvent.press(screen.getByRole('button', { name: 'Send code and continue' }));

    expect((await screen.findAllByText('You need to be 18 or over to use Solace.')).length).toBeGreaterThan(0);
    expect(calls).toHaveLength(0);
  });

  it('starts the account, then asks Keycloak for a code', async () => {
    answer = ({ url }) =>
      url.endsWith('/v1/signups') ? { status: 202 } : { status: 401, body: { error: 'code_required' } };
    const screen = await renderRouter(routes, { initialUrl: '/signup/details' });

    expect(screen.getByLabelText('Step 1 of 3')).toBeTruthy();
    await fireEvent.changeText(screen.getByLabelText('What should we call you?'), 'Mireille');
    await fireEvent.changeText(screen.getByLabelText('Phone number, +237'), '677000000');
    await fireEvent.changeText(screen.getByLabelText('Date of birth'), '12031996');
    expect(screen.getByRole('button', { name: 'Send code and continue' })).toBeDisabled();
    await fireEvent.press(screen.getByRole('checkbox'));
    await fireEvent.press(screen.getByRole('button', { name: 'Send code and continue' }));

    await waitFor(() => expect(screen.getPathname()).toBe('/verify'));
    expect(JSON.parse(calls[0].body)).toEqual({
      displayName: 'Mireille',
      phone: '+237677000000',
      dateOfBirth: '1996-03-12',
      termsVersion: 'v1',
      privacyNoticeVersion: 'v1',
      appLanguage: 'en',
    });
    expect(calls[1].url).toContain('/realms/solace-members/protocol/openid-connect/token');
  });

  it('shows only the privacy choices offered in this release, all off until chosen', async () => {
    await useAuth.getState().signedIn({
      accessToken: 'access',
      refreshToken: 'refresh',
      accessExpiresAt: Date.now() + 300_000,
    });
    answer = ({ url, method }) => {
      if (url.endsWith('/v1/me/consents') && method === 'GET')
        return {
          status: 200,
          body: {
            consents: [
              { purpose: 'AI_ASSIST', granted: false, version: 'v1' },
              { purpose: 'AI_SCRIBE', granted: false, version: 'v1' },
            ],
          },
        };
      if (url.endsWith('/v1/me/consents')) return { status: 200, body: { consents: [] } };
      return { status: 200, body: me(true, true) };
    };
    const screen = await renderRouter(routes, { initialUrl: '/signup/privacy' });

    await waitFor(() => expect(screen.getAllByRole('switch')).toHaveLength(2));
    expect(screen.queryByText('Health tracking')).toBeNull();
    for (const toggle of screen.getAllByRole('switch')) expect(toggle).not.toBeChecked();

    await fireEvent.press(screen.getByRole('switch', { name: 'AI assistance' }));
    await fireEvent.press(screen.getByRole('button', { name: 'Save choices' }));

    await waitFor(() => expect(calls.some((c) => c.method === 'PUT')).toBe(true));
    const saved = calls.find((c) => c.method === 'PUT');
    expect(JSON.parse(saved!.body)).toEqual({
      choices: [
        { purpose: 'AI_ASSIST', granted: true },
        { purpose: 'AI_SCRIBE', granted: false },
      ],
    });
    await waitFor(() => expect(screen.getPathname()).toBe('/signup/done'));
  });
});
