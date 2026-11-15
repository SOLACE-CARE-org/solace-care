import { TestBed } from '@angular/core/testing';
import { Router } from '@angular/router';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import type { TrackingSchemas } from '../api/tracking-api';
import { button, fn, ok, render, route, sees, setUp } from '../testing/harness';
import { EpisodeModelEditorPage, modelDraftOf, modelFormOf } from './episode-model-editor.page';
import { EpisodeModelsPage } from './episode-models.page';

const elements: TrackingSchemas['Element'][] = [
  {
    code: 'migraine_start',
    version: '1',
    kind: 'EVENT',
    valueType: 'DATE',
    constraints: {},
    inputView: 'event_marker',
    icon: 'brain',
    signals: [],
    labels: { en: { name: 'Attack started' }, fr: { name: 'Début de crise' } },
    minAppVersion: '1.0.0',
  } as TrackingSchemas['Element'],
  {
    code: 'migraine_pain',
    version: '1',
    kind: 'DAY_STATE',
    valueType: 'ENUM',
    constraints: { values: ['none', 'mild', 'strong'] },
    inputView: 'choice_chips',
    icon: 'brain',
    signals: ['daily_value'],
    labels: {
      en: {
        name: 'Pain',
        values: { none: 'None', mild: 'Mild', strong: 'Strong' },
      },
      fr: {
        name: 'Douleur',
        values: { none: 'Aucune', mild: 'Légère', strong: 'Forte' },
      },
    },
    minAppVersion: '1.0.0',
  } as TrackingSchemas['Element'],
];

const migraine: TrackingSchemas['EpisodeModelVersion'] = {
  id: 'm1',
  code: 'migraine',
  version: 1,
  status: 'IN_REVIEW',
  startElement: 'migraine_start',
  activityElement: 'migraine_pain',
  activeValues: ['mild', 'strong'],
  gapDays: 2,
  span: 'UNTIL_END',
  metrics: [
    {
      code: 'length_days',
      kind: 'LENGTH_DAYS',
      labels: { en: 'Attack length', fr: 'Durée de la crise' },
    },
    {
      code: 'strong_days',
      kind: 'DAYS_WITH_VALUE',
      value: 'strong',
      labels: { en: 'Strong days', fr: 'Jours forts' },
    },
  ],
  signals: ['migraine.length_days', 'migraine.strong_days'],
  labels: { en: 'Migraine attack', fr: 'Crise de migraine' },
  problems: [],
  usedBy: 0,
  reviews: [],
  draftedBy: 'Nadine Ebai',
  canApprove: true,
};

const runs: TrackingSchemas['EpisodeTestRun'][] = [
  {
    history: 'attacks',
    historyLabels: { en: 'Three attacks', fr: 'Trois crises' },
    episodes: [
      {
        start: '2026-10-01',
        complete: true,
        metrics: { length_days: 3, strong_days: 3 },
      },
      {
        start: '2026-10-11',
        complete: true,
        metrics: { length_days: 3, strong_days: 2 },
      },
      { start: '2026-10-21', complete: false, metrics: {} },
    ],
  },
];

function tracking(version = migraine) {
  return {
    GET: fn(
      route({
        '/admin/v1/tracking/elements': () => ok(elements),
        '/admin/v1/tracking/episode-model-versions/{episodeModelVersionId}': () => ok(version),
        '/admin/v1/tracking/episode-model-versions': () =>
          ok([
            { ...migraine, id: 'm2', version: 2, status: 'DRAFT' },
            { ...migraine, status: 'PUBLISHED' },
          ]),
      }),
    ),
    POST: fn((path: string) =>
      path.endsWith('/test-run')
        ? ok(runs)
        : ok({
            ...version,
            status: 'IN_REVIEW',
            approvedBy: 'Dr. Fomba Grace',
          }),
    ),
    PUT: fn(() => ok(version)),
  };
}

