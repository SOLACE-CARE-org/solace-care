import { inject } from '@angular/core';
import { type CanActivateFn, Router } from '@angular/router';

import type { Area } from './permissions';
import { StaffSession } from './staff-session';

/**
 * Lets a route open only for roles with access to its area. A direct link to a hidden area shows
 * the No access state (Sidebar guidelines: entries are hidden, never greyed out).
 */
export function areaGuard(area: Area): CanActivateFn {
  return () => {
    const session = inject(StaffSession);
    return session.accessTo(area) !== null || inject(Router).parseUrl('/no-access');
  };
}
