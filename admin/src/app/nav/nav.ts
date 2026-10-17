import {
  type LucideIconInput,
  LucideCalendar,
  LucideChartLine,
  LucideClipboardList,
  LucideCreditCard,
  LucideFileText,
  LucideLayers,
  LucideLayoutGrid,
  LucideLifeBuoy,
  LucideList,
  LucideMessageSquare,
  LucideSettings,
  LucideSparkles,
  LucideStar,
  LucideStethoscope,
  LucideTag,
  LucideUpload,
  LucideUserCheck,
  LucideUsers,
  LucideUserCog,
} from '@lucide/angular';

import { environment } from '../../environments/environment';
import type { Area } from '../auth/permissions';

export type NavEntry = {
  /** Route path and i18n key under admin.nav.items. */
  key: string;
  path: string;
  area: Area;
  icon: LucideIconInput;
  /** Off for areas whose phase has not shipped. */
  enabled: boolean;
};

export type NavGroup = { key: string; entries: NavEntry[] };

const on = true;

/**
 * The sidebar: seven groups, as on design/screens/admin/source/Sidebar.dc.html. Each entry maps to
 * an area of the permission matrix; a role sees only the entries it may open.
 */
export const NAV: NavGroup[] = [
  {
    key: 'now',
    entries: [
      { key: 'overview', path: 'overview', area: 'overview', icon: LucideLayoutGrid, enabled: on },
      { key: 'crisis', path: 'crisis', area: 'crisis', icon: LucideLifeBuoy, enabled: on },
    ],
  },
  {
    key: 'specialists',
    entries: [
      { key: 'applications', path: 'applications', area: 'specialists', icon: LucideClipboardList, enabled: on },
      { key: 'specialists', path: 'specialists', area: 'specialists', icon: LucideUserCheck, enabled: on },
    ],
  },
  {
    key: 'seekersBookings',
    entries: [
      { key: 'people', path: 'people', area: 'people', icon: LucideUsers, enabled: on },
      { key: 'bookings', path: 'bookings', area: 'bookings', icon: LucideCalendar, enabled: on },
      { key: 'reviews', path: 'reviews', area: 'reviews', icon: LucideStar, enabled: on },
    ],
  },
  {
    key: 'money',
    entries: [
      { key: 'payments', path: 'payments', area: 'payments', icon: LucideCreditCard, enabled: on },
      { key: 'payouts', path: 'payouts', area: 'payments', icon: LucideUpload, enabled: on },
    ],
  },
  {
    key: 'careSetup',
    entries: [
      { key: 'modules', path: 'modules', area: 'trackingModules', icon: LucideLayers, enabled: environment.flags.trackingModules },
      { key: 'healthCheck', path: 'health-check', area: 'healthCheck', icon: LucideStethoscope, enabled: environment.flags.healthCheck },
      { key: 'catalogue', path: 'catalogue', area: 'catalogue', icon: LucideTag, enabled: on },
      { key: 'content', path: 'content', area: 'content', icon: LucideFileText, enabled: on },
    ],
  },
  {
    key: 'insight',
    entries: [
      { key: 'analytics', path: 'analytics', area: 'analytics', icon: LucideChartLine, enabled: on },
      { key: 'ai', path: 'ai', area: 'aiControls', icon: LucideSparkles, enabled: environment.flags.aiControls },
      // Copilot (Phase 4) is not in the permission matrix yet; it follows AI controls until it is.
      { key: 'copilot', path: 'copilot', area: 'aiControls', icon: LucideMessageSquare, enabled: environment.flags.copilot },
    ],
  },
  {
    key: 'admin',
    entries: [
      { key: 'audit', path: 'audit', area: 'audit', icon: LucideList, enabled: on },
      { key: 'team', path: 'team', area: 'team', icon: LucideUserCog, enabled: on },
      { key: 'settings', path: 'settings', area: 'settings', icon: LucideSettings, enabled: on },
    ],
  },
];

export const ALL_ENTRIES: NavEntry[] = NAV.flatMap((group) => group.entries);
