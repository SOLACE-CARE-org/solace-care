import type { CatalogueModule, Element, Observation } from '@/api/tracking';

type Lang = 'en' | 'fr';
type Label = { name?: string; help?: string; values?: Record<string, string> };

function labels(e: Element, lang: Lang): Label {
  const all = e.labels as Record<string, Label | undefined>;
  return all[lang] ?? all.en ?? {};
}

/** An element's name in the app language. */
export function nameOf(e: Element, lang: Lang): string {
  return labels(e, lang).name ?? e.code;
}

export function helpOf(e: Element, lang: Lang): string | undefined {
  return labels(e, lang).help;
}

/** The labelled choices of a scale or an enum, in order. */
export function choicesOf(e: Element, lang: Lang): { value: string; label: string }[] {
  const named = labels(e, lang).values ?? {};
  if (e.valueType === 'ENUM') {
    return (e.constraints.values ?? []).map((v) => ({ value: v, label: named[v] ?? v }));
  }
  if (e.valueType === 'INT') {
    const min = e.constraints.min ?? 1;
    const max = e.constraints.max ?? 5;
    return Array.from({ length: max - min + 1 }, (_, i) => {
      const v = String(min + i);
      return { value: v, label: named[v] ?? v };
    });
  }
  return [];
}

export function moduleName(m: Pick<CatalogueModule, 'name'>, lang: Lang): string {
  return (lang === 'fr' ? m.name.fr : m.name.en) || m.name.en;
}

export function moduleDescription(m: Pick<CatalogueModule, 'description'>, lang: Lang): string {
  return (lang === 'fr' ? m.description.fr : m.description.en) || m.description.en;
}

/** The phone's calendar day as YYYY-MM-DD. */
export function localDay(at: Date = new Date()): string {
  const y = at.getFullYear();
  const m = String(at.getMonth() + 1).padStart(2, '0');
  const d = String(at.getDate()).padStart(2, '0');
  return `${y}-${m}-${d}`;
}

/** 390 minutes as { h: 6, m: "30" }. */
export function hoursAndMinutes(minutes: number): { h: number; m: string } {
  return { h: Math.floor(minutes / 60), m: String(minutes % 60).padStart(2, '0') };
}

/** The newest value per element for one day. */
export function valuesOn(observations: Observation[], day: string): Map<string, Observation> {
  const latest = new Map<string, Observation>();
  for (const o of observations) {
    if (o.observedOn !== day) continue;
    const seen = latest.get(o.element.code);
    if (!seen || seen.recordedAt < o.recordedAt) latest.set(o.element.code, o);
  }
  return latest;
}

/** The day a range of N days starts on, counting today. */
export function daysBack(n: number, from: Date = new Date()): string {
  const d = new Date(from);
  d.setDate(d.getDate() - (n - 1));
  return localDay(d);
}

export const RANGES = { TWO_WEEKS: 14, MONTH: 30, THREE_MONTHS: 90 } as const;
export type Range = keyof typeof RANGES;
