import {
  ChangeDetectionStrategy,
  Component,
  computed,
  effect,
  inject,
  input,
  output,
  signal,
  untracked,
} from '@angular/core';
import { FormsModule } from '@angular/forms';
import { TranslocoPipe, TranslocoService } from '@jsverse/transloco';

import { orThrow } from '../api/core-api';
import { TrackingApi, type TrackingSchemas } from '../api/tracking-api';

type Version = TrackingSchemas['ModuleVersion'];
type Element = TrackingSchemas['Element'];
type Binding = TrackingSchemas['Binding'];
type Group = TrackingSchemas['BindingGroup'];
type Presentation = TrackingSchemas['Presentation'];
type Vocabulary = TrackingSchemas['RuleVocabulary'];

/** The presentation as the form holds it; an empty text leaves that part to the app (D61). */
interface Shown {
  titleEn: string;
  titleFr: string;
  saveEn: string;
  saveFr: string;
  model: string;
  dayEn: string;
  dayFr: string;
  historyEn: string;
  historyFr: string;
  trends: string[];
  quickLog: string;
}

function shownOf(p: Presentation | undefined): Shown {
  return {
    titleEn: p?.title?.en ?? '',
    titleFr: p?.title?.fr ?? '',
    saveEn: p?.saveLabel?.en ?? '',
    saveFr: p?.saveLabel?.fr ?? '',
    model: p?.episode?.model ?? '',
    dayEn: p?.episode?.dayLabel?.en ?? '',
    dayFr: p?.episode?.dayLabel?.fr ?? '',
    historyEn: p?.episode?.historyTitle?.en ?? '',
    historyFr: p?.episode?.historyTitle?.fr ?? '',
    trends: [...(p?.trends ?? [])],
    quickLog: p?.quickLog ?? '',
  };
}

const MAX_TRENDS = 12;

/** More than this many elements is a warning, not a stop (D51). */
const ELEMENT_WARNING = 6;

/**
 * Builder step 2 (admin screen 23): elements from the engineer-seeded library, in order, each
 * required or not. Reordering uses buttons, so it works without a mouse.
 */
