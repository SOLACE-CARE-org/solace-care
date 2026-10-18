import { TestBed } from '@angular/core/testing';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import type { CoreSchemas } from '../api/core-api';
import { button, fn, ok, render, route, sees, setUp, stubDialogs } from '../testing/harness';
import { PeoplePage } from './people.page';
import { PersonRecordPage } from './person-record.page';

const row: CoreSchemas['PersonRow'] = {
  id: 'p1',
  ref: '#4821',
  type: 'ACCOUNT',
  role: 'SEEKER',
  status: 'ACTIVE',
  contact: '+237 6•• •• •• 34',
  appLanguage: 'fr',
  bookings: 5,
  since: '2026-08-03T09:00:00Z',
  openRequests: ['EXPORT'],
};
const record: CoreSchemas['PersonRecord'] = {
  ...row,
  bookingList: [
    {
      id: 'b1',
      reference: 'BK-7F9D1C2E',
      startsAt: '2026-10-19T09:00:00Z',
      specialist: 'Ngo Biyong Claire',
      modality: 'VIDEO',
      sessionLanguage: 'fr',
      status: 'CONFIRMED',
      price: 20000,
    },
  ],
  consents: [
    { purpose: 'AI_ASSIST', granted: false, version: 'v1', recordedAt: '2026-08-03T09:00:00Z' },
  ],
  dataRequests: [],
  openCrisisAlerts: 1,
  canErase: false,
};

describe('People', () => {
  beforeEach(() => TestBed.resetTestingModule());

  it('lists people with masked contacts and open requests', async () => {
    setUp(['SUPPORT'], { GET: fn(route({ '/admin/v1/people': () => ok([row]) })) });
    const { root } = await render(PeoplePage);
    await sees(root, 'Seeker #4821');
    expect(root.textContent).toContain('+237 6•• •• •• 34');
    expect(root.textContent).toContain('Data export');
  });

  it('reveals a contact only with a reason, and keeps erasure off while a session is ahead', async () => {
    const client = {
      GET: fn(route({ '/admin/v1/people/{personId}': () => ok(record) })),
      POST: fn(() => ok({ phone: '+237670004834' })),
    };
    setUp(['OPERATIONS'], client);
    const { root, settle } = await render(PersonRecordPage, { id: 'p1' });
    stubDialogs(root);
    await sees(root, 'Ngo Biyong Claire');
    expect(root.textContent).toContain('never shown in the admin portal');
    expect(root.textContent).toContain('1 crisis alert in progress');
    expect(button(root, 'Start erasure')!.disabled).toBe(true);

    button(root, 'Reveal contact (logged)')!.click();
    await settle();
    const confirm = button(root, 'Confirm')!;
    expect(confirm.disabled).toBe(true);
    const reason = root.querySelector<HTMLTextAreaElement>('dialog textarea')!;
    reason.value = 'Callback about a missed session';
    reason.dispatchEvent(new Event('input'));
    await settle();
    confirm.click();
    await vi.waitFor(() => expect(client.POST).toHaveBeenCalled());
    expect(client.POST.mock.calls[0]).toEqual([
      '/admin/v1/people/{personId}/contact-reveal',
      { params: { path: { personId: 'p1' } }, body: { reason: 'Callback about a missed session' } },
    ]);
    await sees(root, '+237670004834');
  });
});
