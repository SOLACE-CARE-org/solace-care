import { QueryClientProvider } from '@tanstack/react-query';
import { fireEvent, waitFor } from '@testing-library/react-native';
import { Slot } from 'expo-router';
import { renderRouter } from 'expo-router/testing-library';

import { queryClient } from '@/api/query-client';
import type { SpecialistApplication } from '@/api/specialist';
import SpecialistPractice from '@/app/specialist-practice';
import AddSpecialty from '@/app/specialist-practice/add-specialty';
import RequestSpecialty from '@/app/specialist-practice/request';
import Documents from '@/app/specialist-signup/documents';
import Practice from '@/app/specialist-signup/practice';
import { useAuth } from '@/auth/auth';
import { initI18n } from '@/i18n';
import { practiceLine } from '@/state/format';
import { useSession } from '@/state/session';

jest.mock('expo-document-picker', () => ({
  getDocumentAsync: jest.fn(async () => ({
    canceled: false,
    assets: [{ uri: 'file:///licence.pdf', name: 'licence.pdf', mimeType: 'application/pdf' }],
  })),
}));

function Layout() {
  return (
    <QueryClientProvider client={queryClient}>
      <Slot />
    </QueryClientProvider>
  );
}

const routes = {
  _layout: Layout,
  'specialist-signup/practice': Practice,
  'specialist-signup/documents': Documents,
  'specialist-signup/languages': () => null,
  'specialist-practice/index': SpecialistPractice,
  'specialist-practice/add-specialty': AddSpecialty,
  'specialist-practice/request': RequestSpecialty,
};

type Call = { url: string; method: string; body: unknown };
let calls: Call[];
let current: SpecialistApplication;

const professions = [
  {
    code: 'clinical-psychologist',
    label: 'Clinical psychologist',
    specialties: [
      { code: 'clinical-psychology', label: 'Clinical psychology' },
      { code: 'trauma', label: 'Trauma', parentCode: 'clinical-psychology' },
    ],
  },
  {
    code: 'licensed-counsellor',
    label: 'Licensed counsellor',
    specialties: [{ code: 'couples', label: 'Couples counselling', parentCode: 'counselling' }],
  },
];

/** A multipart field, whether the body is the platform's FormData or React Native's. */
function field(body: unknown, name: string): unknown[] {
  const form = body as {
    getAll?: (n: string) => unknown[];
    getParts?: () => { fieldName: string; string?: string; uri?: string }[];
  };
  if (form.getParts) {
    return form
      .getParts()
      .filter((p) => p.fieldName === name)
      .map((p) => p.string ?? p.uri);
  }
  return form.getAll ? form.getAll(name) : [];
}

function stubNetwork() {
  calls = [];
  globalThis.fetch = jest.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const url = input instanceof Request ? input.url : String(input);
    const method = input instanceof Request ? input.method : (init?.method ?? 'GET');
    const body = input instanceof Request ? await input.clone().text() : init?.body;
    calls.push({ url, method, body });
    const json = url.includes('/v1/catalogue/professions')
      ? professions
      : url.includes('/v1/catalogue/help-topics')
        ? [{ code: 'anxiety', label: 'Anxiety' }]
        : current;
    return new Response(JSON.stringify(json), {
      status: 200,
      headers: { 'Content-Type': 'application/json' },
    });
  }) as typeof fetch;
}

const pricing = { minPrice: 5000, maxPrice: 20000, feePercent: 20, durations: [30, 50, 80] };
const steps = {
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
    steps,
    languages: [],
    documents: [],
    offerings: [],
    pricing,
    ...patch,
  };
}

const approved = () =>
  application({
    status: 'APPROVED',
    professions: [
      {
        profession: 'clinical-psychologist',
        label: 'Clinical psychologist',
        licenceNumber: 'ONPC-1',
        licensingBody: 'ONPC',
        status: 'APPROVED',
        specialties: [
          { specialty: 'clinical-psychology', label: 'Clinical psychology', status: 'APPROVED' },
          {
            specialty: 'trauma',
            label: 'Trauma',
            status: 'DECLINED',
            reviewNote: 'Please attach the certificate.',
          },
        ],
      },
    ],
    specialtyRequests: [
      {
        id: 'r1',
        profession: 'clinical-psychologist',
        proposedName: 'Perinatal psychology',
        status: 'OPEN',
        requestedAt: '2026-10-20T08:00:00Z',
      },
    ],
  });

