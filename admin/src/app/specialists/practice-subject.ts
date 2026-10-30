import type { CoreSchemas } from '../api/core-api';

type Review = CoreSchemas['ApplicationReview'];

/** Activity kinds about a profession, a specialty or a specialty request (D62). */
export const PRACTICE_KIND = /^(PROFESSION|SPECIALTY|REQUEST)_/;

/**
 * What a practice activity entry is about, in the viewer's language: "Clinical psychologist", or
 * "Trauma (Clinical psychologist)" for a subject written "profession/specialty".
 */
export function practiceSubject(review: Review, subject: string | undefined): string {
  if (!subject) return '';
  const [profession, specialty] = subject.split('/');
  const p = review.professions.find((x) => x.profession === profession);
  const professionLabel = p?.label ?? profession;
  if (!specialty) return professionLabel;
  const label = p?.specialties.find((x) => x.specialty === specialty)?.label ?? specialty;
  return `${label} (${professionLabel})`;
}
