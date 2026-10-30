import type { TFunction } from 'i18next';

/** "18:00" in the viewer's language and time zone. */
export function timeOf(date: Date, lang: string): string {
  return new Intl.DateTimeFormat(lang, { hour: '2-digit', minute: '2-digit' }).format(date);
}

/**
 * When a session could start, as the Search and Specialist screens say it: "today, 18:00",
 * "tomorrow, 09:00", else "Thu 22 Oct, 16:00". In the viewer's time zone (CLAUDE.md, Language).
 */
export function whenLabel(iso: string, t: TFunction, lang: string, now = new Date()): string {
  const date = new Date(iso);
  const time = timeOf(date, lang);
  const day = (d: Date) => new Date(d.getFullYear(), d.getMonth(), d.getDate()).getTime();
  const days = Math.round((day(date) - day(now)) / 86_400_000);
  if (days === 0) return t('search.today', { time });
  if (days === 1) return t('search.tomorrow', { time });
  return new Intl.DateTimeFormat(lang, {
    weekday: 'short',
    day: 'numeric',
    month: 'short',
    hour: '2-digit',
    minute: '2-digit',
  }).format(date);
}

/** Whole FCFA with the language's grouping: "15,000" / "15 000". */
export function money(amount: number, lang: string): string {
  return new Intl.NumberFormat(lang).format(amount);
}

/** Initials for an avatar disc: first letters of the first two words. */
export function initials(name: string): string {
  return name
    .split(/\s+/)
    .filter(Boolean)
    .slice(0, 2)
    .map((word) => word[0]!.toUpperCase())
    .join('');
}

/** The same text starting with a capital, for the start of a line. */
export function capitalised(text: string): string {
  return text.charAt(0).toUpperCase() + text.slice(1);
}

/**
 * "Licensed counsellor · Marriage and couples counselling": the professions, then the
 * specialties, as search cards and profiles show them (D62).
 */
export function practiceLine(
  professions: string[] | undefined,
  specialties: string[] | undefined,
  fallback?: string,
): string {
  const who = professions && professions.length > 0 ? professions.join(', ') : (fallback ?? '');
  const what = (specialties ?? []).join(', ');
  return [who, what].filter(Boolean).join(' · ');
}
