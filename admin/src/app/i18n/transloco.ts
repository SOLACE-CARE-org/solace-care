import { Injectable, isDevMode } from '@angular/core';
import { provideTransloco, type Translation, type TranslocoLoader } from '@jsverse/transloco';
import en from '@solace/i18n/en.json';
import fr from '@solace/i18n/fr.json';
import { of } from 'rxjs';

export type AdminLanguage = 'en' | 'fr';

const CATALOGUES: Record<AdminLanguage, Translation> = { en, fr };
const STORAGE_KEY = 'solace.admin.language';

/** The shared EN/FR catalogues from packages/i18n, bundled with the portal. */
@Injectable({ providedIn: 'root' })
class SharedCatalogues implements TranslocoLoader {
  getTranslation(language: string) {
    return of(CATALOGUES[language as AdminLanguage] ?? en);
  }
}

/** The admin language: the one chosen before (a per-browser convenience), else the browser's. */
export function startLanguage(): AdminLanguage {
  try {
    const saved = localStorage.getItem(STORAGE_KEY);
    if (saved === 'en' || saved === 'fr') return saved;
  } catch {
    // Storage can be unavailable (private windows); fall through.
  }
  return navigator.language?.toLowerCase().startsWith('fr') ? 'fr' : 'en';
}

export function rememberLanguage(language: AdminLanguage) {
  try {
    localStorage.setItem(STORAGE_KEY, language);
  } catch {
    // Not remembered; the portal still works.
  }
}

export function provideAdminTransloco() {
  return provideTransloco({
    config: {
      availableLangs: ['en', 'fr'],
      defaultLang: startLanguage(),
      fallbackLang: 'en',
      reRenderOnLangChange: true,
      prodMode: !isDevMode(),
    },
    loader: SharedCatalogues,
  });
}
