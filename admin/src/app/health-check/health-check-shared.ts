import { type CoreApi, orThrow } from '../api/core-api';

/** A Clinical reviewer or a Super admin writes, submits and publishes (D55). */
export function canWrite(roles: readonly string[]): boolean {
  return roles.includes('CLINICAL_REVIEWER') || roles.includes('SUPER_ADMIN');
}

/** An approved version stays IN_REVIEW until it is published. */
export function hcState(status: string | undefined, approvedBy: string | undefined): string {
  if (status === 'IN_REVIEW' && approvedBy) return 'APPROVED';
  return status ?? 'DRAFT';
}

/** Specialty names in the portal language, from Core's catalogue; codes when it cannot be read. */
export async function specialtyNames(api: CoreApi, lang: string): Promise<Record<string, string>> {
  try {
    const catalogue = orThrow(await api.client.GET('/admin/v1/catalogue'));
    return Object.fromEntries(
      catalogue.specialties.map((s) => [s.code, lang === 'fr' ? s.labelFr : s.labelEn]),
    );
  } catch {
    return {};
  }
}
