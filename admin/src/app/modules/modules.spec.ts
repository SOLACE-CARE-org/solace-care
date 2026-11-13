import { TestBed } from '@angular/core/testing';
import { Router } from '@angular/router';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import type { TrackingSchemas } from '../api/tracking-api';
import {
  button,
  fn,
  ok,
  problem,
  render,
  route,
  sees,
  setUp,
  stubDialogs,
} from '../testing/harness';
import { ModuleBuilderPage } from './module-builder.page';
import { ModuleReviewPage } from './module-review.page';
import { ModulesPage } from './modules.page';

const mood: TrackingSchemas['Element'] = {
  code: 'mood',
  version: '1',
  kind: 'DAY_STATE',
  valueType: 'INT',
  constraints: { min: 1, max: 5 },
  inputView: 'scale_5',
  icon: 'mood',
  signals: ['daily_value'],
  labels: { en: { name: 'Mood' }, fr: { name: 'Humeur' } },
  minAppVersion: '1.0.0',
};
const stress: TrackingSchemas['Element'] = {
  ...mood,
  code: 'stress_level',
  valueType: 'ENUM',
  constraints: { values: ['low', 'some', 'high'] },
  inputView: 'choice_chips',
  labels: {
    en: { name: 'Stress', values: { low: 'Low', some: 'Some', high: 'High' } },
    fr: { name: 'Stress', values: { low: 'Faible', some: 'Un peu', high: 'Élevé' } },
  },
};

// What Tracking offers the rules of a draft binding stress_level (D61).
const vocabulary: TrackingSchemas['RuleVocabulary'] = {
  signals: [
    {
      key: 'daily_value:stress_level',
      signal: 'daily_value',
      element: 'stress_level',
      kind: 'DAILY',
      valueType: 'ENUM',
      values: ['low', 'some', 'high'],
      operators: [{ code: 'days_in_window', comparisons: ['eq', 'in'] }],
    },
    {
      key: 'menstrual_cycle.length_days',
      signal: 'menstrual_cycle.length_days',
      kind: 'EPISODE',
      valueType: 'INT',
      values: [],
      labels: { en: 'Cycle length', fr: 'Durée du cycle' },
      operators: [{ code: 'consecutive_episodes', comparisons: ['gt', 'gte', 'lt', 'lte', 'eq'] }],
    },
  ],
  operators: [
    { code: 'consecutive_episodes', fields: ['count'], evidenceKeys: ['n', 'threshold'] },
    {
      code: 'days_in_window',
      fields: ['mode', 'count', 'window', 'minData'],
      evidenceKeys: ['n', 'count', 'window', 'logged', 'threshold'],
    },
    {
      code: 'average_in_window',
      fields: ['window', 'minData'],
      evidenceKeys: ['average', 'window', 'logged', 'threshold'],
    },
  ],
  unlessFields: ['trying_to_conceive'],
  questions: [
    {
      code: 'trying_to_conceive',
      version: 1,
      type: 'YES_NO',
      scope: 'PROFILE',
      labels: {
        en: { question: 'Are you trying to conceive?' },
        fr: { question: "Essayez-vous d'avoir un enfant ?" },
      },
      options: [],
    },
  ],
};

const rows: TrackingSchemas['ModuleRow'][] = [
  {
    id: 'm1',
    code: 'pcos',
    accentColour: 'track-pink',
    name: { en: 'PCOS & cycle', fr: 'SOPK et cycle' },
    enrolled: 1240,
    versions: [
      { id: 'v11', number: '1.1', status: 'IN_REVIEW', elements: 6, rules: 4 },
      { id: 'v1', number: '1', status: 'PUBLISHED', elements: 6, rules: 4 },
    ],
  },
  {
    id: 'm2',
    code: 'stress',
    accentColour: 'brand',
    name: { en: 'Stress', fr: 'Stress' },
    versions: [{ id: 's1', number: '1', status: 'DRAFT', elements: 3, rules: 0 }],
  },
];

