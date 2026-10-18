import { TestBed } from '@angular/core/testing';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import type { CoreSchemas } from '../api/core-api';
import {
  button,
  fn,
  ok,
  problem,
  render,
  route,
  sees,
  setUp,
  stubDialogs,
} from '../testing/harness';
import { ReviewsPage } from './reviews.page';

type Item = CoreSchemas['ModerationItem'];

function item(patch: Partial<Item> = {}): Item {
  return {
    id: 'r1',
    stars: 5,
    text: 'She listened without rushing me. Call her on WhatsApp at 6•• •• •• ••.',
    specialist: { id: 's1', fullName: 'Dr. Fomba Grace' },
    seekerRef: '#3907',
    sessionAt: '2026-10-14T09:00:00Z',
    sessionLanguage: 'en',
    flags: ['CONTACT_DETAILS'],
    status: 'PENDING',
    ...patch,
  };
}

const queue = () => [
  item(),
  item({
    id: 'r2',
    stars: 2,
    text: 'La connexion a coupé deux fois.',
    specialist: { id: 's2', fullName: 'Dr. Eyong Peter' },
    seekerRef: 'G-2170',
    sessionLanguage: 'fr',
    flags: [],
  }),
];

describe('Reviews to moderate', () => {
  beforeEach(() => TestBed.resetTestingModule());

  it('lists the queue and publishes with the number masked when one was found', async () => {
    const client = {
      GET: fn(route({ '/admin/v1/reviews': () => ok(queue()) })),
      POST: fn(() => ok(item({ status: 'PUBLISHED' }))),
    };
    setUp(['SUPPORT'], client);
    const { root } = await render(ReviewsPage);

    await sees(root, '5 of 5 · for Dr. Fomba Grace');
    expect(root.textContent).toContain('2 waiting.');
    expect(root.textContent).toContain('Guest #G-2170');
    expect(root.textContent).toContain('Contains a phone number');

    button(root, 'Publish without the number')!.click();
    await vi.waitFor(() => expect(client.POST).toHaveBeenCalled());
    expect(client.POST.mock.calls[0]).toEqual([
      '/admin/v1/reviews/{reviewId}/publication',
      { params: { path: { reviewId: 'r1' } }, body: { maskContacts: true } },
    ]);
    await vi.waitFor(() => expect(root.textContent).not.toContain('Dr. Fomba Grace'));
  });

  it('hides only with a reason', async () => {
    const client = {
      GET: fn(route({ '/admin/v1/reviews': () => ok(queue()) })),
      POST: fn(() => ok(item({ status: 'HIDDEN' }))),
    };
    setUp(['OPERATIONS'], client);
    const { root, settle } = await render(ReviewsPage);
    stubDialogs(root);
    await sees(root, 'Dr. Eyong Peter');

    button(root, 'Hide, with reason')!.click();
    await settle();
    expect(button(root, 'Hide review')!.disabled).toBe(true);
    const reason = root.querySelector('textarea')!;
    reason.value = 'Mentions a third person by name.';
    reason.dispatchEvent(new Event('input'));
    await settle();
    button(root, 'Hide review')!.click();

    await vi.waitFor(() => expect(client.POST).toHaveBeenCalled());
    expect(client.POST.mock.calls[0]).toEqual([
      '/admin/v1/reviews/{reviewId}/hiding',
      {
        params: { path: { reviewId: 'r1' } },
        body: { reason: 'Mentions a third person by name.' },
      },
    ]);
  });

  it('shows Core’s reason when a decision is refused', async () => {
    const client = {
      GET: fn(route({ '/admin/v1/reviews': () => ok([item({ flags: [] })]) })),
      POST: fn(() => problem(409, 'reviews.decided', 'Someone has already decided this review.')),
    };
    setUp(['SUPPORT'], client);
    const { root } = await render(ReviewsPage);
    await sees(root, 'Dr. Fomba Grace');
    button(root, 'Publish')!.click();
    await sees(root, 'Someone has already decided this review.');
  });

  it('shows the empty state', async () => {
    const client = { GET: fn(route({ '/admin/v1/reviews': () => ok([]) })), POST: fn() };
    setUp(['SUPPORT'], client);
    const { root } = await render(ReviewsPage);
    await sees(root, 'No reviews waiting.');
  });
});