describe('Tracking episode models', () => {
  beforeEach(() => TestBed.resetTestingModule());

  it('lists each model with what it reads, what is published and what is being worked on', async () => {
    setUp(['CONTENT_EDITOR'], {}, { tracking: tracking() });
    const { root } = await render(EpisodeModelsPage);
    await sees(root, 'Migraine attack');
    expect(root.textContent).toContain('migraine_start, migraine_pain');
    expect(root.textContent).toContain('v2 draft');
    expect(root.textContent).not.toContain('New episode model');
  });

  it('builds the draft body from the form', () => {
    const form = modelFormOf(migraine);
    expect(modelDraftOf(form)).toEqual({
      code: 'migraine',
      startElement: 'migraine_start',
      activityElement: 'migraine_pain',
      activeValues: ['mild', 'strong'],
      gapDays: 2,
      span: 'UNTIL_END',
      metrics: [
        {
          code: 'length_days',
          kind: 'LENGTH_DAYS',
          labels: { en: 'Attack length', fr: 'Durée de la crise' },
        },
        {
          code: 'strong_days',
          kind: 'DAYS_WITH_VALUE',
          value: 'strong',
          labels: { en: 'Strong days', fr: 'Jours forts' },
        },
      ],
      labels: { en: 'Migraine attack', fr: 'Crise de migraine' },
    });
    // Without an activity element, no active values go.
    expect(modelDraftOf({ ...form, activity: '' }).activeValues).toEqual([]);
  });

  it('runs on the test histories, and a reviewer approves', async () => {
    const api = tracking();
    setUp(['CLINICAL_REVIEWER'], {}, { tracking: api });
    const { root, settle } = await render(EpisodeModelEditorPage, { id: 'm1' });
    await sees(root, 'Migraine attack');
    expect(root.textContent).toContain('Strong');
    button(root, 'Run on the test histories')!.click();
    await settle();
    await sees(root, 'Three attacks');
    expect(root.textContent).toContain('3 episodes, 2 complete');
    expect(root.textContent).toMatch(/Strong days\s*3, 2/);
    button(root, 'Approve')!.click();
    await settle();
    expect(api.POST).toHaveBeenCalledWith(
      '/admin/v1/tracking/episode-model-versions/{episodeModelVersionId}/review',
      {
        params: { path: { episodeModelVersionId: 'm1' } },
        body: { approve: true },
      },
    );
    await sees(root, 'Approved by Dr. Fomba Grace.');
    expect(button(root, 'Publish v1')).toBeUndefined();
  });

  it('drafts a new model and opens it', async () => {
    const api = tracking();
    setUp(['OPERATIONS'], {}, { tracking: api });
    const navigate = vi.spyOn(TestBed.inject(Router), 'navigate').mockResolvedValue(true);
    const { root, fixture, settle } = await render(EpisodeModelEditorPage, {
      id: 'new',
    });
    await sees(root, 'New episode model');
    const page = fixture.componentInstance;
    page.form = modelFormOf(migraine);
    page.touch();
    await settle();
    await page.save();
    expect(api.POST).toHaveBeenCalledWith('/admin/v1/tracking/episode-model-versions', {
      body: expect.objectContaining({
        code: 'migraine',
        startElement: 'migraine_start',
      }),
    });
    expect(navigate).toHaveBeenCalledWith(['/modules/episode-models', 'm1']);
  });

  it('keeps a published version read-only and will not retire one a module reads', async () => {
    setUp(
      ['OPERATIONS'],
      {},
      { tracking: tracking({ ...migraine, status: 'PUBLISHED', usedBy: 1 }) },
    );
    const { root } = await render(EpisodeModelEditorPage, { id: 'm1' });
    await sees(root, 'Published.');
    expect(button(root, 'Start a new version')).toBeDefined();
    expect(root.textContent).toContain('1 module versions read this model');
    expect(button(root, 'Retire v1')).toBeUndefined();
  });
});
