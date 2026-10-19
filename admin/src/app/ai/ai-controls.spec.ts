import { TestBed } from '@angular/core/testing';
import { beforeEach, describe, expect, it } from 'vitest';

import type { AiSchemas } from '../api/ai-api';
import { button, fn, ok, render, route, sees, setUp } from '../testing/harness';
import { AiControlsPage } from './ai-controls.page';

const failedDraft: AiSchemas['PromptVersion'] = {
  id: 'p11',
  version: '1.1',
  status: 'DRAFT',
  system: 'You explain to a person, in plain words, why a pattern was noticed.',
  policy: 'Policy. Never diagnose.',
  createdBy: 'sa-1',
  createdAt: '2026-10-18T10:00:00Z',
  evaluation: {
    id: 'e1',
    passedEn: 2,
    totalEn: 2,
    passedFr: 1,
    totalFr: 2,
    passed: false,
    failures: [{ caseId: 'long-cycles-fr', locale: 'fr', reason: 'number-not-in-evidence' }],
    ranAt: '2026-10-18T10:05:00Z',
  },
};

function overview(candidate: AiSchemas['PromptVersion']): AiSchemas['AiOverview'] {
  return {
    safetyMonitor: {
      classifier: 'phrases-1',
      policy: 'safety-1',
      recallEn: 0,
      recallFr: 0,
      gate: 99,
      passing: false,
    },
    features: [
      { feature: 'TRACKING_INSIGHTS', enabled: false, routes: ['local-llm'], candidate },
      { feature: 'PRE_SESSION_BRIEF', enabled: false, routes: [] },
      { feature: 'REVIEW_MODERATION', enabled: false, routes: [] },
      { feature: 'TRANSLATION', enabled: false, routes: [] },
    ],
  };
}

describe('AI controls', () => {
  beforeEach(() => TestBed.resetTestingModule());

  it('shows why a version is blocked and keeps promotion shut until it passes and is signed off', async () => {
    setUp(
      ['SUPER_ADMIN'],
      {},
      { ai: { GET: fn(route({ '/admin/v1/ai/features': () => ok(overview(failedDraft)) })) } },
    );
    const { root, settle } = await render(AiControlsPage);
    await sees(root, 'Tracking insights');
    expect(root.textContent).toContain('FR sample failed: added a finding not in the evidence');
    expect(root.textContent).toContain('Blocked');
    expect(root.textContent).toContain("Waiting for the clinical team's safety set");
    expect(root.textContent).toContain('Not built');
    expect(button(root, 'Promote v1.1')!.disabled).toBe(true);
    button(root, 'View failed samples')!.click();
    await settle();
    expect(root.textContent).toContain('long-cycles-fr · FR');
  });

  it('lets a clinical reviewer sign off a passing evaluation, and only a super admin promote', async () => {
    const passing = {
      ...failedDraft,
      evaluation: { ...failedDraft.evaluation!, passedFr: 2, passed: true, failures: [] },
    };
    const ai = {
      GET: fn(route({ '/admin/v1/ai/features': () => ok(overview(passing)) })),
      POST: fn(() => ok({ ...passing, signedOffBy: 'Dr. Fomba Grace' })),
    };
    setUp(['CLINICAL_REVIEWER'], {}, { ai });
    const { root, settle } = await render(AiControlsPage);
    await sees(root, 'Passing');
    expect(button(root, 'Promote v1.1')).toBeUndefined();
    expect(root.querySelector<HTMLInputElement>('input[role=switch]')!.disabled).toBe(true);
    button(root, 'Sign off the graded sample')!.click();
    await settle();
    expect(ai.POST).toHaveBeenCalledWith('/admin/v1/ai/versions/{versionId}/sign-off', {
      params: { path: { versionId: 'p11' } },
    });
  });
});
