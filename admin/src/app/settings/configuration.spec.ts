import { TestBed } from '@angular/core/testing';
import { beforeEach, describe, expect, it } from 'vitest';

import { AiModelsPage } from '../ai/ai-models.page';
import { NotificationCopyPage } from '../content/notification-copy.page';
import { PaymentProvidersPage } from '../payments/providers.page';
import { ReviewedListsPage } from '../shared/reviewed-lists.page';
import { button, fn, ok, render, route, sees, setUp, stubDialogs } from '../testing/harness';
import { JobsPage } from './jobs.page';
import { MessageSendersPage } from './senders.page';

/** The D61 screens: providers, senders, jobs, notification copy, AI models and reviewed lists. */
describe('Configuration screens (D61)', () => {
  beforeEach(() => TestBed.resetTestingModule());

  const provider = {
    code: 'notchpay',
    adapter: 'NOTCHPAY',
    displayName: 'Notch Pay',
    mode: 'LIVE',
    status: 'ACTIVE',
    secretRef: 'config',
    version: 2,
    updatedBy: 'System',
    updatedAt: '2026-10-25T09:00:00Z',
    keysFound: true,
    circuit: 'CLOSED',
    inFlight: 3,
    clearing: 45000,
    webhookPath: '/v1/webhooks/payments/notchpay',
  };
  const routes = {
    version: 1,
    routes: [{ purpose: 'COLLECT', method: 'MTN_MOMO', priority: 1, providerCode: 'notchpay' }],
  };

  it('shows providers and routes, and only a Super admin may change them', async () => {
    const answers = {
      '/admin/v1/payment-providers': () => ok([provider]),
      '/admin/v1/payment-routes': () => ok(routes),
    };
    setUp(['FINANCE'], { GET: fn(route(answers)) });
    const finance = await render(PaymentProvidersPage);
    await sees(finance.root, 'Notch Pay · notchpay');
    expect(finance.root.textContent).toContain('Keys found');
    expect(finance.root.textContent).toContain('3 on their way');
    expect(button(finance.root, 'Change')).toBeUndefined();

    TestBed.resetTestingModule();
    const put = fn(() => ok(routes));
    setUp(['SUPER_ADMIN'], { GET: fn(route(answers)), PUT: put });
    const admin = await render(PaymentProvidersPage);
    await sees(admin.root, 'Notch Pay · notchpay');
    stubDialogs(admin.root);
    const page = admin.fixture.componentInstance;
    page.askRoutes();
    page.setRoute('COLLECT:MTN_MOMO', 'notchpay, sandbox');
    page.reason.set('Second account');
    await page.saveRoutes();
    const body = put.mock.calls[0]![1].body;
    expect(body.version).toBe(1);
    expect(body.routes).toContainEqual({
      purpose: 'COLLECT',
      method: 'MTN_MOMO',
      priority: 2,
      providerCode: 'sandbox',
    });
  });

  it('warns about jobs safety depends on and reads each service from its own client', async () => {
    const job = {
      name: 'core.crisis.escalate',
      description: 'Escalates unacknowledged crisis alerts',
      status: 'ACTIVE',
      caution: true,
      kind: 'INTERVAL',
      schedule: 'PT30S',
      version: 2,
      updatedBy: 'S. Admin',
      updatedAt: '2026-10-25T09:00:00Z',
      pendingRestart: true,
    };
    const aiGet = fn(() => ok([{ ...job, name: 'ai.scribe.sweep', description: 'Deletes drafts' }]));
    setUp(['OPERATIONS'], { GET: fn(route({ '/admin/v1/jobs': () => ok([job]) })) }, { ai: { GET: aiGet } });
    const { root, fixture, settle } = await render(JobsPage);
    await sees(root, 'Escalates unacknowledged crisis alerts');
    expect(root.textContent).toContain('Safety or money depends on this job');
    expect(root.textContent).toContain('in force after the next restart');
    fixture.componentInstance.show('ai');
    await settle();
    await sees(root, 'Deletes drafts');
    expect(aiGet).toHaveBeenCalled();
  });

  it('edits a message in both languages and can bring back the approved wording', async () => {
    const message = {
      key: 'push.rate-session',
      channel: 'push',
      forStaff: false,
      en: 'How did it go?',
      fr: 'Comment ça s’est passé ?',
      approvedEn: 'How was your appointment? Tell us in one tap.',
      approvedFr: 'Comment s’est passé votre rendez-vous ? Répondez en un geste.',
      version: 1,
      updatedBy: 'C. Editor',
      updatedAt: '2026-10-25T09:00:00Z',
    };
    const put = fn(() => ok(message));
    setUp(
      ['CONTENT_EDITOR'],
      { GET: fn(route({ '/admin/v1/notification-copy': () => ok([message]) })), PUT: put },
    );
    const { root, fixture } = await render(NotificationCopyPage);
    await sees(root, 'How did it go?');
    stubDialogs(root);
    const page = fixture.componentInstance;
    page.ask(message);
    page.restore(message);
    page.reason.set('Back to the approved wording');
    await page.save();
    expect(put.mock.calls[0]![1].body).toEqual({
      version: 1,
      en: message.approvedEn,
      fr: message.approvedFr,
      reason: 'Back to the approved wording',
    });
  });

  it('says no sender is set up yet', async () => {
    setUp(['SUPER_ADMIN'], { GET: fn(route({ '/admin/v1/message-senders': () => ok([]) })) });
    const { root } = await render(MessageSendersPage);
    await sees(root, 'No sender yet');
  });

  it('shows a pending AI route and whether its evaluation passed', async () => {
    setUp(
      ['SUPER_ADMIN'],
      {},
      {
        ai: {
          GET: fn(
            route({
              '/admin/v1/ai-models': () => ok([]),
              '/admin/v1/ai-routes': () =>
                ok([
                  {
                    feature: 'TRANSLATION',
                    live: ['local-a'],
                    pending: ['local-b'],
                    proposedBy: 'S. Admin',
                    pendingPassed: false,
                  },
                ]),
            }),
          ),
        },
      },
    );
    const { root } = await render(AiModelsPage);
    await sees(root, 'Pending');
    expect(root.textContent).toContain('local-b');
    expect(root.textContent).toContain('not evaluated yet');
    expect(button(root, 'Put live')).toBeDefined();
  });

  it('lets a clinician sign off a draft only when someone else wrote it', async () => {
    const summary = {
      code: 'ai.safety.phrases',
      description: 'Safety monitor phrases',
      drafts: 1,
      drafters: ['CLINICAL_REVIEWER', 'SUPER_ADMIN'],
      approvers: ['CLINICAL_REVIEWER'],
    };
    const detail = {
      code: 'ai.safety.phrases',
      description: 'Safety monitor phrases',
      inForce: { SELF_HARM: ['cut myself'] },
      versions: [
        {
          list: 'ai.safety.phrases',
          version: 1,
          status: 'DRAFT',
          entries: {},
          note: 'More phrases',
          draftedBy: 'staff-1',
          draftedByName: 'Nadine Ebai',
          draftedAt: '2026-10-25T09:00:00Z',
        },
      ],
    };
    setUp(
      ['CLINICAL_REVIEWER'],
      {},
      {
        ai: {
          GET: fn(
            route({
              '/admin/v1/lists': () => ok([summary]),
              '/admin/v1/lists/{code}': () => ok(detail),
            }),
          ),
        },
      },
    );
    const { root, fixture, settle } = await render(ReviewedListsPage, { service: 'ai' });
    await sees(root, 'Safety monitor phrases');
    await fixture.componentInstance.open(summary);
    await settle();
    await sees(root, 'More phrases');
    // The signed-in clinician drafted it: someone else must decide.
    expect(button(root, 'Sign off')?.disabled).toBe(true);
  });
});
