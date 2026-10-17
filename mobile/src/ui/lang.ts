import { Platform } from 'react-native';

import type { AppLanguage } from '@/state/session';

/**
 * Tells the screen reader which language a piece of text is in, so French reads with a French
 * voice inside an English screen (the language choice shows both). iOS supports this per element;
 * other platforms ignore it, so it is only passed there.
 */
export function spokenIn(language: AppLanguage) {
  return Platform.OS === 'ios' ? { accessibilityLanguage: language } : {};
}
