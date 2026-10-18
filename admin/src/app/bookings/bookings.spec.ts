import { TestBed } from '@angular/core/testing';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import type { CoreSchemas } from '../api/core-api';
import { button, fn, ok, problem, render, route, sees, setUp } from '../testing/harness';
import { BookingDetailPage } from './booking-detail.page';
import { BookingsPage } from './bookings.page';

type Row = CoreSchemas['AdminBookingRow'];
type Booking = CoreSchemas['AdminBooking'];

function row(patch: Partial<Row> = {}): Row {
  return {
    id: '7f9d1c2e-0000-4000-8000-000000000001',
    reference: 'BK-7F9D1C2E',
    startsAt: '2026-10-18T09:00:00Z',
    endsAt: '2026-10-18T09:50:00Z',
    specialist: { id: 's1', fullName: 'Tchakounté Arnaud' },
    personRef: '#5480',
    modality: 'AUDIO',
    sessionLanguage: 'fr',
    price: 15000,
    status: 'CONFIRMED',
    disputed: false,
    ...patch,
  };
}

function booking(patch: Partial<Booking> = {}): Booking {
  return {
    ...row({ status: 'COMPLETED', disputed: true }),
    createdAt: '2026-10-12T08:14:00Z',
    source: 'SEARCH',
    reschedules: 0,
    policy: {
      version: 1,
      seekerNoShowRefundPercent: 0,
      specialistNoShowRefundPercent: 100,
    },
    money: {
      method: 'MTN_MOMO',
      phone: '•••• 4821',
      amount: 15000,
      status: 'COMPLETE',
      held: 0,
      refunded: 0,
    },
    dispute: {
      openedAt: '2026-10-18T09:40:00Z',
      openedBy: 'E. Nkeng',
      note: 'I waited and nobody came.',
    },
    timeline: [
      { at: '2026-10-12T08:14:00Z', kind: 'BOOKED', amount: 15000 },
      { at: '2026-10-18T08:58:00Z', kind: 'JOINED', role: 'SEEKER' },
    ],
    canCancel: false,
    ...patch,
  };
}

describe('Bookings', () => {
  beforeEach(() => TestBed.resetTestingModule());

  it('lists bookings with pseudonymous people and dispute states', async () => {
    const client = {
      GET: fn(
        route({
          '/admin/v1/bookings': () =>
            ok([
              row({ disputed: true, status: 'NO_SHOW' }),
              row({
                id: 'x2',
                reference: 'BK-00000002',
                personRef: 'G-2244',
                waiting: 'SEEKER',
              }),
            ]),
        }),
      ),
    };
    setUp(['SUPPORT'], client);
    const { root } = await render(BookingsPage);

    await sees(root, 'BK-7F9D1C2E');
    expect(root.textContent).toContain('No-show disputed');
    expect(root.textContent).toContain('Guest #G-2244');
    expect(root.textContent).toContain('Seeker waiting');
    expect(root.textContent).toContain('Seeker #5480');
  });

  it('explains a search that is not a booking reference', async () => {
    const client = {
      GET: fn(
        route({
          '/admin/v1/bookings': () => problem(400, 'request.invalid', 'Bad'),
        }),
      ),
    };
    setUp(['SUPPORT'], client);
    const { root } = await render(BookingsPage);
    await sees(root, 'Search with a booking reference');
  });

  it('resolves a dispute at the policy amount, and asks for a reason to change it', async () => {
    const client = {
      GET: fn(route({ '/admin/v1/bookings/{bookingId}': () => ok(booking()) })),
      POST: fn(() => ok(booking({ disputed: false }))),
    };
    setUp(['SUPPORT'], client);
    const { root, settle } = await render(BookingDetailPage, { id: 'b1' });

    await sees(root, 'I waited and nobody came.');
    const radio = root.querySelectorAll<HTMLInputElement>('input[type="radio"]')[0]!;
    radio.click();
    await settle();
    expect(root.textContent).toContain('Policy proposes: refund 15,000 XAF to the seeker');

    const amount = root.querySelector<HTMLInputElement>('input[type="number"]')!;
    amount.value = '5000';
    amount.dispatchEvent(new Event('input'));
    await settle();
    expect(root.textContent).toContain('Reason (required)');
    expect(button(root, 'Resolve and notify both')!.disabled).toBe(true);

    amount.value = '';
    amount.dispatchEvent(new Event('input'));
    await settle();
    button(root, 'Resolve and notify both')!.click();
    await vi.waitFor(() => expect(client.POST).toHaveBeenCalled());
    expect(client.POST.mock.calls[0]).toEqual([
      '/admin/v1/bookings/{bookingId}/dispute-resolution',
      {
        params: { path: { bookingId: 'b1' } },
        body: { outcome: 'SPECIALIST_NO_SHOW' },
      },
    ]);
  });

  it('shows no actions to staff who may only view bookings', async () => {
    const client = {
      GET: fn(route({ '/admin/v1/bookings/{bookingId}': () => ok(booking()) })),
    };
    setUp(['FINANCE'], client);
    const { root } = await render(BookingDetailPage, { id: 'b1' });
    await sees(root, 'Timeline');
    expect(root.textContent).not.toContain('Who missed the session?');
  });
});
