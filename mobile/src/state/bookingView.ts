import type { Booking, BookingStatus } from '@/api/bookings';
import type { Tone } from '@/ui/Pill';

/** The pill tone for a booking's status (components/Pill). */
export function toneOf(status: BookingStatus): Tone {
  switch (status) {
    case 'CONFIRMED':
    case 'IN_PROGRESS':
      return 'brand';
    case 'PENDING_PAYMENT':
      return 'warning';
    case 'CANCELLED':
    case 'NO_SHOW':
    case 'EXPIRED':
    case 'COMPLETED':
    default:
      return 'neutral';
  }
}

/** Bookings that can still be moved or cancelled: on, and not started (D32). */
export function changeable(b: Pick<Booking, 'status' | 'start'>, now = Date.now()): boolean {
  return (b.status === 'CONFIRMED' || b.status === 'PENDING_PAYMENT') && Date.parse(b.start) > now;
}

/** Minutes the session lasts, from its start and end. */
export function minutesOf(b: Pick<Booking, 'start' | 'end'>): number {
  return Math.round((Date.parse(b.end) - Date.parse(b.start)) / 60_000);
}

/** Joining opens 10 minutes before the start (implementation-plan.md, epic 7). */
export const JOIN_OPENS_MINUTES = 10;

export function joinOpensAt(b: Pick<Booking, 'start'>): Date {
  return new Date(Date.parse(b.start) - JOIN_OPENS_MINUTES * 60_000);
}

/** "Mon 19 Oct, 18:00" in the viewer's language and time zone, capitalised. */
export function longWhen(iso: string, lang: string): string {
  const text = new Intl.DateTimeFormat(lang, {
    weekday: 'short',
    day: 'numeric',
    month: 'short',
    hour: '2-digit',
    minute: '2-digit',
  }).format(new Date(iso));
  return text.charAt(0).toUpperCase() + text.slice(1);
}
