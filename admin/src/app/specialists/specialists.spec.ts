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
    licence: { licenceNumber: 'ONPC-1234', licensingBody: 'Ordre national' },
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

function setUp(roles: string[], client: Record<string, ReturnType<typeof fn>>) {
  TestBed.configureTestingModule({
    imports: [
      TranslocoTestingModule.forRoot({
        langs: { en, fr },
        translocoConfig: { availableLangs: ['en', 'fr'], defaultLang: 'en' },
      }),
    ],
    providers: [
      provideZonelessChangeDetection(),
      provideRouter([{ path: 'applications/:id', children: [] }]),
      { provide: CoreApi, useValue: { client, document: vi.fn() } },
    ],
  });
  TestBed.inject(StaffSession).fromToken({
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

  it('keeps Approve locked until all six items are verified', async () => {
    const client = { GET: fn(() => ok(review())), POST: fn(), PUT: fn() };
    setUp(['OPERATIONS'], client);
    const root = await render(ApplicationReviewPage, { id: review().id });

    expect(root.textContent).toContain('5 of 6 items verified.');
    expect(root.textContent).toContain('Approve unlocks when every item is verified.');
    expect(button(root, 'Approve')!.disabled).toBe(true);
    expect(root.textContent).toContain('Verified by N. Ebai');
  });

  it('lets Operations approve once every item is verified', async () => {
    const approved = review({ status: 'APPROVED', verifiedItems: 6 });
    const client = {
      GET: fn(() => ok(review({ verifiedItems: 6 }))),
      POST: fn(() => ok(approved)),
      PUT: fn(),
    };
    setUp(['OPERATIONS'], client);
    const root = await render(ApplicationReviewPage, { id: review().id });

    const approve = button(root, 'Approve')!;
    expect(approve.disabled).toBe(false);
    approve.click();
    await vi.waitFor(() => expect(client.POST).toHaveBeenCalled());
    expect(client.POST.mock.calls[0][0]).toBe('/admin/v1/specialist-applications/{id}/approval');
  });

  it('shows Support the application without any action', async () => {
    const client = { GET: fn(() => ok(review())), POST: fn(), PUT: fn() };
    setUp(['SUPPORT'], client);
    const root = await render(ApplicationReviewPage, { id: review().id });

    expect(root.textContent).toContain('Kamga Estelle');
    for (const label of ['Approve', 'Reject', 'Request changes', 'Mark verified']) {
      expect(button(root, label)).toBeUndefined();
    }
  });

  it('sends the licence expiry date when verifying the licence', async () => {
    const client = {
      GET: fn(() => ok(review())),
      POST: fn(),
      PUT: fn(() => ok(review())),
    };
    setUp(['OPERATIONS'], client);
    const root = await render(ApplicationReviewPage, { id: review().id });

    const date = root.querySelector<HTMLInputElement>('input[type="date"]')!;
    date.value = '2028-10-17';
    date.dispatchEvent(new Event('input'));
    // The licence card's button is the first enabled "Mark verified".
    const verify = [...root.querySelectorAll('button')].filter(
      (b) => b.textContent?.trim() === 'Mark verified',
    );
    (await waitEnabled(verify[0]!)).click();
    await vi.waitFor(() => expect(client.PUT).toHaveBeenCalled());
    expect(client.PUT.mock.calls[0][1]).toEqual({
      params: { path: { id: review().id, item: 'LICENCE' } },
      body: { verified: true, licenceValidUntil: '2028-10-17' },
    });
  });

  it('sends the reason the applicant will see when rejecting', async () => {
    const client = {
      GET: fn(() => ok(review())),
      POST: fn(() => ok(review({ status: 'REJECTED' }))),
      PUT: fn(),
    };
    setUp(['OPERATIONS'], client);
    const root = await render(ApplicationReviewPage, { id: review().id });
    const dialog = root.querySelector('dialog')!;
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
