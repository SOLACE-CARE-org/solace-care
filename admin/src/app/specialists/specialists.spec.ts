import { provideZonelessChangeDetection, type Type } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter, Router } from '@angular/router';
import { TranslocoTestingModule } from '@jsverse/transloco';
import en from '@solace/i18n/en.json';
import fr from '@solace/i18n/fr.json';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import { CoreApi, type CoreSchemas } from '../api/core-api';
import { StaffSession } from '../auth/staff-session';
import { ApplicationReviewPage } from './application-review.page';
import { ApplicationsPage } from './applications.page';
import { PracticeRequestsPage } from './practice-requests.page';
import { SpecialistRecordPage } from './specialist-record.page';

type Review = CoreSchemas['ApplicationReview'];

// eslint-disable-next-line @typescript-eslint/no-explicit-any
type AnyFn = (...args: any[]) => any;
/** A stub for one method of the generated client; tests read its calls. */
const fn = (impl: AnyFn = () => undefined) => vi.fn<AnyFn>(impl);

const ok = <T>(data: T) => Promise.resolve({ data, response: new Response(null, { status: 200 }) });

function review(patch: Partial<Review> = {}): Review {
  return {
    id: '7f9d1c2e-1111-4a7a-b3e5-5f0d9c1a7e64',
    status: 'UNDER_REVIEW',
    fullName: 'Kamga Estelle',
    professionLabel: 'Clinical psychologist',
    submittedAt: '2026-10-09T08:00:00Z',
    verifiedItems: 5,
    identity: {
      verification: {
        by: { id: 'ops-1', name: 'N. Ebai' },
        at: '2026-10-14T09:00:00Z',
      },
    },
    practice: { verified: false },
    professions: [
      {
        profession: 'clinical-psychologist',
        label: 'Clinical psychologist',
        position: 1,
        licenceNumber: 'ONPC-1234',
        licensingBody: 'Ordre national',
        status: 'PENDING',
        requestedAt: '2026-10-09T08:00:00Z',
        specialties: [
          {
            specialty: 'clinical-psychology',
            label: 'Clinical psychology',
            status: 'PENDING',
            requestedAt: '2026-10-09T08:00:00Z',
          },
        ],
      },
    ],
    specialtyRequests: [],
    languages: [{ code: 'fr', declared: 'NATIVE' }],
    services: {
      offerings: [{ modality: 'AUDIO', enabled: true, durationMinutes: 50, price: 15000 }],
      withinBands: true,
    },
    profile: { bioEn: 'I help adults.', bioFr: "J'accompagne les adultes." },
    payout: {
      method: 'MTN_MOMO',
      phone: '+237•••••••81',
      holderName: 'Kamga Estelle',
    },
    documents: [],
    notes: '',
    activity: [{ at: '2026-10-09T08:00:00Z', kind: 'SUBMITTED' }],
    ...patch,
  };
}

function setUp(roles: string[], client: Record<string, ReturnType<typeof fn>>, sub = 'ops-1') {
  TestBed.configureTestingModule({
    imports: [
      TranslocoTestingModule.forRoot({
        langs: { en, fr },
        translocoConfig: { availableLangs: ['en', 'fr'], defaultLang: 'en' },
      }),
    ],
    providers: [
      provideZonelessChangeDetection(),
      provideRouter([
        { path: 'applications/:id', children: [] },
        { path: 'practice-requests', children: [] },
        { path: 'specialists/:id', children: [] },
      ]),
      { provide: CoreApi, useValue: { client, document: vi.fn() } },
    ],
  });
  TestBed.inject(StaffSession).fromToken({
    sub,
    name: 'Nadine Ebai',
    realm_access: { roles },
  });
}

async function render<T>(component: Type<T>, inputs: Record<string, unknown> = {}) {
  const fixture = TestBed.createComponent(component);
  for (const [key, value] of Object.entries(inputs)) fixture.componentRef.setInput(key, value);
  await fixture.whenStable();
  fixture.detectChanges();
  await fixture.whenStable();
  return fixture.nativeElement as HTMLElement;
}

const button = (root: HTMLElement, label: string) =>
  [...root.querySelectorAll('button')].find((b) => b.textContent?.trim() === label);

