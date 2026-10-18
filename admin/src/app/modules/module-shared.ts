import type { TrackingSchemas } from '../api/tracking-api';

/** Accent colours are design tokens (D51). */
export const ACCENTS = ['brand', 'track-pink', 'track-gold', 'track-blue'] as const;
export type Accent = (typeof ACCENTS)[number];

/** Operations and Super admins draft and publish; Clinical reviewers approve (D51). */
export function canDraft(roles: readonly string[]): boolean {
  return roles.includes('OPERATIONS') || roles.includes('SUPER_ADMIN');
}

export function canReview(roles: readonly string[]): boolean {
  return roles.includes('CLINICAL_REVIEWER');
}

/** The state a version is shown in: an approved version is still IN_REVIEW until published. */
export function stateOf(status: string | undefined, approvedBy: string | undefined): string {
  if (status === 'IN_REVIEW' && approvedBy) return 'APPROVED';
  return status ?? 'DRAFT';
}

export function textIn(text: TrackingSchemas['Text'] | undefined, lang: string): string {
  if (!text) return '';
  return (lang === 'fr' ? text.fr : text.en) || text.en || text.fr || '';
}