@Component({
  selector: 'sl-elements-step',
  imports: [TranslocoPipe, FormsModule],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="layout">
      <section class="sl-card" aria-labelledby="library-title">
        <h2 id="library-title" class="section">
          {{ 'admin.modules.elements.library' | transloco }}
        </h2>
        <p class="sl-meta">{{ 'admin.modules.elements.libraryNote' | transloco }}</p>
        <ul class="list">
          @for (e of library(); track e.code + e.version) {
            <li class="row">
              <div>
                <p class="name">{{ label(e) }}</p>
                <p class="sl-meta">
                  {{ e.code }} v{{ e.version }} · {{ e.inputView }}
                  @if (e.valueType === 'TEXT') {
                    · {{ 'admin.modules.elements.neverRead' | transloco }}
                  }
                </p>
              </div>
              @if (has(e)) {
                <span class="sl-pill sl-pill--brand">{{
                  'admin.modules.elements.added' | transloco
                }}</span>
              } @else if (editable()) {
                <button type="button" class="sl-button" (click)="add(e)">
                  {{ 'admin.modules.elements.add' | transloco }}
                </button>
              }
            </li>
          }
        </ul>
      </section>

      <section class="sl-card" aria-labelledby="chosen-title">
        <h2 id="chosen-title" class="section">
          {{ 'admin.modules.elements.inModule' | transloco: { n: chosen().length } }}
        </h2>
        <ol class="list">
          @for (b of chosen(); track b.code; let i = $index; let last = $last) {
            <li class="row">
              <p class="name">{{ i + 1 }} · {{ nameOf(b.code) }}</p>
              <label class="required">
                <input
                  type="checkbox"
                  [checked]="b.required"
                  [disabled]="!editable()"
                  (change)="toggle(i)"
                />
                {{ 'admin.modules.elements.required' | transloco }}
              </label>
              @if (editable()) {
                <button
                  type="button"
                  class="sl-button sl-button--quiet"
                  [attr.aria-pressed]="!!b.group"
                  (click)="toggleGroup(i)"
                >
                  {{
                    (b.group ? 'admin.modules.elements.ungroup' : 'admin.modules.elements.group')
                      | transloco
                  }}
                </button>
                <span class="moves">
                  <button
                    type="button"
                    class="sl-button sl-button--quiet"
                    [disabled]="i === 0"
                    [attr.aria-label]="
                      'admin.modules.elements.moveUp' | transloco: { name: nameOf(b.code) }
                    "
                    (click)="move(i, -1)"
                  >
                    ↑
                  </button>
                  <button
                    type="button"
                    class="sl-button sl-button--quiet"
                    [disabled]="last"
                    [attr.aria-label]="
                      'admin.modules.elements.moveDown' | transloco: { name: nameOf(b.code) }
                    "
                    (click)="move(i, 1)"
                  >
                    ↓
                  </button>
                  <button
                    type="button"
                    class="sl-button sl-button--quiet"
                    [attr.aria-label]="
                      'admin.modules.elements.remove' | transloco: { name: nameOf(b.code) }
                    "
                    (click)="remove(i)"
                  >
                    ×
                  </button>
                </span>
              }
            </li>
            @if (b.group; as g) {
              <li class="group">
                <label>
                  {{ 'admin.modules.elements.groupCode' | transloco }}
                  <input
                    class="sl-input"
                    [value]="g.code"
                    [disabled]="!editable()"
                    (change)="setGroupCode(i, $any($event.target).value)"
                  />
                </label>
                <label>
                  {{ 'admin.modules.elements.groupLabelEn' | transloco }}
                  <input
                    class="sl-input"
                    maxlength="60"
                    [value]="g.labels.en"
                    [disabled]="!editable()"
                    (change)="setGroupLabel(g.code, 'en', $any($event.target).value)"
                  />
                </label>
                <label>
                  {{ 'admin.modules.elements.groupLabelFr' | transloco }}
                  <input
                    class="sl-input"
                    maxlength="60"
                    [value]="g.labels.fr"
                    [disabled]="!editable()"
                    (change)="setGroupLabel(g.code, 'fr', $any($event.target).value)"
                  />
                </label>
              </li>
            }
          }
        </ol>
        <p class="sl-meta">{{ 'admin.modules.elements.groupNote' | transloco }}</p>
        @if (tooMany()) {
          <p class="warning" role="status">{{ 'admin.modules.elements.tooMany' | transloco }}</p>
        }
        <p class="sl-meta">{{ 'admin.modules.elements.note' | transloco }}</p>
      </section>
    </div>

    <section class="sl-card shown" aria-labelledby="shown-title">
      <h2 id="shown-title" class="section">{{ 'admin.modules.shown.title' | transloco }}</h2>
      <p class="sl-meta">{{ 'admin.modules.shown.note' | transloco }}</p>
      <div class="pair">
        @for (f of texts; track f.key) {
          <label class="sl-field">
            <span>{{ f.label | transloco }}</span>
            <input
              class="sl-input"
              maxlength="60"
              [attr.lang]="f.lang"
              [name]="f.key"
              [disabled]="!editable()"
              [ngModel]="shown()[f.key]"
              (ngModelChange)="setShown(f.key, $event)"
            />
          </label>
        }
      </div>

      <label class="sl-field">
        <span>{{ 'admin.modules.shown.episode' | transloco }}</span>
        <select
          class="sl-input"
          name="model"
          [disabled]="!editable()"
          [ngModel]="shown().model"
          (ngModelChange)="setShown('model', $event)"
        >
          <option value="">{{ 'admin.modules.shown.noEpisode' | transloco }}</option>
          @for (m of models(); track m) {
            <option [value]="m">{{ m }}</option>
          }
        </select>
      </label>
      @if (shown().model) {
        <div class="pair">
          @for (f of episodeTexts; track f.key) {
            <label class="sl-field">
              <span>{{ f.label | transloco }}</span>
              <input
                class="sl-input"
                maxlength="60"
                [attr.lang]="f.lang"
                [name]="f.key"
                [disabled]="!editable()"
                [ngModel]="shown()[f.key]"
                (ngModelChange)="setShown(f.key, $event)"
              />
            </label>
          }
        </div>
        <p class="sl-meta">{{ 'admin.modules.shown.dayHint' | transloco: { n: placeholder } }}</p>
      }

      <fieldset class="trends">
        <legend>{{ 'admin.modules.shown.trends' | transloco }}</legend>
        @for (t of trendChoices(); track t.key) {
          <label class="check">
            <input
              type="checkbox"
              [checked]="shown().trends.includes(t.key)"
              [disabled]="
                !editable() || (!shown().trends.includes(t.key) && shown().trends.length >= maxTrends)
              "
              (change)="toggleTrend(t.key)"
            />
            {{ t.label }}
          </label>
        } @empty {
          <p class="sl-meta">{{ 'admin.modules.shown.noTrends' | transloco }}</p>
        }
      </fieldset>
      <p class="sl-meta">{{ 'admin.modules.shown.trendsHint' | transloco }}</p>

      <label class="sl-field">
        <span>{{ 'admin.modules.shown.quickLog' | transloco }}</span>
        <select
          class="sl-input"
          name="quickLog"
          [disabled]="!editable()"
          [ngModel]="shown().quickLog"
          (ngModelChange)="setShown('quickLog', $event)"
        >
          <option value="">{{ 'admin.modules.shown.noQuickLog' | transloco }}</option>
          @for (b of quickLogChoices(); track b.code) {
            <option [value]="b.code">{{ nameOf(b.code) }}</option>
          }
        </select>
      </label>
    </section>

    @if (shownRefused()) {
      <p class="error" role="alert">
        {{ 'admin.modules.shown.refused' | transloco: { n: placeholder } }}
      </p>
    }
    @if (failed()) {
      <p class="error" role="alert">{{ 'common.checkConnection' | transloco }}</p>
    }
    <div class="actions">
      <button type="button" class="sl-button" (click)="back.emit()">
        {{ 'admin.modules.elements.back' | transloco }}
      </button>
      <button
        type="button"
        class="sl-button sl-button--primary"
        [disabled]="busy() || (editable() && chosen().length === 0)"
        (click)="saveAndNext()"
      >
        {{ 'admin.modules.elements.next' | transloco }}
      </button>
    </div>
  `,
  styles: `
    .layout {
      display: grid;
      grid-template-columns: 1fr 1fr;
      gap: var(--space-20);
      align-items: start;
    }
    .section {
      margin: 0 0 var(--space-4);
      font-size: 1rem;
    }
    .list {
      list-style: none;
      padding: 0;
      margin: var(--space-12) 0;
    }
    .row {
      display: flex;
      align-items: center;
      justify-content: space-between;
      gap: var(--space-12);
      padding: var(--space-8) 0;
      border-bottom: 1px solid var(--color-line, #eef1ec);
    }
    .name {
      margin: 0;
      font-weight: 600;
    }
    .row .sl-meta {
      margin: 0;
    }
    .required {
      display: inline-flex;
      align-items: center;
      gap: var(--space-4);
      min-height: 44px;
      margin-left: auto;
    }
    .group {
      display: grid;
      grid-template-columns: 1fr 1fr 1fr;
      gap: var(--space-8);
      padding: var(--space-8) 0 var(--space-12);
      border-bottom: 1px solid var(--color-line, #eef1ec);
    }
    .group label {
      display: grid;
      gap: var(--space-4);
      font-size: 0.875rem;
    }
    .moves {
      display: inline-flex;
      gap: var(--space-4);
    }
    .moves button {
      min-width: 44px;
    }
    .warning {
      color: var(--warning-ink, inherit);
      font-weight: 600;
    }
    .shown {
      margin-top: var(--space-20);
      display: grid;
      gap: var(--space-12);
    }
    .pair {
      display: grid;
      grid-template-columns: 1fr 1fr;
      gap: var(--space-12);
    }
    .trends {
      border: 0;
      padding: 0;
      margin: 0;
      display: flex;
      flex-wrap: wrap;
      gap: var(--space-12);
    }
    .check {
      display: inline-flex;
      align-items: center;
      gap: var(--space-8);
      min-height: 44px;
    }
    .actions {
      display: flex;
      justify-content: end;
      gap: var(--space-8);
      margin-top: var(--space-16);
    }
    .error {
      color: var(--danger);
    }
  `,
})
export class ElementsStep {
  private readonly api = inject(TrackingApi);
  private readonly transloco = inject(TranslocoService);

  readonly version = input.required<Version>();
  readonly library = input<Element[]>([]);
  readonly editable = input(false);
  readonly saved = output<Version>();
  readonly back = output<void>();
  readonly next = output<void>();

  readonly chosen = signal<Binding[]>([]);
  readonly shown = signal<Shown>(shownOf(undefined));
  readonly vocabulary = signal<Vocabulary | null>(null);
  readonly busy = signal(false);
  readonly failed = signal(false);
  readonly shownRefused = signal(false);
  readonly maxTrends = MAX_TRENDS;
  readonly placeholder = '{{n}}';
  readonly texts = [
    { key: 'titleEn', lang: 'en', label: 'admin.modules.shown.titleEn' },
    { key: 'titleFr', lang: 'fr', label: 'admin.modules.shown.titleFr' },
    { key: 'saveEn', lang: 'en', label: 'admin.modules.shown.saveEn' },
    { key: 'saveFr', lang: 'fr', label: 'admin.modules.shown.saveFr' },
  ] as const;
  readonly episodeTexts = [
    { key: 'dayEn', lang: 'en', label: 'admin.modules.shown.dayEn' },
    { key: 'dayFr', lang: 'fr', label: 'admin.modules.shown.dayFr' },
    { key: 'historyEn', lang: 'en', label: 'admin.modules.shown.historyEn' },
    { key: 'historyFr', lang: 'fr', label: 'admin.modules.shown.historyFr' },
  ] as const;

  /** Episode models this version's saved elements give, from the vocabulary's episode signals. */
  readonly models = computed(() => {
    const out = new Set<string>();
    for (const s of this.vocabulary()?.signals ?? []) {
      if (s.kind === 'EPISODE') out.add(s.signal.split('.')[0]!);
    }
    const current = this.shown().model;
    if (current) out.add(current);
    return [...out];
  });

  /** What Trends may chart: the signals the saved elements give, labelled as the rules step does. */
  readonly trendChoices = computed(() => {
    this.lang();
    const offered = (this.vocabulary()?.signals ?? []).map((s) => ({
      key: s.key,
      label:
        s.kind === 'DAILY'
          ? this.nameOf(s.element ?? s.key)
          : ((this.lang() === 'fr' ? s.labels?.fr : s.labels?.en) ?? s.signal),
    }));
    for (const key of this.shown().trends) {
      if (!offered.some((o) => o.key === key)) offered.push({ key, label: key });
    }
    return offered;
  });

  /** One tap on Home suits an element with a short answer, not a date or free text. */
  readonly quickLogChoices = computed(() =>
    this.chosen().filter((b) => {
      const e = this.library().find((x) => x.code === b.code);
      return e && e.valueType !== 'TEXT' && e.valueType !== 'DATE';
    }),
  );
  private readonly lang = signal(this.transloco.getActiveLang());
  readonly tooMany = computed(() => this.chosen().length > ELEMENT_WARNING);

  constructor() {
    this.transloco.langChanges$.subscribe((lang) => this.lang.set(lang));
    effect(() => {
      const v = this.version();
      untracked(() => {
        this.chosen.set(
          [...(v.elements ?? [])].sort((a, b) => (a.position ?? 0) - (b.position ?? 0)),
        );
        this.shown.set(shownOf(v.presentation));
        if (v.id) void this.loadVocabulary(v.id);
      });
    });
  }

  /** The vocabulary follows the saved elements, so it is read again with each version. */
  async loadVocabulary(versionId: string) {
    try {
      this.vocabulary.set(
        orThrow(
          await this.api.client.GET('/admin/v1/tracking/versions/{versionId}/rule-vocabulary', {
            params: { path: { versionId } },
          }),
        ),
      );
    } catch {
      this.failed.set(true);
    }
  }

  setShown<K extends keyof Shown>(key: K, value: Shown[K]) {
    this.shownRefused.set(false);
    this.shown.update((f) => ({ ...f, [key]: value }));
  }

  toggleTrend(key: string) {
    const now = this.shown().trends;
    if (now.includes(key)) {
      this.setShown(
        'trends',
        now.filter((k) => k !== key),
      );
    } else if (now.length < MAX_TRENDS) {
      this.setShown('trends', [...now, key]);
    }
  }

  /**
   * The presentation to save. A text left empty in both languages is left to the app; one filled
   * in only one language is sent, so Tracking refuses it rather than showing half of it. A daily
   * trend or quick-log element no longer in the module is dropped.
   */
  presentation(): Presentation {
    const f = this.shown();
    const codes = new Set(this.chosen().map((b) => b.code));
    const text = (en: string, fr: string) =>
      en.trim() || fr.trim() ? { en: en.trim(), fr: fr.trim() } : undefined;
    const title = text(f.titleEn, f.titleFr);
    const saveLabel = text(f.saveEn, f.saveFr);
    const historyTitle = text(f.historyEn, f.historyFr);
    const trends = f.trends.filter(
      (k) => !k.startsWith('daily_value:') || codes.has(k.slice('daily_value:'.length)),
    );
    return {
      ...(title ? { title } : {}),
      ...(saveLabel ? { saveLabel } : {}),
      ...(f.model
        ? {
            episode: {
              model: f.model,
              dayLabel: { en: f.dayEn.trim(), fr: f.dayFr.trim() },
              ...(historyTitle ? { historyTitle } : {}),
            },
          }
        : {}),
      ...(trends.length ? { trends } : {}),
      ...(f.quickLog && codes.has(f.quickLog) ? { quickLog: f.quickLog } : {}),
    };
  }

  label(e: Element): string {
    const labels = e.labels as Record<string, { name?: string } | undefined>;
    return labels[this.lang()]?.name ?? labels['en']?.name ?? e.code;
  }

  nameOf(code: string): string {
    const e = this.library().find((x) => x.code === code);
    return e ? this.label(e) : code;
  }

  has(e: Element): boolean {
    return this.chosen().some((b) => b.code === e.code);
  }

  add(e: Element) {
    this.chosen.update((list) => [...list, { code: e.code, version: e.version, required: true }]);
  }

  remove(i: number) {
    this.chosen.update((list) => list.filter((_, j) => j !== i));
  }

  toggle(i: number) {
    this.chosen.update((list) =>
      list.map((b, j) => (j === i ? { ...b, required: !b.required } : b)),
    );
  }

  /**
   * Puts a binding in a group or takes it out. A new group joins the one just above when there is
   * one, so elements shown together share the same code and label.
   */
  toggleGroup(i: number) {
    this.chosen.update((list) =>
      list.map((b, j) => {
        if (j !== i) return b;
        if (b.group) {
          const { group: _, ...rest } = b;
          return rest;
        }
        const above = list[i - 1]?.group;
        const group: Group = above
          ? { code: above.code, labels: { ...above.labels } }
          : { code: b.code, labels: { en: '', fr: '' } };
        return { ...b, group };
      }),
    );
  }

  setGroupCode(i: number, code: string) {
    const clean = code.trim().toLowerCase();
    this.chosen.update((list) =>
      list.map((b, j) => (j === i && b.group ? { ...b, group: { ...b.group, code: clean } } : b)),
    );
  }

  /** A group has one label, so changing it changes it for every element in the group. */
  setGroupLabel(code: string, lang: 'en' | 'fr', text: string) {
    this.chosen.update((list) =>
      list.map((b) =>
        b.group?.code === code
          ? { ...b, group: { ...b.group, labels: { ...b.group.labels, [lang]: text.trim() } } }
          : b,
      ),
    );
  }

  move(i: number, by: -1 | 1) {
    this.chosen.update((list) => {
      const copy = [...list];
      const [item] = copy.splice(i, 1);
      copy.splice(i + by, 0, item!);
      return copy;
    });
  }

  async saveAndNext() {
    if (!this.editable()) {
      this.next.emit();
      return;
    }
    this.busy.set(true);
    this.failed.set(false);
    try {
      const v = orThrow(
        await this.api.client.PUT('/admin/v1/tracking/versions/{versionId}/elements', {
          params: { path: { versionId: this.version().id! } },
          body: this.chosen().map((b) => ({
            code: b.code,
            version: b.version,
            required: b.required,
            ...(b.group ? { group: b.group } : {}),
          })),
        }),
      );
      const shown = this.presentation();
      if (JSON.stringify(shown) === JSON.stringify(v.presentation ?? {})) {
        this.saved.emit(v);
        this.next.emit();
        return;
      }
      const res = await this.api.client.PUT('/admin/v1/tracking/versions/{versionId}/presentation', {
        params: { path: { versionId: this.version().id! } },
        body: shown,
      });
      if (res.response.status === 400) {
        // The elements are saved; the presentation stays in the form to be fixed.
        this.saved.emit(v);
        this.shownRefused.set(true);
        return;
      }
      this.saved.emit(orThrow(res));
      this.next.emit();
    } catch {
      this.failed.set(true);
    } finally {
      this.busy.set(false);
    }
  }
}
