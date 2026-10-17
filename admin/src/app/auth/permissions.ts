/**
 * The admin permission matrix (docs/specs/admin-portal-spec.md, Roles and permissions), from the
 * shared contract Core also enforces. F = view and change, V = view only; a missing entry means
 * the area is hidden. One person can hold several roles; the strongest access wins.
 */
import matrix from '@solace/contracts/permissions/admin-permissions.json';

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

export type Area = keyof typeof matrix.areas;

type Row = Partial<Record<StaffRole, Access>>;

/** One definition for the portal and Core (contracts/permissions/admin-permissions.json). */
const MATRIX = matrix.areas as Record<Area, Row>;

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