describe('Application review', () => {
  beforeEach(() => TestBed.resetTestingModule());

  it('keeps Propose approval locked until all six items are verified', async () => {
    const client = { GET: fn(() => ok(review())), POST: fn(), PUT: fn() };
    setUp(['OPERATIONS'], client);
    const root = await render(ApplicationReviewPage, { id: review().id });

    expect(root.textContent).toContain('5 of 6 items verified.');
    expect(root.textContent).toContain('Approve unlocks when every item is verified.');
    expect(button(root, 'Propose approval')!.disabled).toBe(true);
    expect(root.textContent).toContain('Verified by N. Ebai');
  });

  it('lets the reviewer propose approval once every item is verified', async () => {
    const client = {
      GET: fn(() => ok(review({ verifiedItems: 6 }))),
      POST: fn(() => ok(review({ verifiedItems: 6 }))),
      PUT: fn(),
    };
    setUp(['OPERATIONS'], client);
    const root = await render(ApplicationReviewPage, { id: review().id });

    const propose = button(root, 'Propose approval')!;
    expect(propose.disabled).toBe(false);
    propose.click();
    await vi.waitFor(() => expect(client.POST).toHaveBeenCalled());
    expect(client.POST.mock.calls[0][0]).toBe(
      '/admin/v1/specialist-applications/{id}/approval-proposal',
    );
  });

  it('lets the proposer withdraw, not confirm (D29)', async () => {
    const proposal = { by: { id: 'ops-1', name: 'Nadine Ebai' }, at: '2026-10-16T10:00:00Z' };
    const client = {
      GET: fn(() => ok(review({ verifiedItems: 6, approvalProposal: proposal }))),
      POST: fn(),
      PUT: fn(),
    };
    setUp(['OPERATIONS'], client, 'ops-1');
    const root = await render(ApplicationReviewPage, { id: review().id });

    expect(root.textContent).toContain('You proposed approval');
    expect(button(root, 'Withdraw')).toBeDefined();
    expect(button(root, 'Confirm approval')).toBeUndefined();
  });

  it('lets a second person confirm or decline (D29)', async () => {
    const proposal = { by: { id: 'ops-1', name: 'Nadine Ebai' }, at: '2026-10-16T10:00:00Z' };
    const client = {
      GET: fn(() => ok(review({ verifiedItems: 6, approvalProposal: proposal }))),
      POST: fn(() => ok(review({ status: 'APPROVED', verifiedItems: 6 }))),
      PUT: fn(),
    };
    setUp(['SUPER_ADMIN'], client, 'sa-2');
    const root = await render(ApplicationReviewPage, { id: review().id });

    expect(root.textContent).toContain('Approval proposed by Nadine Ebai');
    expect(button(root, 'Decline')).toBeDefined();
    button(root, 'Confirm approval')!.click();
    await vi.waitFor(() => expect(client.POST).toHaveBeenCalled());
    expect(client.POST.mock.calls[0][0]).toBe('/admin/v1/specialist-applications/{id}/approval');
  });

  it('shows Support the application without any action', async () => {
    const client = { GET: fn(() => ok(review())), POST: fn(), PUT: fn() };
    setUp(['SUPPORT'], client);
    const root = await render(ApplicationReviewPage, { id: review().id });

    expect(root.textContent).toContain('Kamga Estelle');
    for (const label of ['Propose approval', 'Reject', 'Request changes', 'Mark verified']) {
      expect(button(root, label)).toBeUndefined();
    }
  });

  it("sends each profession's licence expiry date when checking it (D62)", async () => {
    const client = {
      GET: fn(() => ok(review())),
      POST: fn(),
      PUT: fn(() => ok(review())),
    };
    setUp(['OPERATIONS'], client);
    const root = await render(ApplicationReviewPage, { id: review().id });

    expect(root.textContent).toContain('2. Professions and licences');
    expect(root.textContent).toContain('ONPC-1234');
    const check = button(root, 'Mark licence verified')!;
    expect(check.disabled).toBe(true);
    const date = root.querySelector<HTMLInputElement>('input[type="date"]')!;
    date.value = '2028-10-17';
    date.dispatchEvent(new Event('input'));
    (await waitEnabled(check)).click();
    await vi.waitFor(() => expect(client.PUT).toHaveBeenCalled());
    expect(client.PUT.mock.calls[0][0]).toBe(
      '/admin/v1/specialists/{id}/professions/{profession}/verification',
    );
    expect(client.PUT.mock.calls[0][1]).toEqual({
      params: { path: { id: review().id, profession: 'clinical-psychologist' } },
      body: { verified: true, licenceValidUntil: '2028-10-17' },
    });
  });

  it('checks a specialty under its profession', async () => {
    const client = { GET: fn(() => ok(review())), POST: fn(), PUT: fn(() => ok(review())) };
    setUp(['OPERATIONS'], client);
    const root = await render(ApplicationReviewPage, { id: review().id });

    expect(root.textContent).toContain('Clinical psychology');
    // The identity item comes first, already verified; the specialty's is the only one left.
    button(root, 'Mark verified')!.click();
    await vi.waitFor(() => expect(client.PUT).toHaveBeenCalled());
    expect(client.PUT.mock.calls[0][0]).toBe(
      '/admin/v1/specialists/{id}/professions/{profession}/specialties/{specialty}/verification',
    );
  });

  it('sends the reason the applicant will see when rejecting', async () => {
    const client = {
      GET: fn(() => ok(review())),
      POST: fn(() => ok(review({ status: 'REJECTED' }))),
      PUT: fn(),
    };
    setUp(['OPERATIONS'], client);
    const root = await render(ApplicationReviewPage, { id: review().id });
    // The application's own dialog comes after the professions' one.
    const dialog = [...root.querySelectorAll('dialog')].at(-1)!;
    dialog.showModal = vi.fn();
    dialog.close = vi.fn();

    button(root, 'Reject')!.click();
    const reason = dialog.querySelector('textarea')!;
    reason.value = 'The licence could not be confirmed.';
    reason.dispatchEvent(new Event('input'));
    await vi.waitFor(() =>
      expect(button(dialog as unknown as HTMLElement, 'Send')!.disabled).toBe(false),
    );
    button(dialog as unknown as HTMLElement, 'Send')!.click();

    await vi.waitFor(() => expect(client.POST).toHaveBeenCalled());
    expect(client.POST.mock.calls[0][0]).toBe('/admin/v1/specialist-applications/{id}/rejection');
    expect(client.POST.mock.calls[0][1].body).toEqual({
      reason: 'The licence could not be confirmed.',
    });
  });
});