beforeEach(async () => {
  await initI18n('en');
  useSession.setState({ language: 'en', role: null });
  await useAuth.getState().signedIn({
    accessToken: 'access',
    refreshToken: 'refresh',
    accessExpiresAt: Date.now() + 300_000,
  });
  queryClient.clear();
  current = application();
  stubNetwork();
});

describe('Practice step (D62)', () => {
  it('saves two professions, each with its licence and specialties', async () => {
    const screen = await renderRouter(routes, { initialUrl: '/specialist-signup/practice' });
    await screen.findByText('Main profession');

    await fireEvent.press(screen.getByLabelText('Profession, '));
    await fireEvent.press(await screen.findByText('Clinical psychologist'));
    await fireEvent.changeText(screen.getAllByLabelText('Licence number')[0], 'ONPC-1');
    await fireEvent.changeText(screen.getAllByLabelText('Issuing professional body')[0], 'ONPC');
    await fireEvent.press(await screen.findByText('Trauma'));

    await fireEvent.press(screen.getByText('Add another profession'));
    await screen.findByText('Profession 2');
    await fireEvent.press(screen.getAllByLabelText('Profession, ')[0]);
    // The profession already chosen is not offered again.
    await waitFor(() => expect(screen.queryAllByText('Clinical psychologist')).toHaveLength(1));
    await fireEvent.press(await screen.findByText('Licensed counsellor'));
    await fireEvent.changeText(screen.getAllByLabelText('Licence number')[1], 'CA-2');
    await fireEvent.changeText(screen.getAllByLabelText('Issuing professional body')[1], 'Ordre');
    await fireEvent.press(await screen.findByText('Couples counselling'));

    await fireEvent.changeText(screen.getByLabelText('Years in practice'), '9');
    await fireEvent.press(screen.getByText('Anxiety'));
    await fireEvent.changeText(
      screen.getByLabelText('Short bio for your profile'),
      'I help adults.',
    );
    current = application({ steps: { ...steps, practice: true } });
    await fireEvent.press(screen.getByText('Continue'));

    await waitFor(() =>
      expect(calls.some((c) => c.url.endsWith('/specialist-application/practice'))).toBe(true),
    );
    const sent = JSON.parse(
      String(calls.find((c) => c.url.endsWith('/specialist-application/practice'))!.body),
    );
    expect(sent.professions).toEqual([
      {
        profession: 'clinical-psychologist',
        licenceNumber: 'ONPC-1',
        licensingBody: 'ONPC',
        specialties: ['trauma'],
      },
      {
        profession: 'licensed-counsellor',
        licenceNumber: 'CA-2',
        licensingBody: 'Ordre',
        specialties: ['couples'],
      },
    ]);
    expect(sent.yearsInPractice).toBe(9);
  });
});

describe('Documents step (D62)', () => {
  it('asks for a licence for each profession and says which', async () => {
    current = application({
      steps: { ...steps, practice: true },
      practice: {
        professions: [
          {
            profession: 'clinical-psychologist',
            licenceNumber: '1',
            licensingBody: 'O',
            specialties: ['clinical-psychology'],
          },
          {
            profession: 'licensed-counsellor',
            licenceNumber: '2',
            licensingBody: 'O',
            specialties: ['couples'],
          },
        ],
        yearsInPractice: 3,
        focusAreas: ['anxiety'],
        bio: 'Bio',
      },
      professions: [
        {
          profession: 'clinical-psychologist',
          label: 'Clinical psychologist',
          licenceNumber: '1',
          licensingBody: 'O',
          status: 'PENDING',
          specialties: [],
        },
        {
          profession: 'licensed-counsellor',
          label: 'Licensed counsellor',
          licenceNumber: '2',
          licensingBody: 'O',
          status: 'PENDING',
          specialties: [],
        },
      ],
      documents: [
        {
          kind: 'LICENCE',
          profession: 'clinical-psychologist',
          files: [
            {
              fileName: 'onpc.pdf',
              contentType: 'application/pdf',
              size: 10,
              uploadedAt: '2026-10-20T08:00:00Z',
            },
          ],
        },
      ],
    });
    const screen = await renderRouter(routes, { initialUrl: '/specialist-signup/documents' });
    await screen.findByText('Licence · Clinical psychologist');
    expect(screen.getByText('Licence · Licensed counsellor')).toBeTruthy();
    expect(screen.getByText('onpc.pdf · uploaded')).toBeTruthy();

    await fireEvent.press(screen.getByLabelText('Add: Licence · Licensed counsellor'));
    await waitFor(() =>
      expect(
        calls.some(
          (c) =>
            c.method === 'PUT' &&
            c.url.endsWith('/documents/LICENCE?profession=licensed-counsellor'),
        ),
      ).toBe(true),
    );
  });
});

