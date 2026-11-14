import { TestBed } from '@angular/core/testing';
import { Router } from '@angular/router';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import type { TrackingSchemas } from '../api/tracking-api';
import { button, fn, ok, render, route, sees, setUp } from '../testing/harness';
import {
  ContextQuestionEditorPage,
  questionDraftOf,
  questionFormOf,
} from './context-question-editor.page';
import { ContextQuestionsPage } from './context-questions.page';

const contraception: TrackingSchemas['ContextQuestionVersion'] = {
  id: 'q1',
  code: 'contraception',
  version: 1,
  status: 'IN_REVIEW',
  type: 'ONE_CHOICE',
  scope: 'ENROLMENT',
  question: {
    en: 'Which contraception do you use?',
    fr: 'Quelle contraception utilisez-vous ?',
  },
  options: [
    { code: 'pill', labels: { en: 'Pill', fr: 'Pilule' } },
    { code: 'coil', labels: { en: 'Coil', fr: 'Stérilet' } },
  ],
  problems: [],
  usedBy: 0,
  reviews: [],
  draftedBy: 'Nadine Ebai',
  canApprove: true,
};

function tracking(version = contraception) {
  return {
    GET: fn(
      route({
        '/admin/v1/tracking/context-question-versions/{contextQuestionVersionId}': () =>
          ok(version),
        '/admin/v1/tracking/context-question-versions': () =>
          ok([
            { ...contraception, id: 'q2', version: 2, status: 'DRAFT' },
            { ...contraception, status: 'PUBLISHED' },
          ]),
      }),
    ),
    POST: fn(() => ok({ ...version, status: 'IN_REVIEW', approvedBy: 'Dr. Fomba Grace' })),
    PUT: fn(() => ok(version)),
  };
}

describe('Tracking context questions', () => {
  beforeEach(() => TestBed.resetTestingModule());

  it('lists each question with where it is asked, what is published and what is being worked on', async () => {
    setUp(['CONTENT_EDITOR'], {}, { tracking: tracking() });
    const { root } = await render(ContextQuestionsPage);
    await sees(root, 'Which contraception do you use?');
    expect(root.textContent).toContain('On joining');
    expect(root.textContent).toContain('v2 draft');
    expect(root.textContent).not.toContain('New context question');
  });

  it('builds the draft body from the form, with no choices for yes or no', () => {
    const form = questionFormOf(contraception);
    expect(questionDraftOf(form)).toEqual({
      code: 'contraception',
      type: 'ONE_CHOICE',
      scope: 'ENROLMENT',
      question: {
        en: 'Which contraception do you use?',
        fr: 'Quelle contraception utilisez-vous ?',
      },
      options: [
        { code: 'pill', labels: { en: 'Pill', fr: 'Pilule' } },
        { code: 'coil', labels: { en: 'Coil', fr: 'Stérilet' } },
      ],
    });
    expect(questionDraftOf({ ...form, type: 'YES_NO' }).options).toEqual([]);
  });

  it('previews the question in both languages, and a reviewer approves', async () => {
    const api = tracking();
    setUp(['CLINICAL_REVIEWER'], {}, { tracking: api });
    const { root, settle } = await render(ContextQuestionEditorPage, { id: 'q1' });
    await sees(root, 'contraception v1');
    expect(root.textContent).toContain('Quelle contraception utilisez-vous ?');
    expect(root.textContent).toContain('Stérilet');
    expect(root.textContent).toContain('Passer');
    button(root, 'Approve')!.click();
    await settle();
    expect(api.POST).toHaveBeenCalledWith(
      '/admin/v1/tracking/context-question-versions/{contextQuestionVersionId}/review',
      {
        params: { path: { contextQuestionVersionId: 'q1' } },
        body: { approve: true },
      },
    );
    await sees(root, 'Approved by Dr. Fomba Grace.');
    expect(button(root, 'Publish v1')).toBeUndefined();
  });

  it('drafts a new question and opens it', async () => {
    const api = tracking();
    setUp(['OPERATIONS'], {}, { tracking: api });
    const navigate = vi.spyOn(TestBed.inject(Router), 'navigate').mockResolvedValue(true);
    const { root, fixture, settle } = await render(ContextQuestionEditorPage, { id: 'new' });
    await sees(root, 'New context question');
    const page = fixture.componentInstance;
    page.form = questionFormOf(contraception);
    page.touch();
    await settle();
    await page.save();
    expect(api.POST).toHaveBeenCalledWith('/admin/v1/tracking/context-question-versions', {
      body: expect.objectContaining({ code: 'contraception', type: 'ONE_CHOICE' }),
    });
    expect(navigate).toHaveBeenCalledWith(['/modules/context-questions', 'q1']);
  });

  it('keeps a published version read-only and will not retire one a module uses', async () => {
    setUp(
      ['OPERATIONS'],
      {},
      { tracking: tracking({ ...contraception, status: 'PUBLISHED', usedBy: 2 }) },
    );
    const { root } = await render(ContextQuestionEditorPage, { id: 'q1' });
    await sees(root, 'This version cannot change.');
    expect(button(root, 'Start a new version')).toBeDefined();
    expect(root.textContent).toContain('2 module versions ask this question');
    expect(button(root, 'Retire v1')).toBeUndefined();
  });
});
