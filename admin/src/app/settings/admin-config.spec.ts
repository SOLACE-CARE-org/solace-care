import { TestBed } from '@angular/core/testing';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import type { CoreSchemas } from '../api/core-api';
import { AuditLogPage } from '../audit/audit-log.page';
import { TeamPage } from '../team/team.page';
import { button, fn, ok, render, route, sees, setUp, stubDialogs } from '../testing/harness';
import { SettingsPage } from './settings.page';

const settings: CoreSchemas['PlatformSettings'] = {
  settings: [
    {
      key: 'solace.specialists.pricing.fee-percent',
      group: 'MONEY',
      type: 'PERCENT',
      value: '20',
      defaultValue: '20',
      min: 0,
      max: 50,
      editable: true,
      version: 0,
    },
    {
      key: 'solace.scheduling.hold',
      group: 'SESSIONS',
      type: 'MINUTES',
      value: '10',
      defaultValue: '10',
      min: 10,
      max: 10,
      editable: false,
      version: 0,
    },
  ],
  flags: [
    { key: 'safetyMonitor', enabled: true, locked: true, phase: 1 },
    { key: 'cardPayments', enabled: false, locked: false, phase: 1 },
  ],
};

describe('Settings and flags', () => {
  beforeEach(() => TestBed.resetTestingModule());

  it('shows fixed values and changes a setting only with a reason', async () => {
    const client = {
      GET: fn(
        route({
          '/admin/v1/settings': () => ok(settings),
          '/admin/v1/settings/{key}/history': () => ok([]),
        }),
      ),
      PUT: fn(() => ok(settings.settings[0])),
    };
    setUp(['SUPER_ADMIN'], client);
    const { root, settle } = await render(SettingsPage);
    stubDialogs(root);

    await sees(root, 'Commission on each session');
    expect(root.textContent).toContain('20%');
    expect(root.textContent).toContain('Fixed');
    expect(root.textContent).toContain('Locked on in production');

    button(root, 'Change')!.click();
    await settle();
    const save = button(root, 'Save')!;
    expect(save.disabled).toBe(true);
    const reason = root.querySelector<HTMLTextAreaElement>('dialog textarea')!;
    reason.value = 'Board decision';
    reason.dispatchEvent(new Event('input'));
    const value = root.querySelector<HTMLInputElement>('dialog input')!;
    value.value = '18';
    value.dispatchEvent(new Event('input'));
    await settle();
    save.click();
    await vi.waitFor(() => expect(client.PUT).toHaveBeenCalled());
    expect(client.PUT.mock.calls[0]).toEqual([
      '/admin/v1/settings/{key}',
      {
        params: { path: { key: 'solace.specialists.pricing.fee-percent' } },
        body: { value: '18', reason: 'Board decision' },
      },
    ]);
  });

  it('lets Operations look without changing', async () => {
    setUp(['OPERATIONS'], { GET: fn(route({ '/admin/v1/settings': () => ok(settings) })) });
    const { root } = await render(SettingsPage);
    await sees(root, 'Commission on each session');
    expect(button(root, 'Change')).toBeUndefined();
    expect(button(root, 'Turn on')).toBeUndefined();
  });
});

describe('Team and roles', () => {
  beforeEach(() => TestBed.resetTestingModule());

  it('adds a staff member and shows the temporary password once', async () => {
    const member: CoreSchemas['StaffMember'] = {
      id: 'k1',
      name: 'M. Atangana',
      email: 'm@solace.test',
      roles: ['CONTENT_EDITOR'],
      secondFactor: false,
      status: 'INVITED',
      addedAt: '2026-10-15T09:00:00Z',
    };
    const client = {
      GET: fn(
        route({
          '/admin/v1/staff': () => ok([member]),
          '/admin/v1/crisis/rota': () => ok([]),
        }),
      ),
      POST: fn(() =>
        ok({ member, temporaryPassword: 'Kp7-xQ2.mRt9vWa3', expiresAt: '2026-10-18T09:00:00Z' }),
      ),
    };
    setUp(['SUPER_ADMIN'], client);
    const { root, settle } = await render(TeamPage);
    stubDialogs(root);

    await sees(root, 'M. Atangana');
    expect(root.textContent).toContain('Not set up');
    expect(root.textContent).toContain('not signed in yet');

    button(root, 'Add staff member')!.click();
    await settle();
    const inputs = root.querySelectorAll<HTMLInputElement>('dialog input');
    inputs[0]!.value = 'M. Atangana';
    inputs[0]!.dispatchEvent(new Event('input'));
    inputs[1]!.value = 'm@solace.test';
    inputs[1]!.dispatchEvent(new Event('input'));
    root.querySelector<HTMLInputElement>('dialog input[type="checkbox"]')!.click();
    await settle();
    [...root.querySelectorAll<HTMLButtonElement>('dialog button')]
      .find((b) => b.textContent?.trim() === 'Add staff member')!
      .click();
    await vi.waitFor(() => expect(client.POST).toHaveBeenCalled());
    await sees(root, 'Kp7-xQ2.mRt9vWa3');
    button(root, 'I have noted it')!.click();
    await settle();
    expect(root.textContent).not.toContain('Kp7-xQ2.mRt9vWa3');
  });
});

describe('Audit log', () => {
  beforeEach(() => TestBed.resetTestingModule());

  it('names actions in words and people by reference', async () => {
    const entry: CoreSchemas['AuditEntryView'] = {
      id: 9,
      at: '2026-10-18T13:31:00Z',
      actor: {
        type: 'STAFF',
        name: 'Dr. Fomba Grace',
        roles: ['CRISIS_RESPONDER', 'offline_access'],
      },
      action: 'crisis.alert.read',
      target: { type: 'crisis-alert', id: 'a1', label: '#4821' },
      reason: 'On-call response',
      outcome: 'ALLOWED',
    };
    setUp(['OPERATIONS'], { GET: fn(route({ '/admin/v1/audit': () => ok([entry]) })) });
    const { root } = await render(AuditLogPage);
    await sees(root, 'Crisis alert viewed');
    expect(root.textContent).toContain('Seeker #4821');
    expect(root.textContent).toContain('Crisis responder');
    expect(root.textContent).not.toContain('offline_access');
    expect(root.textContent).toContain('On-call response');
  });
});