describe('Professions & specialties after approval (D62)', () => {
  it('shows each status and the reason, and withdraws a specialty', async () => {
    current = approved();
    const screen = await renderRouter(routes, { initialUrl: '/specialist-practice' });
    await screen.findByText('Clinical psychologist');
    expect(screen.getByText('Reason: Please attach the certificate.')).toBeTruthy();
    expect(screen.getByText('Perinatal psychology')).toBeTruthy();
    expect(screen.getByText('Waiting for an answer')).toBeTruthy();

    const alert = jest.spyOn(require('react-native').Alert, 'alert');
    await fireEvent.press(screen.getByLabelText('Withdraw Clinical psychology'));
    const buttons = alert.mock.calls[0]![2] as { text: string; onPress?: () => void }[];
    buttons.find((b) => b.text === 'Withdraw')!.onPress!();
    await waitFor(() =>
      expect(
        calls.some(
          (c) =>
            c.method === 'DELETE' &&
            c.url.endsWith(
              '/v1/me/specialist-professions/clinical-psychologist/specialties/clinical-psychology',
            ),
        ),
      ).toBe(true),
    );
  });

  it('adds a specialty only with a note, and offers the request when it is not listed', async () => {
    current = approved();
    const screen = await renderRouter(routes, {
      initialUrl: '/specialist-practice/add-specialty?profession=clinical-psychologist',
    });
    await screen.findByText('Add a specialty for Clinical psychologist');
    // Already approved: not offered again. Declined: may be asked for again.
    expect(screen.queryByText('Clinical psychology')).toBeNull();
    await fireEvent.press(await screen.findByText('Trauma'));
    expect(screen.getByText('Send for review')).toBeTruthy();
    await fireEvent.press(screen.getByText('Send for review'));
    expect(calls.some((c) => c.url.includes('/specialties') && c.method === 'POST')).toBe(false);

    await fireEvent.changeText(
      screen.getByLabelText('Your training or experience in it'),
      'Two-year certificate, 2021.',
    );
    await fireEvent.press(screen.getByText('Send for review'));
    await waitFor(() =>
      expect(
        calls.some(
          (c) =>
            c.method === 'POST' &&
            c.url.endsWith('/v1/me/specialist-professions/clinical-psychologist/specialties'),
        ),
      ).toBe(true),
    );
    const sent = calls.find((c) => c.url.endsWith('/specialties'))!;
    expect(field(sent.body, 'specialty')).toEqual(['trauma']);
    expect(field(sent.body, 'note')).toEqual(['Two-year certificate, 2021.']);
  });

  it('asks for a specialty that is not listed', async () => {
    current = approved();
    const screen = await renderRouter(routes, {
      initialUrl: '/specialist-practice/request?profession=clinical-psychologist',
    });
    await screen.findByText('Ask for a specialty');
    await fireEvent.changeText(
      screen.getByLabelText('Name of the specialty'),
      'Perinatal psychology',
    );
    await fireEvent.changeText(
      screen.getByLabelText('Your training or experience in it'),
      'Trained for two years.',
    );
    await fireEvent.press(screen.getByLabelText('Add: Certificate'));
    await screen.findByText('licence.pdf · uploaded');
    await fireEvent.press(screen.getByText('Send request'));
    await waitFor(() =>
      expect(
        calls.some((c) => c.method === 'POST' && c.url.endsWith('/v1/me/specialty-requests')),
      ).toBe(true),
    );
    const sent = calls.find((c) => c.url.endsWith('/v1/me/specialty-requests'))!;
    expect(field(sent.body, 'profession')).toEqual(['clinical-psychologist']);
    expect(field(sent.body, 'name')).toEqual(['Perinatal psychology']);
    expect(field(sent.body, 'file')).toHaveLength(1);
  });
});

describe('The practice line (D62)', () => {
  it('names the professions, then the specialties', () => {
    expect(practiceLine(['Licensed counsellor'], ['Marriage and couples counselling'], 'X')).toBe(
      'Licensed counsellor · Marriage and couples counselling',
    );
    expect(practiceLine(undefined, undefined, 'Clinical psychologist')).toBe(
      'Clinical psychologist',
    );
    expect(practiceLine(['A', 'B'], [])).toBe('A, B');
  });
});
