import { spacedLocalNumber } from '@/ui/TextField';

/** Phone numbers are Cameroonian on the screens: +237 and nine digits. */
export const CAMEROON_PREFIX = '+237';

/** The nine local digits as E.164 (+237677000000), or null when incomplete. */
export function toE164(localDigits: string): string | null {
  const digits = localDigits.replace(/\D/g, '');
  return digits.length === 9 ? `${CAMEROON_PREFIX}${digits}` : null;
}

/** A contact as the screens show it: "+237 6 77 00 00 00", or the email as typed. */
export function displayContact(contact: string): string {
  return contact.startsWith(CAMEROON_PREFIX)
    ? `${CAMEROON_PREFIX} ${spacedLocalNumber(contact.slice(CAMEROON_PREFIX.length))}`
    : contact;
}