describe('Specialist record (D62)', () => {
  beforeEach(() => TestBed.resetTestingModule());

  const added = (by: string) =>
    review({
      status: 'APPROVED',
      professions: [
        {
          ...review().professions[0]!,
          status: 'APPROVED',
          specialties: [{ ...review().professions[0]!.specialties[0]!, status: 'APPROVED' }],
        },
        {
          profession: 'psychiatrist',
          label: 'Psychiatrist',
          position: 2,
          licenceNumber: 'PSY-9',
          licensingBody: 'Ordre des médecins',
          status: 'PENDING',
          addedAfterApproval: true,
          validUntil: '2028-01-01',
          verification: { by: { id: by, name: 'Checker' }, at: '2026-10-20T08:00:00Z' },
          requestedAt: '2026-10-19T08:00:00Z',
          specialties: [
            {
              specialty: 'psychiatry',
              label: 'Psychiatry',
              status: 'PENDING',
              verification: { by: { id: by, name: 'Checker' }, at: '2026-10-20T08:00:00Z' },
              requestedAt: '2026-10-19T08:00:00Z',
            },
          ],
        },
      ],
    });

  it('lets someone other than the checker approve an added profession', async () => {
    const client = { GET: fn(() => ok(added('ops-9'))), POST: fn(() => ok(added('ops-9'))) };
    setUp(['OPERATIONS'], client, 'ops-1');
    const root = await render(SpecialistRecordPage, { id: review().id });

    expect(root.textContent).toContain('Added after approval');
    button(root, 'Approve profession')!.click();
    await vi.waitFor(() => expect(client.POST).toHaveBeenCalled());
    expect(client.POST.mock.calls[0][0]).toBe(
      '/admin/v1/specialists/{id}/professions/{profession}/approval',
    );
    expect(client.POST.mock.calls[0][1].params.path.profession).toBe('psychiatrist');
  });

  it('tells the checker a second person approves it', async () => {
    const client = { GET: fn(() => ok(added('ops-1'))), POST: fn() };
    setUp(['OPERATIONS'], client, 'ops-1');
    const root = await render(SpecialistRecordPage, { id: review().id });

    expect(root.textContent).toContain('Someone else with access to Specialists approves it.');
    expect(button(root, 'Approve profession')).toBeUndefined();
  });
});

