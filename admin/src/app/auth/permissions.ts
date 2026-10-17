/**
 * The admin permission matrix (docs/specs/admin-portal-spec.md, Roles and permissions).
 * F = view and change, V = view only; a missing entry means the area is hidden.
 * One person can hold several roles; the strongest access wins.
 */
export const STAFF_ROLES = [
  'SUPER_ADMIN',
  'OPERATIONS',
  'SUPPORT',
  'FINANCE',
  'CLINICAL_REVIEWER',
  'CRISIS_RESPONDER',
  'CONTENT_EDITOR',
] as const;

export type StaffRole = (typeof STAFF_ROLES)[number];
export type Access = 'F' | 'V';

export type Area =
  | 'overview'
  | 'crisis'
  | 'specialists'
  | 'bookings'
  | 'people'
  | 'payments'
  | 'reviews'
  | 'trackingModules'
  | 'healthCheck'
  | 'aiControls'
  | 'catalogue'
  | 'content'
  | 'analytics'
  | 'audit'
  | 'team'
  | 'settings';

type Row = Partial<Record<StaffRole, Access>>;

const MATRIX: Record<Area, Row> = {
  overview: { SUPER_ADMIN: 'F', OPERATIONS: 'F', SUPPORT: 'V', FINANCE: 'V', CLINICAL_REVIEWER: 'V', CRISIS_RESPONDER: 'V', CONTENT_EDITOR: 'V' },
  crisis: { SUPER_ADMIN: 'F', OPERATIONS: 'V', SUPPORT: 'V', CLINICAL_REVIEWER: 'V', CRISIS_RESPONDER: 'F' },
  specialists: { SUPER_ADMIN: 'F', OPERATIONS: 'F', SUPPORT: 'V', FINANCE: 'V', CLINICAL_REVIEWER: 'V' },
  bookings: { SUPER_ADMIN: 'F', OPERATIONS: 'F', SUPPORT: 'F', FINANCE: 'V', CRISIS_RESPONDER: 'V' },
  people: { SUPER_ADMIN: 'F', OPERATIONS: 'F', SUPPORT: 'V', CRISIS_RESPONDER: 'V' },
  payments: { SUPER_ADMIN: 'F', OPERATIONS: 'V', SUPPORT: 'V', FINANCE: 'F' },
  reviews: { SUPER_ADMIN: 'F', OPERATIONS: 'F', SUPPORT: 'F' },
  trackingModules: { SUPER_ADMIN: 'F', OPERATIONS: 'F', CLINICAL_REVIEWER: 'F', CONTENT_EDITOR: 'V' },
  healthCheck: { SUPER_ADMIN: 'F', OPERATIONS: 'V', CLINICAL_REVIEWER: 'F', CONTENT_EDITOR: 'V' },
  aiControls: { SUPER_ADMIN: 'F', OPERATIONS: 'V', CLINICAL_REVIEWER: 'V' },
  catalogue: { SUPER_ADMIN: 'F', OPERATIONS: 'F', SUPPORT: 'V', FINANCE: 'V', CONTENT_EDITOR: 'V' },
  content: { SUPER_ADMIN: 'F', OPERATIONS: 'V', SUPPORT: 'V', CLINICAL_REVIEWER: 'V', CRISIS_RESPONDER: 'V', CONTENT_EDITOR: 'F' },
  analytics: { SUPER_ADMIN: 'F', OPERATIONS: 'F', SUPPORT: 'V', FINANCE: 'F', CLINICAL_REVIEWER: 'V' },
  audit: { SUPER_ADMIN: 'F', OPERATIONS: 'V' },
  team: { SUPER_ADMIN: 'F' },
  settings: { SUPER_ADMIN: 'F', OPERATIONS: 'V' },
};

/** The strongest access any of the roles gives to the area, or null if it is hidden. */
export function accessTo(area: Area, roles: readonly string[]): Access | null {
  let best: Access | null = null;
  for (const role of roles) {
    const access = MATRIX[area][role as StaffRole];
    if (access === 'F') return 'F';
    if (access === 'V') best = 'V';
  }
  return best;
}

export function isStaffRole(role: string): role is StaffRole {
  return (STAFF_ROLES as readonly string[]).includes(role);
}
