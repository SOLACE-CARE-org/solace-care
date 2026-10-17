import { describe, expect, it } from 'vitest';

import { accessTo } from './permissions';

describe('permission matrix (admin-portal-spec.md)', () => {
  it('gives a Super admin full access everywhere', () => {
    for (const area of ['overview', 'crisis', 'payments', 'team', 'settings'] as const) {
      expect(accessTo(area, ['SUPER_ADMIN'])).toBe('F');
    }
  });

  it('hides team and roles from everyone but a Super admin', () => {
    for (const role of ['OPERATIONS', 'SUPPORT', 'FINANCE', 'CLINICAL_REVIEWER', 'CRISIS_RESPONDER', 'CONTENT_EDITOR']) {
      expect(accessTo('team', [role])).toBeNull();
    }
  });

  it('lets Finance change payments but only view bookings', () => {
    expect(accessTo('payments', ['FINANCE'])).toBe('F');
    expect(accessTo('bookings', ['FINANCE'])).toBe('V');
    expect(accessTo('crisis', ['FINANCE'])).toBeNull();
  });

  it('gives the strongest access when a person holds several roles', () => {
    expect(accessTo('crisis', ['SUPPORT'])).toBe('V');
    expect(accessTo('crisis', ['SUPPORT', 'CRISIS_RESPONDER'])).toBe('F');
  });

  it('ignores roles that are not staff roles', () => {
    expect(accessTo('overview', ['SEEKER', 'offline_access'])).toBeNull();
  });
});
