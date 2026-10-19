import type { Type } from '@angular/core';
import type { Routes } from '@angular/router';

import { AiControlsPage } from './ai/ai-controls.page';
import { areaGuard } from './auth/area.guard';
import { AuditLogPage } from './audit/audit-log.page';
import { CataloguePage } from './catalogue/catalogue.page';
import { ContentEditorPage } from './content/content-editor.page';
import { CopilotPage } from './copilot/copilot.page';
import { ContentPage } from './content/content.page';
import { PeoplePage } from './people/people.page';
import { PersonRecordPage } from './people/person-record.page';
import { AnalyticsPage } from './analytics/analytics.page';
import { OverviewPage } from './overview/overview.page';
import { BookingDetailPage } from './bookings/booking-detail.page';
import { BookingsPage } from './bookings/bookings.page';
import { CrisisQueuePage } from './crisis/crisis-queue.page';
import { environment } from '../environments/environment';
import { HealthCheckVersionPage } from './health-check/health-check-version.page';
import { HealthCheckPage } from './health-check/health-check.page';
import { ModuleBuilderPage } from './modules/module-builder.page';
import { ModuleReviewPage } from './modules/module-review.page';
import { ModulesPage } from './modules/modules.page';
import { Shell } from './layout/shell';
import { ALL_ENTRIES } from './nav/nav';
import { NoAccess } from './pages/no-access';
import { NotBuilt } from './pages/not-built';
import { PaymentsPage } from './payments/payments.page';
import { PayoutRunPage } from './payments/payout-run.page';
import { PayoutRunsPage } from './payments/payout-runs.page';
import { ReviewsPage } from './reviews/reviews.page';
import { SettingsPage } from './settings/settings.page';
import { ApplicationReviewPage } from './specialists/application-review.page';
import { ApplicationsPage } from './specialists/applications.page';
import { SpecialistRecordPage } from './specialists/specialist-record.page';
import { TeamPage } from './team/team.page';

/** Screens built so far; every other entry shows the not-built stand-in. */
const SCREENS: Record<string, Type<unknown>> = {
  overview: OverviewPage,
  analytics: AnalyticsPage,
  crisis: CrisisQueuePage,
  applications: ApplicationsPage,
  bookings: BookingsPage,
  catalogue: CataloguePage,
  content: ContentPage,
  people: PeoplePage,
  reviews: ReviewsPage,
  payments: PaymentsPage,
  payouts: PayoutRunsPage,
  specialists: SpecialistRecordPage,
  audit: AuditLogPage,
  team: TeamPage,
  settings: SettingsPage,
  modules: ModulesPage,
  'health-check': HealthCheckPage,
  ai: AiControlsPage,
  copilot: CopilotPage,
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
      {
        path: 'bookings/:id',
        component: BookingDetailPage,
        canActivate: [areaGuard('bookings')],
      },
      {
        path: 'content/:id',
        component: ContentEditorPage,
        canActivate: [areaGuard('content')],
      },
      {
        path: 'people/:id',
        component: PersonRecordPage,
        canActivate: [areaGuard('people')],
      },
      {
        path: 'payouts/:id',
        component: PayoutRunPage,
        canActivate: [areaGuard('payments')],
      },
      // The module builder ships behind its Phase 2 flag, like its sidebar entry.
      ...(environment.flags.trackingModules
        ? [
            {
              path: 'modules/versions/:id',
              component: ModuleBuilderPage,
              canActivate: [areaGuard('trackingModules')],
            },
            {
              path: 'modules/versions/:id/review',
              component: ModuleReviewPage,
              canActivate: [areaGuard('trackingModules')],
            },
          ]
        : []),
      ...(environment.flags.healthCheck
        ? [
            {
              path: 'health-check/versions/:id',
              component: HealthCheckVersionPage,
              canActivate: [areaGuard('healthCheck')],
            },
          ]
        : []),
      { path: 'no-access', component: NoAccess },
      { path: '**', component: NotBuilt },
    ],
  },
];