describe('Additions and specialty requests (D62)', () => {
  beforeEach(() => TestBed.resetTestingModule());

  const request: CoreSchemas['SpecialtyRequest'] = {
    id: 'r1',
    specialistId: 's1',
    fullName: 'Nkeng Brigitte',
    profession: 'clinical-psychologist',
    professionLabel: 'Clinical psychologist',
    proposedName: 'Périnatalité',
    note: 'Two years of training.',
    status: 'OPEN',
    requestedAt: '2026-10-20T08:00:00Z',
  };
  const catalogue = {
    specialties: [
      { code: 'clinical-psychology', labelEn: 'Clinical psychology', labelFr: 'Psychologie clinique', position: 1, enabled: true },
    ],
    professions: [],
    sessionLanguages: [],
    helpTopics: [],
    durations: [50],
  };

  it('creates the requested specialty under a main one', async () => {
    const client = {
      GET: fn((path: string) =>
        path === '/admin/v1/specialist-additions'
          ? ok([])
          : path === '/admin/v1/specialty-requests'
            ? ok([request])
            : ok(catalogue),
      ),
      POST: fn(() => ok({ ...request, status: 'CREATED' })),
    };
    setUp(['OPERATIONS'], client);
    const root = await render(PracticeRequestsPage);
    await vi.waitFor(() => expect(root.textContent).toContain('Périnatalité'));
    for (const d of root.querySelectorAll('dialog')) {
      d.showModal = vi.fn();
      d.close = vi.fn();
    }
    expect(root.textContent).toContain('Nothing is waiting.');

    button(root, 'Create specialty')!.click();
    const dialog = root.querySelector('dialog')!;
    const fields = () => dialog.querySelectorAll<HTMLInputElement>('input');
    // The code comes from the proposed name, accents dropped.
    await vi.waitFor(() => expect(fields()[0]?.value).toBe('perinatalite'));
    for (const [i, text] of [
      [1, 'Perinatal psychology'],
      [2, 'Psychologie périnatale'],
    ] as const) {
      fields()[i]!.value = text;
      fields()[i]!.dispatchEvent(new Event('input'));
    }
    const parent = dialog.querySelector<HTMLSelectElement>('select')!;
    parent.value = 'clinical-psychology';
    parent.dispatchEvent(new Event('change'));
    const reason = dialog.querySelector('textarea')!;
    reason.value = 'Asked for by a specialist.';
    reason.dispatchEvent(new Event('input'));
    const send = button(dialog as unknown as HTMLElement, 'Send')!;
    (await waitEnabled(send)).click();

    await vi.waitFor(() => expect(client.POST).toHaveBeenCalled());
    expect(client.POST.mock.calls[0][0]).toBe('/admin/v1/specialty-requests/{requestId}/creation');
    expect(client.POST.mock.calls[0][1].body).toEqual({
      code: 'perinatalite',
      labelEn: 'Perinatal psychology',
      labelFr: 'Psychologie périnatale',
      parentCode: 'clinical-psychology',
      reason: 'Asked for by a specialist.',
    });
  });

  it('shows requests without answers to staff who cannot change the catalogue', async () => {
    const client = {
      GET: fn((path: string) =>
        path === '/admin/v1/specialty-requests' ? ok([request]) : ok([]),
      ),
    };
    setUp(['SUPPORT'], client);
    const root = await render(PracticeRequestsPage);
    await vi.waitFor(() => expect(root.textContent).toContain('Périnatalité'));
    expect(button(root, 'Create specialty')).toBeUndefined();
  });
});

describe('Specialist applications', () => {
  beforeEach(() => TestBed.resetTestingModule());

  it('lists applications oldest first with waiting time and assignee, and takes one for review', async () => {
    const tenDaysAgo = new Date(Date.now() - 10 * 86_400_000).toISOString();
    const client = {
      GET: fn((path: string) =>
        path === '/admin/v1/specialist-applications'
          ? ok({
              counts: { submitted: 1, underReview: 0, changesRequested: 0 },
              items: [
                {
                  id: 'a1',
                  fullName: 'Manga Joël',
                  profession: 'Licensed counsellor',
                  languages: [{ code: 'fr', proficiency: 'NATIVE' }],
                  status: 'SUBMITTED',
                  submittedAt: tenDaysAgo,
                },
              ],
            })
          : ok([]),
      ),
      POST: fn(() => ok(review())),
    };
    setUp(['OPERATIONS'], client);
    const root = await render(ApplicationsPage);

    await vi.waitFor(() => expect(root.textContent).toContain('Manga Joël'));
    expect(root.textContent).toContain('Submitted (1)');
    expect(root.textContent).toContain('French native');
    expect(root.textContent).toContain('10 days');
    expect(root.textContent).toContain('Unassigned');

    const navigate = vi.spyOn(TestBed.inject(Router), 'navigate').mockResolvedValue(true);
    root.querySelector<HTMLButtonElement>('td.action button')!.click();
    await vi.waitFor(() => expect(navigate).toHaveBeenCalledWith(['/applications', 'a1']));
    expect(client.POST.mock.calls[0][0]).toBe('/admin/v1/specialist-applications/{id}/review');
  });
});

async function waitEnabled(b: HTMLButtonElement): Promise<HTMLButtonElement> {
  await vi.waitFor(() => expect(b.disabled).toBe(false));
  return b;
}
