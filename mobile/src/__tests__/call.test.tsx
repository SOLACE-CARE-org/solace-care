import { QueryClientProvider } from '@tanstack/react-query';
import { fireEvent, waitFor } from '@testing-library/react-native';
import { Slot } from 'expo-router';
import { renderRouter } from 'expo-router/testing-library';
import * as SecureStore from 'expo-secure-store';
import { act } from 'react';

import { queryClient } from '@/api/query-client';
import SpecialistToday from '@/app/(specialist)/today';
import SessionCall from '@/app/call/[id]';
import { useAuth } from '@/auth/auth';
import { useCall } from '@/call/engine';
import { initI18n } from '@/i18n';
import { useSession } from '@/state/session';

// The media engine is LiveKit's: here a stand-in records what the screen asks of it.
jest.mock('@/call/engine', () => {
  const { create } = jest.requireActual('zustand');
  const actions = {
    connect: jest.fn(async () => undefined),
    toggleMic: jest.fn(async () => undefined),
    toggleCamera: jest.fn(async () => undefined),
    toggleSpeaker: jest.fn(async () => undefined),
    flipCamera: jest.fn(async () => undefined),
    goAudioOnly: jest.fn(async () => undefined),
    leave: jest.fn(async () => undefined),
  };
  const useCall = create(() => ({
    status: 'idle',
    video: false,
    remotePresent: false,
    remoteSpeaking: false,
    quality: 'good',
    micOn: true,
    cameraOn: false,
    speakerOn: false,
    audioOnly: false,
    offerAudioOnly: false,
    switchedAutomatically: false,
    remoteVideo: null,
    localVideo: null,
    connectedAt: null,
    ...actions,
  }));
  return { useCall, checkMicrophone: jest.fn(async () => true) };
});

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
  'call/[id]': SessionCall,
  'session/[id]': () => null,
  '(specialist)/today': SpecialistToday,
  '(specialist)/calendar': () => null,
};

const bookingId = '0b1c2d3e-4f50-4a7a-b3e5-5f0d9c1a7e64';
const inMinutes = (m: number) => new Date(Date.now() + m * 60_000).toISOString();

function booking(patch: Record<string, unknown> = {}) {
  return {
    id: bookingId,
    status: 'CONFIRMED',
    specialist: { id: 's1', fullName: 'Ngo Ebode Clarisse' },
    start: inMinutes(5),
    end: inMinutes(55),
    modality: 'AUDIO',
    sessionLanguage: 'en',
    price: 15000,
    reschedulesLeft: 2,
    ...patch,
  };
}

const token = {
  url: 'wss://media.example',
  token: 'jwt',
  room: bookingId,
  role: 'SEEKER',
  modality: 'AUDIO',
  expiresAt: inMinutes(70),
};

let answer: (url: string, method: string) => { status: number; body?: unknown } | undefined;

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
  useCall.setState({ status: 'idle', video: false, offerAudioOnly: false, remotePresent: false });
  jest.clearAllMocks();
  globalThis.fetch = jest.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const request = input instanceof Request ? input : new Request(String(input), init);
    const languages = request.url.includes('/session-languages')
      ? { status: 200, body: [{ code: 'en', name: 'English' }] }
      : undefined;
    const { status, body } = languages ??
      answer(request.url, request.method) ?? { status: 200, body: [] };
    return new Response(body === undefined ? null : JSON.stringify(body), {
      status,
      headers: { 'Content-Type': 'application/json' },
    });
  }) as typeof fetch;
});

