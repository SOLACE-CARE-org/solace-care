import { TestBed } from '@angular/core/testing';
import { Router } from '@angular/router';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import type { TrackingSchemas } from '../api/tracking-api';
import { button, fn, ok, render, route, sees, setUp } from '../testing/harness';
import { draftOf, ElementEditorPage, emptyForm, formOf } from './element-editor.page';
import { ElementsPage } from './elements.page';

const views: TrackingSchemas['InputView'][] = [
  { key: 'scale_5', valueTypes: ['INT'], kinds: ['DAY_STATE'], props: {}, minAppVersion: '1.0.0' },
  {
    key: 'duration_stepper',
    valueTypes: ['MINUTES'],
    kinds: ['DAY_STATE'],
    props: { start: { type: 'integer', minimum: 0, maximum: 1440 } },
    minAppVersion: '1.0.0',
  },
  {
    key: 'choice_chips',
    valueTypes: ['ENUM'],
    kinds: ['DAY_STATE'],
    props: {},
    minAppVersion: '1.0.0',
  },
  {
    key: 'event_marker',
    valueTypes: ['DATE'],
    kinds: ['EVENT'],
    props: {},
    minAppVersion: '1.0.0',
  },
];

const headache: TrackingSchemas['ElementVersion'] = {
  id: 'e1',
  code: 'headache',
  version: '1',
  status: 'IN_REVIEW',
  kind: 'DAY_STATE',
  valueType: 'ENUM',
  constraints: { values: ['none', 'bad'] },
  inputView: 'choice_chips',
  viewProps: {},
  icon: 'brain',
  signals: ['daily_value'],
  labels: {
    en: { name: 'Headache', values: { none: 'None', bad: 'Bad' } },
    fr: { name: 'Mal de tête', values: { none: 'Aucun', bad: 'Fort' } },
  },
  minAppVersion: '1.0.0',
  problems: [],
  boundBy: 0,
  reviews: [],
  draftedBy: 'Nadine Ebai',
  canApprove: true,
};

function tracking(version = headache) {
  return {
    GET: fn(
      route({
        '/admin/v1/tracking/input-views': () => ok(views),
        '/admin/v1/tracking/element-icons': () => ok(['brain', 'moon']),
        '/admin/v1/tracking/element-versions/{elementVersionId}': () => ok(version),
        '/admin/v1/tracking/element-versions': () =>
          ok([
            { ...headache, id: 'e2', version: '1.1', status: 'DRAFT' },
            { ...headache, status: 'PUBLISHED' },
          ]),
      }),
    ),
    POST: fn(() => ok({ ...version, status: 'IN_REVIEW', approvedBy: 'Dr. Fomba Grace' })),
    PUT: fn(() => ok(version)),
  };
}

describe('Tracking elements', () => {
  beforeEach(() => TestBed.resetTestingModule());

  it('lists each element with what is published and what is being worked on', async () => {
    setUp(['CONTENT_EDITOR'], {}, { tracking: tracking() });
    const { root } = await render(ElementsPage);
    await sees(root, 'Headache');
    expect(root.textContent).toContain('Choice chips');
    expect(root.textContent).toContain('v1.1 draft');
    expect(root.textContent).not.toContain('New element');
  });

  it('builds the draft body from the form, with labels for every choice in EN and FR', () => {
    const form = formOf(headache);
    expect(draftOf(form, views)).toEqual({
      code: 'headache',
      kind: 'DAY_STATE',
      valueType: 'ENUM',
      constraints: { values: ['none', 'bad'] },
      inputView: 'choice_chips',
      viewProps: {},
      icon: 'brain',
      labels: {
        en: { name: 'Headache', values: { none: 'None', bad: 'Bad' } },
        fr: { name: 'Mal de tête', values: { none: 'Aucun', bad: 'Fort' } },
      },
    });
    const nap = {
      ...emptyForm(),
      code: 'nap',
      valueType: 'MINUTES' as const,
      inputView: 'duration_stepper',
      min: 0,
      max: 240,
      step: 15,
      start: 30,
      choices: [],
      nameEn: 'Nap',
      nameFr: 'Sieste',
    };
    expect(draftOf(nap, views)).toMatchObject({
      constraints: { min: 0, max: 240, step: 15 },
      viewProps: { start: 30 },
      labels: { en: { name: 'Nap' }, fr: { name: 'Sieste' } },
    });
    // An event marker records an event.
    expect(draftOf({ ...nap, valueType: 'DATE', inputView: 'event_marker' }, views).kind).toBe(
      'EVENT',
    );
  });

  it('previews the row in EN and FR, and a reviewer approves', async () => {
    const api = tracking();
    setUp(['CLINICAL_REVIEWER'], {}, { tracking: api });
    const { root, settle } = await render(ElementEditorPage, { id: 'e1' });
    await sees(root, 'Mal de tête');
    expect(root.textContent).toContain('Fort');
    expect(root.textContent).toContain('Shown on app 1.0.0 and later.');
    button(root, 'Approve')!.click();
    await settle();
    expect(api.POST).toHaveBeenCalledWith(
      '/admin/v1/tracking/element-versions/{elementVersionId}/review',
      { params: { path: { elementVersionId: 'e1' } }, body: { approve: true } },
    );
    await sees(root, 'Approved by Dr. Fomba Grace.');
    // Reviewers do not publish.
    expect(button(root, 'Publish v1')).toBeUndefined();
  });

  it('drafts a new element and opens it', async () => {
    const api = tracking();
    setUp(['OPERATIONS'], {}, { tracking: api });
    const navigate = vi.spyOn(TestBed.inject(Router), 'navigate').mockResolvedValue(true);
    const { root, fixture, settle } = await render(ElementEditorPage, { id: 'new' });
    await sees(root, 'New element');
    const page = fixture.componentInstance;
    page.form = { ...formOf(headache) };
    page.touch();
    await settle();
    await page.save();
    expect(api.POST).toHaveBeenCalledWith('/admin/v1/tracking/element-versions', {
      body: expect.objectContaining({ code: 'headache', inputView: 'choice_chips' }),
    });
    expect(navigate).toHaveBeenCalledWith(['/modules/elements', 'e1']);
  });

  it('keeps a published version read-only and offers a new version', async () => {
    setUp(
      ['OPERATIONS'],
      {},
      { tracking: tracking({ ...headache, status: 'PUBLISHED', boundBy: 2 }) },
    );
    const { root } = await render(ElementEditorPage, { id: 'e1' });
    await sees(root, 'This version cannot change.');
    expect(button(root, 'Start a new version')).toBeDefined();
    expect(root.textContent).toContain('2 module versions use this version');
    expect(button(root, 'Retire v1')).toBeUndefined();
  });
});
