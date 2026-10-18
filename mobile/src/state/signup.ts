import { flags } from '@/config/flags';

/**
 * Seeker sign-up has three steps until tracking ships; the tracking step makes four
 * (implementation-plan.md, Phase 2: "until then, sign-up has three steps").
 */
export function signupSteps(): number {
  return flags.trackTab ? 4 : 3;
}

/** Keeps digits and lays them out as "DD / MM / YYYY" while typing. */
export function maskDate(text: string): string {
  const d = text.replace(/\D/g, '').slice(0, 8);
  return [d.slice(0, 2), d.slice(2, 4), d.slice(4, 8)].filter(Boolean).join(' / ');
}

/** "12 / 03 / 1996" as an ISO date (1996-03-12), or null when it is not a real past date. */
export function parseDate(text: string, now = new Date()): string | null {
  const m = /^(\d{2}) \/ (\d{2}) \/ (\d{4})$/.exec(text);
  if (!m) return null;
  const [, dd, mm, yyyy] = m;
  const date = new Date(Date.UTC(Number(yyyy), Number(mm) - 1, Number(dd)));
  if (date.getUTCDate() !== Number(dd) || date.getUTCMonth() !== Number(mm) - 1) return null;
  if (date.getTime() > now.getTime()) return null;
  return `${yyyy}-${mm}-${dd}`;
}

/** Whole years since an ISO date. Core checks again, in Cameroon's time zone. */
export function age(iso: string, today = new Date()): number {
  const [y, m, d] = iso.split('-').map(Number);
  let years = today.getFullYear() - y;
  if (today.getMonth() + 1 < m || (today.getMonth() + 1 === m && today.getDate() < d)) years--;
  return years;
}

/** Keeps digits and lays them out as HH:MM while typing (weekly hours). */
export function maskTime(text: string): string {
  const d = text.replace(/\D/g, '').slice(0, 4);
  return d.length > 2 ? `${d.slice(0, 2)}:${d.slice(2)}` : d;
}
