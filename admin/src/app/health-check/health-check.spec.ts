import { TestBed } from '@angular/core/testing';
import { beforeEach, describe, expect, it } from 'vitest';

import type { CoreSchemas } from '../api/core-api';
import { button, fn, ok, problem, render, route, sees, setUp } from '../testing/harness';
import { HealthCheckVersionPage } from './health-check-version.page';
import { HealthCheckPage } from './health-check.page';

const catalogue = {
  specialties: [
    {
      code: 'clinical-psychology',
      labelEn: 'Clinical psychology',
      labelFr: 'Psychologie clinique',
      position: 1,
      enabled: true,
    },
    {
      code: 'counselling',
      labelEn: 'Counselling',
      labelFr: 'Conseil psychologique',
      position: 2,
      enabled: true,
    },
  ],
  sessionLanguages: [],
  helpTopics: [],
  durations: [50],
};

const safety: CoreSchemas['HealthCheckQuestion'] = {
  code: 'safety',
  kind: 'SAFETY',
  multi: false,
  step: 2,
  textEn: 'In the last 2 weeks, have you had thoughts that you would be better off dead?',
  textFr: 'Au cours des 2 dernières semaines…',
  locked: true,
  options: [
    { code: 'no', labelEn: 'No', labelFr: 'Non' },
    { code: 'yes_often', labelEn: 'Yes, often', labelFr: 'Oui, souvent' },
  ],
};
const topics: CoreSchemas['HealthCheckQuestion'] = {
  code: 'topics',
  kind: 'CHOICE',
  multi: true,
  step: 1,
  textEn: 'What would you like help with?',
  textFr: 'Pour quoi souhaitez-vous de l’aide ?',
  options: [
    {
      code: 'worry',
      labelEn: 'Worry or anxiety',
      labelFr: 'Inquiétude ou anxiété',
      topic: 'anxiety',
    },
    {
      code: 'stress',
      labelEn: 'Stress at work or school',
      labelFr: 'Stress au travail ou à l’école',
    },
  ],
};

function version(
  overrides: Partial<CoreSchemas['HealthCheckVersion']> = {},
): CoreSchemas['HealthCheckVersion'] {
  return {
    id: 'v11',
    number: '1.1',
    status: 'DRAFT',
    questions: [topics, safety],
    rules: [
      {
        code: 'stress.work',
        when: [{ question: 'topics', anyOf: ['stress'] }],
        specialty: 'counselling',
        points: 2,
        reasonEn: 'You said stress at work or school is part of it.',
        reasonFr: 'Vous avez dit que le stress au travail ou à l’école y contribue.',
      },
    ],
    specialtyOrder: ['clinical-psychology', 'counselling'],
    testSets: [],
    reviews: [],
    problems: [],
    draftedBy: 'Dr. Fomba Grace',
    canApprove: false,
    ...overrides,
  };
}

const rows: CoreSchemas['HealthCheckVersionRow'][] = [
  {
    id: 'v11',
    number: '1.1',
    status: 'IN_REVIEW',
    draftedBy: 'Dr. Fomba Grace',
    completions: 0,
    safetyStops: 0,
  },
  {
    id: 'v1',
    number: '1.0',
    status: 'PUBLISHED',
    draftedBy: 'Dr. Fomba Grace',
    approvedBy: 'T. Arnaud',
    completions: 312,
    safetyStops: 4,
  },
];

