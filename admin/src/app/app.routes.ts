import type { Routes } from '@angular/router';

import { areaGuard } from './auth/area.guard';
import { Shell } from './layout/shell';
import { ALL_ENTRIES } from './nav/nav';
import { NoAccess } from './pages/no-access';
import { NotBuilt } from './pages/not-built';

/**
 * Every sidebar entry is a route guarded by its area of the permission matrix. Later-phase areas
 * are not routed until their flag is on. The screens themselves arrive with their epics.
 */
export const routes: Routes = [
  {
    path: '',
    component: Shell,
    children: [
      { path: '', pathMatch: 'full', redirectTo: 'overview' },
      ...ALL_ENTRIES.filter((entry) => entry.enabled).map((entry) => ({
        path: entry.path,
        component: NotBuilt,
        canActivate: [areaGuard(entry.area)],
      })),
      { path: 'no-access', component: NoAccess },
      { path: '**', component: NotBuilt },
    ],
  },
];
