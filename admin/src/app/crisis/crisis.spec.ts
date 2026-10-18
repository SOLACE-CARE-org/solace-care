import { TestBed } from '@angular/core/testing';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import type { CoreSchemas } from '../api/core-api';
import { button, fn, ok, render, route, sees, setUp, stubDialogs } from '../testing/harness';
import { CrisisQueuePage } from './crisis-queue.page';
import { CrisisWatch } from './crisis-watch';

type Alert = CoreSchemas['CrisisAlert'];

const minutesAgo = (m: number) => new Date(Date.now() - m * 60_000).toISOString();

function alert(patch: Partial<Alert> = {}): Alert {
  return {
    id: 'a1',
    status: 'OPEN',
    category: 'SELF_HARM',
    source: 'REVIEW',
    language: 'fr',
    personRef: '#4821',
    raisedAt: minutesAgo(6),
    ...patch,
  };
}

const summary = {
  open: 2,
  unacknowledged: 2,
  primary: {
    staffId: 's1',
    name: 'Dr. Fomba Grace',
    phone: '+237670000001',
    role: 'PRIMARY',
    startsAt: minutesAgo(60),
    endsAt: '2026-10-18T19:00:00Z',
  },
  backup: {
    staffId: 's2',
    name: 'Tchakounté Arnaud',
    phone: '+237670000002',
    role: 'BACKUP',
    startsAt: minutesAgo(60),
    endsAt: '2026-10-18T19:00:00Z',
  },
};