describe('Waiting room and call', () => {
  it('checks the microphone and connection, joins, and ends the session', async () => {
    answer = (url) => {
      if (url.endsWith('/join')) return { status: 200, body: token };
      if (url.endsWith(`/v1/bookings/${bookingId}`)) return { status: 200, body: booking() };
      return undefined;
    };
    const screen = await renderRouter(routes, { initialUrl: `/call/${bookingId}` });

    expect(await screen.findByText('Waiting room')).toBeTruthy();
    expect(await screen.findByText('Microphone works')).toBeTruthy();
    expect(await screen.findByText('Connection is good')).toBeTruthy();
    expect(screen.getByText('Not recorded. AI notes are off for your sessions.')).toBeTruthy();

    await fireEvent.press(screen.getByRole('button', { name: 'Join now' }));
    await waitFor(() => expect(useCall.getState().connect).toHaveBeenCalledWith(token));

    act(() =>
      useCall.setState({ status: 'connected', connectedAt: Date.now(), remotePresent: true }),
    );
    expect(await screen.findByText('Private · not recorded')).toBeTruthy();
    expect(screen.getByText('Audio session · English')).toBeTruthy();
    // Audio sessions never offer a camera.
    expect(screen.queryByRole('button', { name: 'Turn off camera' })).toBeNull();
    await fireEvent.press(screen.getByRole('button', { name: 'Mute microphone' }));
    expect(useCall.getState().toggleMic).toHaveBeenCalled();
    await fireEvent.press(screen.getByRole('button', { name: 'End session' }));
    expect(useCall.getState().leave).toHaveBeenCalled();
  });

  it('says when joining is not open yet', async () => {
    answer = (url) => {
      if (url.endsWith('/join'))
        return {
          status: 409,
          body: {
            code: 'session.not-open-yet',
            title: 'Joining opens 10 minutes before the start.',
          },
        };
      if (url.endsWith(`/v1/bookings/${bookingId}`))
        return { status: 200, body: booking({ start: inMinutes(60), end: inMinutes(110) }) };
      return undefined;
    };
    const screen = await renderRouter(routes, { initialUrl: `/call/${bookingId}` });

    expect(await screen.findByText('Joining opens 10 minutes before the start.')).toBeTruthy();
    expect(screen.getByRole('button', { name: 'Join now' })).toBeDisabled();
  });

  it('offers audio only when video struggles, and warns while reconnecting', async () => {
    answer = (url) =>
      url.endsWith(`/v1/bookings/${bookingId}`)
        ? { status: 200, body: booking({ modality: 'VIDEO' }) }
        : { status: 200, body: { ...token, modality: 'VIDEO' } };
    act(() =>
      useCall.setState({
        status: 'reconnecting',
        video: true,
        cameraOn: true,
        offerAudioOnly: true,
        connectedAt: Date.now(),
      }),
    );
    const screen = await renderRouter(routes, { initialUrl: `/call/${bookingId}` });

    expect(await screen.findByText('Reconnecting. Your place is kept for 2 minutes.')).toBeTruthy();
    expect(screen.getByRole('button', { name: 'Turn off camera' })).toBeTruthy();
    await fireEvent.press(screen.getByRole('button', { name: 'Switch to audio only' }));
    expect(useCall.getState().goAudioOnly).toHaveBeenCalled();
  });
});

describe('Today', () => {
  it('shows the next session with Join from 10 minutes before', async () => {
    useSession.setState({ role: 'specialist' });
    answer = (url) => {
      if (url.includes('scope=UPCOMING'))
        return {
          status: 200,
          body: [
            booking({
              seeker: { label: 'Guest', guest: true },
              start: inMinutes(30),
              end: inMinutes(80),
            }),
          ],
        };
      if (url.includes('scope=PAST')) return { status: 200, body: [] };
      if (url.includes('/v1/me/specialist-application'))
        return { status: 200, body: { fullName: 'Dr. Ngo Ebode' } };
      return undefined;
    };
    const screen = await renderRouter(routes, { initialUrl: '/today' });

    expect(await screen.findByText('Guest · first session')).toBeTruthy();
    expect(screen.getByRole('button', { name: /^Join at / })).toBeDisabled();
  });

  it('shows a quiet day with the next session', async () => {
    useSession.setState({ role: 'specialist' });
    answer = (url) => {
      if (url.includes('scope=UPCOMING'))
        return {
          status: 200,
          body: [
            booking({
              seeker: { label: 'Awa N.' },
              start: inMinutes(3 * 1440),
              end: inMinutes(3 * 1440 + 50),
            }),
          ],
        };
      if (url.includes('scope=PAST')) return { status: 200, body: [] };
      return undefined;
    };
    const screen = await renderRouter(routes, { initialUrl: '/today' });

    expect(await screen.findByText('No sessions today')).toBeTruthy();
    expect(screen.getByText(/^Your next session is /)).toBeTruthy();
    await fireEvent.press(screen.getByRole('button', { name: 'Open my calendar' }));
    expect(screen.getPathname()).toBe('/calendar');
  });
});
