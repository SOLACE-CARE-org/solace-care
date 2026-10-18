import { TestBed } from '@angular/core/testing';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import type { CoreSchemas } from '../api/core-api';
import { button, fn, ok, render, route, sees, setUp, stubDialogs } from '../testing/harness';
import { PaymentsPage } from './payments.page';
import { PayoutRunPage } from './payout-run.page';

type Refund = CoreSchemas['Refund'];
type Run = CoreSchemas['PayoutRun'];

const summary = {
  held: 2140000,
  released: 1315000,
  refundsToApprove: 1,
  failures: 0,
  lastReconciliation: { day: '2026-10-17', mismatches: 1 },
};

function refund(patch: Partial<Refund> = {}): Refund {
  return {
    id: 'rf1',
    bookingId: '7f9d1c2e-1111-4a7a-b3e5-5f0d9c1a7e64',
    amount: 15000,
    method: 'MTN_MOMO',
    phone: '+237 6•• •• •• 52',
    reason: 'POLICY_NO_SHOW',
    policyVersion: 1,
    proposedBy: { system: false, name: 'E. Nkeng' },
    status: 'PROPOSED',
    createdAt: '2026-10-17T10:00:00Z',
    canDecide: true,
    ...patch,
  };
}

function run(patch: Partial<Run> = {}): Run {
  return {
    id: 'run1',
    periodStart: '2026-10-01',
    periodEnd: '2026-10-15',
    status: 'SUBMITTED',
    preparedBy: 'A. Fon',
    preparedAt: '2026-10-16T10:20:00Z',
    feePercent: 20,
    totals: {
      specialists: 2,
      ready: 1,
      held: 1,
      sessions: 18,
      gross: 360000,
      commission: 72000,
      net: 288000,
    },
    canSubmit: false,
    canApprove: true,
    items: [
      {
        id: 'i1',
        specialistId: 's1',
        name: 'Dr. Mbarga Solange',
        sessions: 18,
        gross: 360000,
        commission: 72000,
        net: 288000,
        method: 'ORANGE_MONEY',
        status: 'READY',
      },
      {
        id: 'i2',
        specialistId: 's2',
        name: 'Dr. Eyong Peter',
        sessions: 11,
        gross: 165000,
        commission: 33000,
        net: 132000,
        method: 'MTN_MOMO',
        status: 'HELD',
        holdReason: 'last payout failed',
      },
    ],
    ...patch,
  };
}

describe('Payments', () => {
  beforeEach(() => TestBed.resetTestingModule());

  it('shows the money tiles and lets a second Finance member approve a refund', async () => {
    const client = {
      GET: fn(
        route({
          '/admin/v1/payments/summary': () => ok(summary),
          '/admin/v1/payments': () => ok([]),
          '/admin/v1/refunds': () => ok([refund()]),
        }),
      ),
      POST: fn(() => ok(refund({ status: 'APPROVED', canDecide: false }))),
    };
    setUp(['FINANCE'], client);
    const { root } = await render(PaymentsPage);
    stubDialogs(root);

    await sees(root, '1 mismatch');
    expect(root.textContent).toMatch(/2.140.000 XAF/);
    button(root, 'Refunds to approve (1)')!.click();
    await sees(root, 'Specialist no-show, policy v1');
    expect(root.textContent).toContain('BK-7F9D1C2E');
    expect(root.textContent).toContain('MTN MoMo · +237 6•• •• •• 52');
    expect(root.textContent).toContain('E. Nkeng');

    // Approve, then confirm in the dialog.
    [...root.querySelectorAll('button')]
      .filter((b) => b.textContent?.trim() === 'Approve')[0]!
      .click();
    [...root.querySelectorAll('button')]
      .filter((b) => b.textContent?.trim() === 'Approve')
      .at(-1)!
      .click();
    await vi.waitFor(() =>
      expect(client.POST.mock.calls[0]).toEqual([
        '/admin/v1/refunds/{refundId}/approval',
        { params: { path: { refundId: 'rf1' } } },
      ]),
    );
  });

  it('gives the proposer no buttons on their own refund (D14)', async () => {
    const client = {
      GET: fn(
        route({
          '/admin/v1/payments/summary': () => ok(summary),
          '/admin/v1/payments': () => ok([]),
          '/admin/v1/refunds': () =>
            ok([refund({ canDecide: false, proposedBy: { system: false, name: 'Nadine Ebai' } })]),
        }),
      ),
      POST: fn(),
    };
    setUp(['FINANCE'], client);
    const { root } = await render(PaymentsPage);
    button(root, 'Refunds to approve (1)')!.click();
    await sees(root, 'You proposed this');
    expect(button(root.querySelector('table')!, 'Approve')).toBeUndefined();
    expect(button(root.querySelector('table')!, 'Decline')).toBeUndefined();
  });
});

describe('Payout run', () => {
  beforeEach(() => TestBed.resetTestingModule());

  it('shows the totals and lets the second approver send it', async () => {
    const client = {
      GET: fn(route({ '/admin/v1/payout-runs/{runId}': () => ok(run()) })),
      POST: fn(() => ok(run({ status: 'SENT', approvedBy: 'B. Tabi', canApprove: false }))),
    };
    setUp(['FINANCE'], client);
    const { root } = await render(PayoutRunPage, { id: 'run1' });
    stubDialogs(root);

    await sees(root, 'Payout run · October 1');
    expect(root.textContent).toContain('Prepared by A. Fon');
    expect(root.textContent).toContain('1 ready · 1 held');
    expect(root.textContent).toContain('Held: last payout failed');

    const approve = [...root.querySelectorAll('button')].filter(
      (b) => b.textContent?.trim() === 'Approve and send 1 payouts',
    );
    approve[0]!.click();
    approve.at(-1)!.click();
    await vi.waitFor(() =>
      expect(client.POST.mock.calls[0]![0]).toBe('/admin/v1/payout-runs/{runId}/approval'),
    );
    await sees(root, 'Approved by B. Tabi');
  });

  it('keeps approve off for the person who prepared it', async () => {
    const client = {
      GET: fn(route({ '/admin/v1/payout-runs/{runId}': () => ok(run({ canApprove: false })) })),
      POST: fn(),
    };
    setUp(['FINANCE'], client);
    const { root } = await render(PayoutRunPage, { id: 'run1' });
    await sees(root, 'A. Fon prepared this run');
    expect(button(root, 'Approve and send 1 payouts')!.disabled).toBe(true);
  });

  it('holds an item in a draft only with a reason', async () => {
    const client = {
      GET: fn(
        route({
          '/admin/v1/payout-runs/{runId}': () =>
            ok(run({ status: 'DRAFT', canSubmit: true, canApprove: false })),
        }),
      ),
      PUT: fn(() => ok(run({ status: 'DRAFT' }))),
      POST: fn(),
    };
    setUp(['FINANCE'], client);
    const { root, settle } = await render(PayoutRunPage, { id: 'run1' });
    stubDialogs(root);
    await sees(root, 'Dr. Mbarga Solange');

    button(root, 'Hold')!.click();
    await settle();
    const reason = root.querySelector('textarea')!;
    reason.value = 'Open dispute on a booking';
    reason.dispatchEvent(new Event('input'));
    await settle();
    button(root, 'Hold payout')!.click();
    await vi.waitFor(() =>
      expect(client.PUT.mock.calls[0]).toEqual([
        '/admin/v1/payout-runs/{runId}/items/{itemId}/hold',
        {
          params: { path: { runId: 'run1', itemId: 'i1' } },
          body: { reason: 'Open dispute on a booking' },
        },
      ]),
    );
  });
});
