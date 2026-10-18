import type { Type } from '@angular/core';
import type { Routes } from '@angular/router';

import { areaGuard } from './auth/area.guard';
import { CrisisQueuePage } from './crisis/crisis-queue.page';
import { Shell } from './layout/shell';
import { ALL_ENTRIES } from './nav/nav';
import { NoAccess } from './pages/no-access';
import { NotBuilt } from './pages/not-built';
import { ApplicationReviewPage } from './specialists/application-review.page';
import { ApplicationsPage } from './specialists/applications.page';
import { SpecialistRecordPage } from './specialists/specialist-record.page';

/** Screens built so far; every other entry shows the not-built stand-in. */
const SCREENS: Record<string, Type<unknown>> = {
  crisis: CrisisQueuePage,
  applications: ApplicationsPage,
  specialists: SpecialistRecordPage,
};

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
        component: SCREENS[entry.path] ?? NotBuilt,
        canActivate: [areaGuard(entry.area)],
      })),
      // Detail screens open from their list and are not in the sidebar.
      {
        path: 'applications/:id',
        component: ApplicationReviewPage,
        canActivate: [areaGuard('specialists')],
      },
      {
        path: 'specialists/:id',
        component: SpecialistRecordPage,
        canActivate: [areaGuard('specialists')],
      },
      { path: 'no-access', component: NoAccess },
      { path: '**', component: NotBuilt },
    ],
  },
];
