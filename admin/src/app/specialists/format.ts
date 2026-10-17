/**
 * Dates, money and language names for the specialist screens, in the portal language and the
 * viewer's time zone (CLAUDE.md, Language).
 */

/** "9 Oct" / "9 oct." */
export function shortDate(iso: string | undefined, lang: string): string {
  if (!iso) return '';
  return new Intl.DateTimeFormat(lang, {
    day: 'numeric',
    month: 'short',
  }).format(new Date(iso));
}

/** "9 Oct 2026, 14:30" */
export function dateTime(iso: string | undefined, lang: string): string {
  if (!iso) return '';
  return new Intl.DateTimeFormat(lang, {
    dateStyle: 'medium',
    timeStyle: 'short',
  }).format(new Date(iso));
}

/** A calendar date ("2028-10-17") without shifting it through a time zone. */
export function calendarDate(isoDate: string | undefined, lang: string): string {
  if (!isoDate) return '';
  const [y, m, d] = isoDate.split('-').map(Number);
  return new Intl.DateTimeFormat(lang, {
    dateStyle: 'medium',
    timeZone: 'UTC',
  }).format(new Date(Date.UTC(y!, m! - 1, d!)));
}

/** "15 000 XAF" in the portal language's grouping. */
export function fcfa(amount: number, lang: string): string {
  return `${new Intl.NumberFormat(lang).format(amount)} XAF`;
}

/** "French" / "français", from the platform rather than the catalogue's endonyms. */
export function languageName(code: string, lang: string): string {
  try {
    return new Intl.DisplayNames([lang], { type: 'language' }).of(code) ?? code;
  } catch {
    return code;
  }
}

/** The same, starting with a capital letter, for the start of a line. */
export function capitalised(text: string): string {
  return text.charAt(0).toUpperCase() + text.slice(1);
}

/** Whole days since a moment; 0 on the same day. */
export function daysSince(iso: string | undefined, now = new Date()): number {
  if (!iso) return 0;
  return Math.max(0, Math.floor((now.getTime() - new Date(iso).getTime()) / 86_400_000));
}

/** Initials for the avatar disc. */
export function initials(name: string): string {
  return name
    .split(/\s+/)
    .filter(Boolean)
    .slice(0, 2)
    .map((word) => word[0]!.toUpperCase())
    .join('');
}