function draft(overrides: Partial<TrackingSchemas['ModuleVersion']> = {}) {
  return {
    id: 's1',
    moduleId: 'm2',
    moduleCode: 'stress',
    accentColour: 'brand',
    number: '1',
    status: 'DRAFT',
    name: { en: 'Stress', fr: 'Stress' },
    description: { en: '', fr: '' },
    audience: { en: '', fr: '' },
    imageAlt: { en: '', fr: '' },
    elements: [{ code: 'stress_level', version: '1', required: true, position: 0 }],
    rules: [],
    reviews: [],
    problems: ['image'],
    warnings: [],
    draftedBy: 'Nadine Ebai',
    canApprove: false,
    ...overrides,
  } as TrackingSchemas['ModuleVersion'];
}

describe('Tracking modules', () => {
  beforeEach(() => TestBed.resetTestingModule());

  it('lists modules with what is published and what is being worked on', async () => {
    setUp(
      ['CONTENT_EDITOR'],
      {},
      {
        tracking: { GET: fn(route({ '/admin/v1/tracking/modules': () => ok(rows) })) },
      },
    );
    const { root } = await render(ModulesPage);
    await sees(root, 'PCOS & cycle');
    expect(root.textContent).toContain('v1.1 in review');
    expect(root.textContent).toContain('v1 draft');
    expect(root.textContent).toContain('Never');
    // Content editors look; only Operations and Super admins create.
    expect(button(root, 'New module')).toBeUndefined();
  });

  it('creates a module and opens its first draft', async () => {
    const tracking = {
      GET: fn(route({ '/admin/v1/tracking/modules': () => ok([]) })),
      POST: fn(() => ok(draft(), 201)),
    };
    setUp(['OPERATIONS'], {}, { tracking });
    const navigate = vi.spyOn(TestBed.inject(Router), 'navigate').mockResolvedValue(true);
    const { root, fixture, settle } = await render(ModulesPage);
    stubDialogs(root);
    await sees(root, 'No modules yet');
    button(root, 'New module')!.click();
    fixture.componentInstance.code.set('stress');
    fixture.componentInstance.accent.set('track-gold');
    await settle();
    await fixture.componentInstance.create();
    expect(tracking.POST).toHaveBeenCalledWith('/admin/v1/tracking/modules', {
      body: { code: 'stress', accentColour: 'track-gold' },
    });
    expect(navigate).toHaveBeenCalledWith(['/modules/versions', 's1']);
  });

  it('says when the code is taken', async () => {
    setUp(
      ['OPERATIONS'],
      {},
      {
        tracking: {
          GET: fn(route({ '/admin/v1/tracking/modules': () => ok([]) })),
          POST: fn(() => problem(409, 'resource.conflict', 'Taken')),
        },
      },
    );
    const { root, fixture, settle } = await render(ModulesPage);
    stubDialogs(root);
    fixture.componentInstance.code.set('stress');
    await fixture.componentInstance.create();
    await settle();
    expect(root.textContent).toContain('A module with this code already exists.');
  });

  it('builds a draft: elements in order, then rules that cannot be submitted with problems', async () => {
    const tracking = {
      GET: fn(
        route({
          '/admin/v1/tracking/versions/{versionId}': () => ok(draft()),
          '/admin/v1/tracking/elements': () => ok([mood, stress]),
          '/admin/v1/tracking/versions/{versionId}/rule-vocabulary': () => ok(vocabulary),
        }),
      ),
      PUT: fn(() => ok(draft())),
      POST: fn(() => ok([])),
    };
    setUp(['OPERATIONS'], { GET: fn(() => problem(403, 'access.denied', 'No')) }, { tracking });
    const { root, settle } = await render(ModuleBuilderPage, { id: 's1' });
    await sees(root, 'Name and description');

    button(root, '2 Elements')!.click();
    await settle();
    await sees(root, 'In this module · 1 of 6');
    button(root, 'Add')!.click();
    await settle();
    expect(root.textContent).toContain('In this module · 2 of 6');
    button(root, 'Next: rules')!.click();
    await settle();
    expect(tracking.PUT).toHaveBeenCalledWith('/admin/v1/tracking/versions/{versionId}/elements', {
      params: { path: { versionId: 's1' } },
      body: [
        { code: 'stress_level', version: '1', required: true },
        { code: 'mood', version: '1', required: true },
      ],
    });

    await sees(root, 'Before it can be submitted');
    expect(root.textContent).toContain('A card image');
    // The rule form offers what the version's rule vocabulary says, and nothing else (D61).
    const selects = root.querySelectorAll<HTMLSelectElement>('fieldset.condition select');
    const options = (i: number) => [...selects[i]!.options].map((o) => o.textContent?.trim());
    // Episode signals carry their model's own labels.
    expect(options(0)).toEqual(['Stress, daily', 'Cycle length']);
    expect(options(1)).toEqual(['days in window']);
    expect(options(2)).toEqual(['equal to', 'one of']);
    expect(button(root, 'Submit for clinical review')!.disabled).toBe(true);
  });

  it('groups elements under one label and saves how the app shows the module (D61)', async () => {
    const tracking = {
      GET: fn(
        route({
          '/admin/v1/tracking/versions/{versionId}': () => ok(draft()),
          '/admin/v1/tracking/elements': () => ok([mood, stress]),
          '/admin/v1/tracking/versions/{versionId}/rule-vocabulary': () => ok(vocabulary),
        }),
      ),
      PUT: fn(() => ok(draft())),
      POST: fn(() => ok([])),
    };
    setUp(['OPERATIONS'], { GET: fn(() => problem(403, 'access.denied', 'No')) }, { tracking });
    const { root, settle } = await render(ModuleBuilderPage, { id: 's1' });
    await sees(root, 'Name and description');
    button(root, '2 Elements')!.click();
    await settle();
    await sees(root, 'How the app shows it');
    button(root, 'Add')!.click();
    await settle();
    // The second element joins the group of the one above it, label and all.
    button(root, 'Group')!.click();
    await settle();
    button(root, 'Group')!.click();
    await settle();
    const change = (el: HTMLInputElement | HTMLSelectElement, value: string) => {
      el.value = value;
      el.dispatchEvent(new Event('change'));
      el.dispatchEvent(new Event('input'));
    };
    const groupInputs = root.querySelectorAll<HTMLInputElement>('li.group input');
    change(groupInputs[1]!, 'Feelings');
    change(groupInputs[2]!, 'Ressenti');
    await settle();
    root.querySelector<HTMLInputElement>('fieldset.trends input')!.click();
    change(root.querySelector<HTMLSelectElement>('select[name=quickLog]')!, 'stress_level');
    await settle();
    button(root, 'Next: rules')!.click();
    await settle();
    const group = { code: 'stress_level', labels: { en: 'Feelings', fr: 'Ressenti' } };
    expect(tracking.PUT).toHaveBeenCalledWith('/admin/v1/tracking/versions/{versionId}/elements', {
      params: { path: { versionId: 's1' } },
      body: [
        { code: 'stress_level', version: '1', required: true, group },
        { code: 'mood', version: '1', required: true, group },
      ],
    });
    expect(tracking.PUT).toHaveBeenCalledWith(
      '/admin/v1/tracking/versions/{versionId}/presentation',
      {
        params: { path: { versionId: 's1' } },
        body: { trends: ['daily_value:stress_level'], quickLog: 'stress_level' },
      },
    );
  });

  it('asks published context questions on enrolment and lets a rule switch off on an answer', async () => {
    const tracking = {
      GET: fn(
        route({
          '/admin/v1/tracking/versions/{versionId}': () => ok(draft()),
          '/admin/v1/tracking/elements': () => ok([mood, stress]),
          '/admin/v1/tracking/context-questions': () => ok(vocabulary.questions),
          '/admin/v1/tracking/versions/{versionId}/rule-vocabulary': () => ok(vocabulary),
        }),
      ),
      PUT: fn(() => ok(draft())),
      POST: fn(() => ok(draft())),
    };
    setUp(['OPERATIONS'], { GET: fn(() => problem(403, 'access.denied', 'No')) }, { tracking });
    const { root, settle } = await render(ModuleBuilderPage, { id: 's1' });
    await sees(root, 'Are you trying to conceive?');

    // Step 1: the module asks the published question once on enrolment (D61).
    const ask = [...root.querySelectorAll<HTMLLabelElement>('fieldset.questions label')].find(
      (l) => l.textContent?.includes('Are you trying to conceive?'),
    )!;
    ask.querySelector('input')!.click();
    await settle();
    button(root, 'Save draft')!.click();
    await settle();
    expect(tracking.PUT).toHaveBeenCalledWith('/admin/v1/tracking/versions/{versionId}/questions', {
      params: { path: { versionId: 's1' } },
      body: ['trying_to_conceive'],
    });

    // Step 3: "unless" offers the published questions and their answers, nothing else.
    button(root, '3 Rules')!.click();
    await settle();
    button(root, 'Add a rule')!.click();
    await settle();
    button(root, 'Add an answer that switches it off')!.click();
    await settle();
    const unless = root.querySelectorAll<HTMLSelectElement>('fieldset.unless select');
    expect([...unless[0]!.options].map((o) => o.textContent?.trim())).toEqual([
      'Are you trying to conceive?',
    ]);
    expect([...unless[1]!.options].map((o) => o.textContent?.trim())).toEqual(['Yes', 'No']);
  });

  it('names each test history from Tracking and adds one next to an untested rule (D61)', async () => {
    const rule = {
      id: 'r1',
      code: 'stress.high_days',
      conditions: [
        {
          signal: 'daily_value',
          element: 'stress_level',
          operator: 'days_in_window',
          comparison: 'eq',
          threshold: 'high',
          count: 5,
          window: 14,
          minData: 7,
        },
      ],
      outcome: 'PATTERN',
      specialtyCodes: [],
      messageEn: 'You logged high stress on {n} of the last {window} days.',
      messageFr: 'Vous avez noté un stress élevé {n} jours sur les {window} derniers.',
      cooldownDays: 30,
    } as TrackingSchemas['Rule'];
    const untested = draft({ rules: [rule], problems: ['tests:stress.high_days'] });
    const tracking = {
      GET: fn(
        route({
          '/admin/v1/tracking/versions/{versionId}': () => ok(untested),
          '/admin/v1/tracking/elements': () => ok([mood, stress]),
          '/admin/v1/tracking/context-questions': () => ok(vocabulary.questions),
          '/admin/v1/tracking/versions/{versionId}/rule-vocabulary': () => ok(vocabulary),
        }),
      ),
      PUT: fn(() => ok(untested)),
      POST: fn((path: string) =>
        path.endsWith('/test-run')
          ? ok([
              {
                history: 'good_mood',
                historyLabels: { en: 'Good mood', fr: 'Bonne humeur' },
                ruleCode: 'stress.high_days',
                fires: false,
              },
            ])
          : ok({ code: 'high_stress', seeded: false }, 201),
      ),
    };
    setUp(['CLINICAL_REVIEWER'], { GET: fn(() => problem(403, 'access.denied', 'No')) }, { tracking });
    const { root, settle } = await render(ModuleBuilderPage, { id: 's1' });
    await sees(root, 'Name and description');
    button(root, '3 Rules')!.click();
    await settle();
    button(root, 'Run again')!.click();
    await settle();
    await sees(root, 'Good mood');

    button(root, 'Add a test history')!.click();
    await settle();
    const form = root.querySelector('sl-history-form')!;
    const type = (selector: string, value: string) => {
      const el = form.querySelector<HTMLInputElement | HTMLSelectElement>(selector)!;
      el.value = value;
      el.dispatchEvent(new Event(el instanceof HTMLSelectElement ? 'change' : 'input'));
    };
    type('input[name=code]', 'high_stress');
    type('input[name=nameEn]', 'High stress');
    type('input[name=nameFr]', 'Stress élevé');
    await settle();
    // Part 1 holds the pattern and element selects, then the days and value inputs.
    const part = () => form.querySelectorAll('fieldset.part')[0]!;
    const set = (el: HTMLInputElement | HTMLSelectElement, value: string) => {
      el.value = value;
      el.dispatchEvent(new Event(el instanceof HTMLSelectElement ? 'change' : 'input'));
    };
    set(part().querySelectorAll('select')[1]!, 'stress_level');
    await settle();
    expect(form.textContent).toContain('Values: low, some, high');
    set(part().querySelectorAll('input')[1]!, 'high');
    set(form.querySelectorAll('fieldset.part')[1]!.querySelector('select')!, 'false');
    await settle();
    button(root, 'Add history')!.click();
    await settle();
    expect(tracking.POST).toHaveBeenCalledWith('/admin/v1/tracking/synthetic-histories', {
      body: {
        code: 'high_stress',
        labels: { en: 'High stress', fr: 'Stress élevé' },
        recipe: {
          parts: [{ pattern: 'constant', element: 'stress_level', days: 14, value: 'high' }],
        },
        context: { trying_to_conceive: false },
      },
    });
    // The form closes and the rule is tested again against the new history.
    await settle();
    expect(root.querySelector('sl-history-form')).toBeNull();
  });

  it('opens a published version read-only, with a way to start a new draft', async () => {
    setUp(
      ['OPERATIONS'],
      { GET: fn(() => problem(403, 'access.denied', 'No')) },
      {
        tracking: {
          GET: fn(
            route({
              '/admin/v1/tracking/versions/{versionId}': () => ok(draft({ status: 'PUBLISHED' })),
              '/admin/v1/tracking/elements': () => ok([mood, stress]),
            }),
          ),
        },
      },
    );
    const { root } = await render(ModuleBuilderPage, { id: 's1' });
    await sees(root, 'cannot be changed any more');
    expect(button(root, 'Start a new draft')).toBeDefined();
    expect(button(root, 'Save draft')).toBeUndefined();
  });

  it('approves only with every check ticked, and never your own draft', async () => {
    const inReview = draft({
      status: 'IN_REVIEW',
      problems: [],
      canApprove: true,
      rules: [
        {
          id: 'r1',
          code: 'stress.high_days',
          conditions: [
            {
              signal: 'daily_value',
              element: 'stress_level',
              operator: 'days_in_window',
              comparison: 'eq',
              threshold: 'high',
              count: 5,
              window: 14,
              minData: 7,
            },
          ],
          outcome: 'INVITE',
          specialtyCodes: ['counselling'],
          messageEn: 'You logged high stress on {n} of the last {window} days.',
          messageFr: 'Vous avez noté un stress élevé {n} jours sur les {window} derniers.',
          cooldownDays: 30,
        },
      ],
    });
    const tracking = {
      GET: fn(
        route({
          '/admin/v1/tracking/versions/{versionId}': () => ok(inReview),
          '/admin/v1/tracking/modules': () => ok(rows),
        }),
      ),
      POST: fn((path: string) =>
        path.endsWith('/test-run')
          ? ok([
              { history: 'low_mood_two_weeks', ruleCode: 'stress.high_days', fires: true },
              { history: 'good_mood', ruleCode: 'stress.high_days', fires: false },
            ])
          : ok({ ...inReview, approvedBy: 'Dr. Fomba Grace' }),
      ),
    };
    setUp(['CLINICAL_REVIEWER'], {}, { tracking });
    const { root, settle } = await render(ModuleReviewPage, { id: 's1' });
    await sees(root, 'Stress · v1 for review');
    await sees(root, 'v1 fires on 1 of 2.');
    expect(root.textContent).toContain('stress.high_days, message');

    const approve = button(root, 'Approve v1')!;
    expect(approve.disabled).toBe(true);
    for (const box of root.querySelectorAll<HTMLInputElement>('aside input[type=checkbox]')) {
      box.click();
    }
    await settle();
    expect(approve.disabled).toBe(false);
    approve.click();
    await settle();
    expect(tracking.POST).toHaveBeenCalledWith('/admin/v1/tracking/versions/{versionId}/review', {
      params: { path: { versionId: 's1' } },
      body: { approve: true },
    });

    TestBed.resetTestingModule();
    setUp(
      ['CLINICAL_REVIEWER'],
      {},
      {
        tracking: {
          ...tracking,
          GET: fn(
            route({
              '/admin/v1/tracking/versions/{versionId}': () =>
                ok({ ...inReview, canApprove: false }),
              '/admin/v1/tracking/modules': () => ok(rows),
            }),
          ),
        },
      },
    );
    const own = await render(ModuleReviewPage, { id: 's1' });
    await sees(own.root, 'The person who drafted a version cannot approve it.');
    expect(button(own.root, 'Approve v1')!.disabled).toBe(true);
  });
});
