import type { CatalogueModule, Element } from '@/api/tracking';

type Lang = 'en' | 'fr';

/**
 * How a module is shown, from its presentation (Tracking Service Spec, Modules; D61). The app reads
 * these instead of checking module or element codes.
 */

/** The episode model whose day a module shows, if any. */
export function episodeModel(m: CatalogueModule | undefined): string | undefined {
  return m?.presentation?.episode?.model;
}

/** The module's day label with the day filled in, e.g. "cycle day 12". */
export function dayLabel(
  m: CatalogueModule | undefined,
  lang: Lang,
  n: number | undefined,
): string | undefined {
  const label = m?.presentation?.episode?.dayLabel?.[lang];
  return label && n !== undefined ? label.split('{{n}}').join(String(n)) : undefined;
}

/** A label that opens a line, e.g. "Cycle day 12 · logged today". */
export function capitalised(text: string): string {
  return text.charAt(0).toLocaleUpperCase() + text.slice(1);
}

export interface Section {
  key: string;
  /** The group's label; undefined for an element on its own. */
  label?: string;
  elements: Element[];
}

/** The module's elements in order, grouped elements together under their group's label. */
export function sections(
  m: CatalogueModule | undefined,
  library: Element[] | undefined,
  lang: Lang,
): Section[] {
  const out: Section[] = [];
  const bindings = (m?.elements ?? [])
    .slice()
    .sort((a, b) => (a.position ?? 0) - (b.position ?? 0));
  for (const b of bindings) {
    const e = library?.find((x) => x.code === b.code);
    if (!e) continue;
    const last = out[out.length - 1];
    if (b.group && last?.key === `group:${b.group.code}`) {
      last.elements.push(e);
    } else if (b.group) {
      out.push({
        key: `group:${b.group.code}`,
        label: b.group.labels[lang],
        elements: [e],
      });
    } else {
      out.push({ key: e.code, elements: [e] });
    }
  }
  return out;
}
