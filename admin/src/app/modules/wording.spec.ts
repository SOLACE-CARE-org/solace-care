import { TestBed } from '@angular/core/testing';
import { beforeEach, describe, expect, it } from 'vitest';

import type { TrackingSchemas } from '../api/tracking-api';
import { button, fn, ok, render, route, sees, setUp, stubDialogs } from '../testing/harness';
import { fold, PATTERN, WordingPage } from './wording.page';

const rules: TrackingSchemas['WordingRule'][] = [
  { id: 'w1', language: 'en', pattern: 'diagnos*', kind: 'BLOCK' },
  { id: 'w2', language: 'fr', pattern: 'pas un diagnostic', kind: 'ALLOW' },
];
const settings: TrackingSchemas['TrackingSetting'][] = [
  { key: 'snooze_days', value: 7, defaultValue: 7, min: 1, max: 90, unit: 'days' },
  {
    key: 'nudge_risk_pause_days',
    value: 21,
    defaultValue: 14,
    min: 1,
    max: 90,
    unit: 'days',
    updatedBy: 'N. Ebai',
    updatedAt: '2026-11-14T08:00:00Z',
  },
];

function tracking() {
  return {
    GET: fn(
      route({
        '/admin/v1/tracking/wording-rules': () => ok(rules),
        '/admin/v1/tracking/settings': () => ok(settings),
      }),
    ),
    POST: fn(
      route({
        '/admin/v1/tracking/wording-rules': () =>
          ok(
            {
              id: 'w3',
              language: 'en',
              pattern: 'cure*',
              kind: 'BLOCK',
              createdBy: 'Dr. Fomba Grace',
            },
            201,
          ),
        '/admin/v1/tracking/wording-check': () => ok({ allowed: false, blockedBy: ['diagnos*'] }),
      }),
    ),
    PUT: fn(() => ok({ ...settings[0]!, value: 10, updatedBy: 'N. Ebai' })),
    DELETE: fn(() => ({
      data: undefined,
      error: undefined,
      response: new Response(null, { status: 204 }),
    })),
  };
}

describe('Word list and settings', () => {
  beforeEach(() => TestBed.resetTestingModule());

  it('folds patterns as the server stores them', () => {
    expect(fold('  Gravité  Élevée ')).toBe('gravite elevee');
    expect(PATTERN.test('diagnos*')).toBe(true);
    expect(PATTERN.test('(a|b)+')).toBe(false);
  });

  it('shows the list and the settings; Operations changes a setting with a reason', async () => {
    const api = tracking();
    setUp(['OPERATIONS'], {}, { tracking: api });
    const { root, fixture, settle } = await render(WordingPage);
    await sees(root, 'diagnos*');
    expect(root.textContent).toContain('Launch list');
    expect(root.textContent).toContain('21 days');
    expect(root.textContent).toContain('N. Ebai');
    // Operations does not edit the word list.
    expect(button(root, 'Add')).toBeUndefined();
    stubDialogs(root);
    const page = fixture.componentInstance;
    page.edit(settings[0]!);
    await settle();
    // The dialog asks for a reason, and shows the default.
    expect(root.querySelector('dialog')?.textContent).toContain('Default 7 days');
    page.value = 10;
    page.reason = 'Seekers asked for longer';
    await settle();
    await page.saveSetting(settings[0]!);
    await settle();
    expect(api.PUT).toHaveBeenCalledWith('/admin/v1/tracking/settings/{settingKey}', {
      params: { path: { settingKey: 'snooze_days' } },
      body: { value: 10, reason: 'Seekers asked for longer' },
    });
    expect(root.textContent).toContain('10 days');
  });

  it('lets a clinical reviewer add a word and try a message', async () => {
    const api = tracking();
    setUp(['CLINICAL_REVIEWER'], {}, { tracking: api });
    const { root, fixture, settle } = await render(WordingPage);
    await sees(root, 'diagnos*');
    const page = fixture.componentInstance;
    page.pattern = ' Cure* ';
    await page.add();
    expect(api.POST).toHaveBeenCalledWith('/admin/v1/tracking/wording-rules', {
      body: { language: 'en', pattern: 'cure*', kind: 'BLOCK' },
    });
    page.pattern = '(bad|worse)';
    await page.add();
    await settle();
    expect(root.textContent).toContain('Use letters, digits');
    page.tryText = 'A diagnostic test';
    await page.check();
    await settle();
    expect(root.textContent).toContain('Blocked by: diagnos*');
    // Reviewers do not change settings.
    expect(button(root, 'Change')).toBeUndefined();
  });
});
