import { TestBed } from '@angular/core/testing';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import type { CoreSchemas } from '../api/core-api';
import { button, fn, ok, problem, render, route, sees, setUp } from '../testing/harness';
import { CataloguePage } from './catalogue.page';

const catalogue: CoreSchemas['AdminCatalogue'] = {
  specialties: [
    {
      code: 'counselling',
      labelEn: 'Counselling',
      labelFr: 'Conseil psychologique',
      position: 2,
      enabled: true,
    },
    {
      code: 'gynaecology',
      labelEn: 'Gynaecology',
      labelFr: 'Gynécologie',
      position: 4,
      enabled: false,
    },
  ],
  sessionLanguages: [
    { code: 'fr', labelEn: 'Français', labelFr: 'Français', position: 2, enabled: true },
  ],
  helpTopics: [],
  durations: [30, 50, 80],
};
const v1: CoreSchemas['CancellationPolicy'] = {
  version: 1,
  fullRefundHours: 24,
  partialRefundHours: 2,
  partialRefundPercent: 50,
  seekerNoShowRefundPercent: 0,
  specialistNoShowRefundPercent: 100,
  maxReschedules: 2,
  rescheduleMinHours: 2,
  publishedAt: '2026-10-01T00:00:00Z',
  publishedBy: 'system',
  inForce: true,
};

describe('Catalogue and policies', () => {
  beforeEach(() => TestBed.resetTestingModule());

  it('lists specialties in both languages and shows hidden ones', async () => {
    setUp(['SUPPORT'], {
      GET: fn(
        route({
          '/admin/v1/catalogue': () => ok(catalogue),
          '/admin/v1/cancellation-policies': () => ok([v1]),
          '/admin/v1/settings': () => problem(403, 'access.denied', 'No'),
        }),
      ),
    });
    const { root } = await render(CataloguePage);
    await sees(root, 'Conseil psychologique');
    expect(root.textContent).toContain('Hidden');
    expect(button(root, 'Add')).toBeUndefined();
  });

  it('publishes a new policy version only when it differs and has a reason', async () => {
    const client = {
      GET: fn(
        route({
          '/admin/v1/catalogue': () => ok(catalogue),
          '/admin/v1/cancellation-policies': () => ok([v1]),
          '/admin/v1/settings': () => ok({ settings: [], flags: [] }),
        }),
      ),
      POST: fn(() => ok({ ...v1, version: 2 }, 201)),
    };
    setUp(['OPERATIONS'], client);
    const { root, settle } = await render(CataloguePage);
    await sees(root, 'Specialties');
    button(root, 'Cancellation policy')!.click();
    await settle();
    expect(root.textContent).toContain(
      'Cancel at least 24 hours before for a full refund. Later, you get 50% back.',
    );
    expect(root.textContent).toContain('Annulez au moins 24 heures avant');
    const publish = button(root, 'Publish v2, with reason')!;
    expect(publish.disabled).toBe(true);

    const hours = root.querySelector<HTMLInputElement>('input[type="number"]')!;
    hours.value = '48';
    hours.dispatchEvent(new Event('input'));
    const reason = root.querySelector<HTMLTextAreaElement>('textarea')!;
    reason.value = 'Clinicians asked for two days';
    reason.dispatchEvent(new Event('input'));
    await settle();
    expect(root.textContent).toContain('Cancel at least 48 hours before');
    button(root, 'Publish v2, with reason')!.click();
    await vi.waitFor(() => expect(client.POST).toHaveBeenCalled());
    expect(client.POST.mock.calls[0]![1].body).toMatchObject({
      fullRefundHours: 48,
      reason: 'Clinicians asked for two days',
    });
  });
});
