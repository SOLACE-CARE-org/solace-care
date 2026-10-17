import en from '@solace/i18n/en.json';
import fr from '@solace/i18n/fr.json';
import { getLocales } from 'expo-localization';
import i18n from 'i18next';
import { initReactI18next } from 'react-i18next';

import type { AppLanguage } from '@/state/session';

/** EN or FR from the device settings, for the first screens before the person chooses. */
export function deviceLanguage(): AppLanguage {
  const code = getLocales()[0]?.languageCode;
  return code === 'fr' ? 'fr' : 'en';
}

/** Shared catalogues from packages/i18n; components never hard-code text. */
export function initI18n(language: AppLanguage) {
  if (i18n.isInitialized) {
    return i18n.changeLanguage(language);
  }
  return i18n.use(initReactI18next).init({
    resources: { en: { translation: en }, fr: { translation: fr } },
    lng: language,
    fallbackLng: 'en',
    interpolation: { escapeValue: false },
    returnNull: false,
  });
}

export default i18n;
