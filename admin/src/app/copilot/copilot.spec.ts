import { TestBed } from '@angular/core/testing';
import { beforeEach, describe, expect, it } from 'vitest';

import type { AiSchemas } from '../api/ai-api';
import { button, fn, ok, problem, render, route, sees, setUp } from '../testing/harness';
import { CopilotPage } from './copilot.page';

const answer: AiSchemas['CopilotAnswer'] = {
  answer: 'In September, 112 bookings came from tracking recommendations.',
  aiWorded: true,
  metric: 'INVITES_BY_MODULE',
  period: 'LAST_MONTH',
  columns: ['Module', 'Invites shown', 'Bookings'],
  rows: [
    ['PCOS & cycle', '310', '74'],
    ['Daily check-in', '205', '38'],
  ],
  source: 'Source: tracking invites shown',
  from: '2026-09-01T00:00:00Z',
  to: '2026-10-01T00:00:00Z',
  suppressed: false,
};

describe('Admin copilot', () => {
  beforeEach(() => TestBed.resetTestingModule());

  it('answers a suggested question with the table behind it, and shows alerts', async () => {
    const post = fn(() => ok(answer));
    setUp(
      ['FINANCE'],
      {
        GET: fn(
          route({
            '/admin/v1/insight/alerts': () =>
              ok([
                {
                  kind: 'BOOKING_DROP',
                  detectedAt: '2026-10-19T08:00:00Z',
                  value: 3,
                  baseline: 20,
                },
              ]),
          }),
        ),
      },
      { ai: { POST: post } },
    );
    const { root, settle } = await render(CopilotPage);
    await sees(root, 'Booking drop');
    expect(root.textContent).toContain('It cannot look up a person and cannot change anything.');
    button(
      root,
      'How many bookings came from tracking recommendations last month, by module?',
    )!.click();
    await settle();
    await sees(root, 'In September, 112 bookings came from tracking recommendations.');
    expect(root.textContent).toContain('PCOS & cycle');
    expect(root.textContent).toContain('310');
    expect(root.textContent).toContain('Worded by AI from this table only');
    expect(root.textContent).toContain('Open in Analytics');
    expect(post).toHaveBeenCalledWith('/admin/v1/copilot/questions', {
      body: { metric: 'INVITES_BY_MODULE', period: 'LAST_MONTH' },
    });
  });

  it('says when a question in words does not match a metric', async () => {
    setUp(
      ['SUPER_ADMIN'],
      { GET: fn(() => ok([])) },
      {
        ai: {
          POST: fn(() => problem(409, 'copilot.no-match', 'That question did not match a metric.')),
        },
      },
    );
    const { root, settle } = await render(CopilotPage);
    const input = root.querySelector('input[name="question"]') as HTMLInputElement;
    input.value = 'Who booked the most?';
    input.dispatchEvent(new Event('input'));
    await settle();
    button(root, 'Ask')!.click();
    await settle();
    await sees(root, 'That question did not match a metric.');
  });
});
