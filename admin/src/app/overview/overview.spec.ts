import { TestBed } from '@angular/core/testing';
import { beforeEach, describe, expect, it } from 'vitest';

import type { CoreSchemas } from '../api/core-api';
import { AnalyticsPage } from '../analytics/analytics.page';
import { fn, ok, render, route, sees, setUp } from '../testing/harness';
import { OverviewPage } from './overview.page';

const overview: CoreSchemas['Overview'] = {
  at: '2026-10-16T13:32:00Z',
  crisis: {
    unacknowledged: 2,
    oldestAt: new Date(Date.now() - 6 * 60_000).toISOString(),
    onCall: 'Dr. Fomba Grace',
  },
  inSession: { total: 7, video: 5, audio: 2 },
  sessionsToday: { total: 48, completed: 31, noShows: 2 },
  bookingsToday: { total: 26, byLanguage: { fr: 14, en: 12 } },
  paymentsToday: { captured: 412000, failed: 1 },
  sessionsNow: [
    {
      id: 'b1',
      reference: 'BK-00000001',
      startsAt: '2026-10-16T13:30:00Z',
      endsAt: '2026-10-16T14:20:00Z',
      specialist: { id: 's1', fullName: 'Dr. Mbarga Solange' },
      personRef: '#5112',
      modality: 'VIDEO',
      sessionLanguage: 'fr',
      price: 20000,
      status: 'IN_PROGRESS',
      waiting: 'SEEKER',
      disputed: false,
    },
  ],
  attention: { applications: 6, refunds: 2, reviews: 4, disputes: 1, dataRequestsDue: 1 },
  health: [
    { service: 'CORE', status: 'HEALTHY' },
    { service: 'PAYMENTS', status: 'SLOW' },
  ],
};

describe('Overview', () => {
  beforeEach(() => TestBed.resetTestingModule());

  it('shows the crisis banner, today, sessions now and service health', async () => {
    setUp(['SUPPORT'], { GET: fn(route({ '/admin/v1/overview': () => ok(overview) })) });
    const { root, fixture } = await render(OverviewPage);
    await sees(root, '2 crisis alerts are not acknowledged');
    expect(root.textContent).toContain('On call: Dr. Fomba Grace.');
    expect(root.textContent).toContain('5 video · 2 audio');
    expect(root.textContent).toContain('31 completed · 2 no-shows');
    expect(root.textContent).toContain('Waiting room: seeker only');
    expect(root.textContent).toContain('Slow confirmations');
    fixture.destroy();
  });
});

describe('Analytics', () => {
  beforeEach(() => TestBed.resetTestingModule());

  it('draws the funnel and names the biggest drop', async () => {
    const analytics: CoreSchemas['Analytics'] = {
      period: 'LAST_30_DAYS',
      from: '2026-09-19T00:00:00Z',
      to: '2026-10-19T00:00:00Z',
      funnel: { searched: 4820, choseSlot: 1130, paid: 902, completed: 811 },
      averageRating: 4.6,
      ratings: 300,
      bookedAgainPercent: 38,
      completedSessions: 811,
      sessionsByLanguage: { fr: 462, en: 349 },
    };
    setUp(['FINANCE'], { GET: fn(route({ '/admin/v1/analytics': () => ok(analytics) })) });
    const { root } = await render(AnalyticsPage);
    await sees(root, 'Most people who pay complete their session');
    expect(root.textContent).toContain('4,820');
    expect(root.textContent).toContain(
      'The biggest drop is between searching and choosing a slot.',
    );
    expect(root.textContent).toContain('38%');
    expect(root.textContent).toContain('57%');
    expect(root.textContent).toContain('From Phase 2');
  });
});