describe('Crisis queue', () => {
  beforeEach(() => TestBed.resetTestingModule());

  it('lists open alerts oldest first with who is on call', async () => {
    const client = {
      GET: fn(
        route({
          '/admin/v1/crisis/summary': () => ok(summary),
          '/admin/v1/crisis/alerts': () =>
            ok([
              alert(),
              alert({
                id: 'a2',
                category: 'SUICIDAL_IDEATION',
                personRef: 'G-2198',
                raisedAt: minutesAgo(2),
                language: 'en',
              }),
            ]),
        }),
      ),
      POST: fn(),
    };
    setUp(['CRISIS_RESPONDER'], client);
    const { root } = await render(CrisisQueuePage);

    await sees(root, 'Self-harm');
    expect(root.textContent).toContain('On call: Dr. Fomba Grace');
    expect(root.textContent).toContain('backup: Tchakounté Arnaud');
    expect(root.textContent).toContain('Open (2)');
    expect(root.textContent).toContain('6 min · not acknowledged');
    expect(root.textContent).toContain('Guest #G-2198');
    expect(root.textContent).toContain('From a session review · French');
    // The list carries no flagged text.
    expect(client.GET.mock.calls.find((c) => c[0] === '/admin/v1/crisis/alerts')![1]).toEqual({
      params: { query: { status: 'OPEN' } },
    });
  });

  it('opens an alert, acknowledges it, reveals the contact and closes with an outcome', async () => {
    const detail = alert({
      text: 'Honnêtement je ne sais pas combien de temps je peux encore tenir comme ça.',
      contact: '+237 6•• •• •• 34',
      nextSession: {
        at: '2026-10-19T09:00:00Z',
        modality: 'VIDEO',
        sessionLanguage: 'fr',
        specialistName: 'Ngo Biyong Claire',
      },
    });
    const acknowledged = {
      ...detail,
      status: 'IN_PROGRESS',
      acknowledgedBy: 'Nadine Ebai',
      acknowledgedAt: minutesAgo(0),
    };
    const client = {
      GET: fn(
        route({
          '/admin/v1/crisis/summary': () => ok(summary),
          '/admin/v1/crisis/alerts': () => ok([alert()]),
          '/admin/v1/crisis/alerts/{alertId}': () => ok(detail),
        }),
      ),
      POST: fn(
        route({
          '/admin/v1/crisis/alerts/{alertId}/acknowledgement': () => ok(acknowledged),
          '/admin/v1/crisis/alerts/{alertId}/contact': () => ok({ phone: '+237670112234' }),
          '/admin/v1/crisis/alerts/{alertId}/closure': () =>
            ok({
              ...acknowledged,
              status: 'CLOSED',
              outcome: 'SAFE_FOR_NOW',
              closedAt: minutesAgo(0),
            }),
        }),
      ),
    };
    setUp(['CRISIS_RESPONDER'], client);
    const { root, settle } = await render(CrisisQueuePage);
    stubDialogs(root);

    await sees(root, 'Seeker #4821');
    root.querySelector<HTMLButtonElement>('button.alert')!.click();
    await sees(root, 'je ne sais pas combien de temps');
    expect(root.textContent).toContain('+237 6•• •• •• 34');
    expect(root.textContent).toContain('Ngo Biyong Claire');

    button(root, 'Acknowledge')!.click();
    await sees(root, 'acknowledged by Nadine Ebai');

    button(root, 'Reveal and call (logged)')!.click();
    await vi.waitFor(() =>
      expect(root.querySelector('a[href="tel:+237670112234"]')).not.toBeNull(),
    );

    const safe = [...root.querySelectorAll('label.choice')]
      .find((l) => l.textContent?.includes('safe for now'))!
      .querySelector('input')!;
    safe.click();
    safe.dispatchEvent(new Event('change'));
    const note = root.querySelector('textarea')!;
    note.value = 'Spoke by phone; session on Monday.';
    note.dispatchEvent(new Event('input'));
    await settle();

    const closeButtons = [...root.querySelectorAll<HTMLButtonElement>('button')].filter(
      (b) => b.textContent?.trim() === 'Close alert',
    );
    closeButtons[0]!.click();
    // Confirm in the dialog.
    closeButtons.at(-1)!.click();
    await vi.waitFor(() =>
      expect(
        client.POST.mock.calls.some((c) => c[0] === '/admin/v1/crisis/alerts/{alertId}/closure'),
      ).toBe(true),
    );
    const closure = client.POST.mock.calls.find(
      (c) => c[0] === '/admin/v1/crisis/alerts/{alertId}/closure',
    )!;
    expect(closure[1]).toEqual({
      params: { path: { alertId: 'a1' } },
      body: {
        outcome: 'SAFE_FOR_NOW',
        note: 'Spoke by phone; session on Monday.',
      },
    });
  });

  it('lets Support look without acting', async () => {
    const client = {
      GET: fn(
        route({
          '/admin/v1/crisis/summary': () => ok(summary),
          '/admin/v1/crisis/alerts': () => ok([alert()]),
          '/admin/v1/crisis/alerts/{alertId}': () =>
            ok(alert({ text: 'x', contact: '+237 6•• •• •• 34' })),
        }),
      ),
      POST: fn(),
    };
    setUp(['SUPPORT'], client);
    const { root } = await render(CrisisQueuePage);

    await sees(root, 'Only crisis responders and Super admins act on alerts.');
    root.querySelector<HTMLButtonElement>('button.alert')!.click();
    await sees(root, '+237 6•• •• •• 34');
    expect(button(root, 'Acknowledge')).toBeUndefined();
    expect(button(root, 'Reveal and call (logged)')).toBeUndefined();
  });

  it('shows the empty state', async () => {
    const client = {
      GET: fn(
        route({
          '/admin/v1/crisis/summary': () => ok({ open: 0, unacknowledged: 0 }),
          '/admin/v1/crisis/alerts': () => ok([]),
        }),
      ),
      POST: fn(),
    };
    setUp(['CRISIS_RESPONDER'], client);
    const { root } = await render(CrisisQueuePage);
    await sees(root, 'No open alerts.');
    expect(root.textContent).toContain('Nobody is on call.');
  });
});

describe('Crisis badge', () => {
  beforeEach(() => TestBed.resetTestingModule());

  it('counts unacknowledged alerts only for staff who may see the queue', async () => {
    const client = { GET: fn(() => ok({ open: 3, unacknowledged: 2 })) };
    setUp(['CRISIS_RESPONDER'], client);
    const watch = TestBed.inject(CrisisWatch);
    await watch.refresh();
    expect(watch.unacknowledged()).toBe(2);

    TestBed.resetTestingModule();
    const none = { GET: fn(() => ok({ open: 3, unacknowledged: 2 })) };
    setUp(['FINANCE'], none);
    const finance = TestBed.inject(CrisisWatch);
    await finance.refresh();
    expect(finance.unacknowledged()).toBe(0);
    expect(none.GET).not.toHaveBeenCalled();
  });
});