describe('Health check', () => {
  beforeEach(() => TestBed.resetTestingModule());

  it('shows counts, what the published version suggests and every version, never answers', async () => {
    setUp(['CONTENT_EDITOR'], {
      GET: fn(
        route({
          '/admin/v1/health-check/versions': () => ok(rows),
          '/admin/v1/health-check/stats': () =>
            ok({
              completedLast7Days: 312,
              startedLast7Days: 400,
              safetyStopsLast7Days: 4,
              suggestionsLast30Days: [
                { code: 'clinical-psychology', count: 46 },
                { code: 'counselling', count: 54 },
              ],
            }),
          '/admin/v1/catalogue': () => ok(catalogue),
        }),
      ),
    });
    const { root } = await render(HealthCheckPage);
    await sees(root, 'What v1.0 suggests, last 30 days');
    expect(root.textContent).toContain('78%');
    expect(root.textContent).toContain('312');
    await sees(root, 'Counselling');
    expect(root.textContent).toContain('54%');
    expect(root.textContent).toContain('In review');
    // Content editors look; they do not start versions.
    expect(button(root, 'Start a new version')).toBeUndefined();
  });

  it('keeps the safety question locked and blocks submitting with problems', async () => {
    setUp(['CLINICAL_REVIEWER'], {
      GET: fn(
        route({
          '/admin/v1/health-check/versions/{versionId}': () =>
            ok(version({ problems: ['text:medicine'] })),
          '/admin/v1/health-check/versions': () => ok([rows[0]]),
          '/admin/v1/catalogue': () => ok(catalogue),
        }),
      ),
      POST: fn(() => ok([])),
      PUT: fn(() => ok(version())),
    });
    const { root, settle } = await render(HealthCheckVersionPage, { id: 'v11' });
    await sees(root, 'What would you like help with?');
    expect(root.textContent).toContain('Locked');
    // The safety question cannot be opened for editing; a choice question can.
    expect(button(root, safety.textEn)).toBeUndefined();
    button(root, 'What would you like help with?')!.click();
    await settle();
    expect(root.textContent).toContain('Question 1 · topics');

    button(root, '3 Test and review')!.click();
    await settle();
    await sees(root, 'EN and FR text for medicine');
    expect(button(root, 'Submit for review')!.disabled).toBe(true);
  });

  it('approves only with the whole checklist, and says when the author tries', async () => {
    const client = {
      GET: fn(
        route({
          '/admin/v1/health-check/versions/{versionId}': () =>
            ok(version({ status: 'IN_REVIEW', canApprove: true })),
          '/admin/v1/health-check/versions': () => ok([rows[0]]),
          '/admin/v1/catalogue': () => ok(catalogue),
        }),
      ),
      POST: fn((path: string) =>
        path.endsWith('/test-run')
          ? ok([
              {
                name: 'Recent stress at work',
                safetyStop: false,
                specialty: 'counselling',
                expected: 'counselling',
              },
              { name: 'Safety answer', safetyStop: true },
            ])
          : ok(version({ status: 'IN_REVIEW', approvedBy: 'T. Arnaud' })),
      ),
    };
    setUp(['CLINICAL_REVIEWER'], client, { sub: 'reviewer-2' });
    const { root, settle } = await render(HealthCheckVersionPage, { id: 'v11' });
    await sees(root, 'What would you like help with?');
    button(root, '3 Test and review')!.click();
    await settle();
    await sees(root, 'Urgent help');
    const approve = button(root, 'Approve v1.1')!;
    expect(approve.disabled).toBe(true);
    for (const box of root.querySelectorAll<HTMLInputElement>('aside input[type=checkbox]'))
      box.click();
    await settle();
    expect(approve.disabled).toBe(false);
    approve.click();
    await settle();
    expect(client.POST).toHaveBeenCalledWith('/admin/v1/health-check/versions/{versionId}/review', {
      params: { path: { versionId: 'v11' } },
      body: { approve: true },
    });
    await sees(root, 'Approved by T. Arnaud. It can now be published.');

    TestBed.resetTestingModule();
    setUp(['CLINICAL_REVIEWER'], {
      ...client,
      POST: fn((path: string) =>
        path.endsWith('/test-run')
          ? ok([])
          : problem(
              409,
              'health-check.same-person',
              'The person who wrote a version cannot approve it.',
            ),
      ),
    });
    const own = await render(HealthCheckVersionPage, { id: 'v11' });
    await sees(own.root, 'What would you like help with?');
    button(own.root, '3 Test and review')!.click();
    await own.settle();
    for (const box of own.root.querySelectorAll<HTMLInputElement>('aside input[type=checkbox]'))
      box.click();
    await own.settle();
    button(own.root, 'Approve v1.1')!.click();
    await own.settle();
    await sees(own.root, 'The person who wrote a version cannot approve it.');
  });
});
