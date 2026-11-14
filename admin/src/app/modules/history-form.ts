import {
  ChangeDetectionStrategy,
  Component,
  computed,
  inject,
  input,
  output,
  signal,
} from '@angular/core';
import { FormsModule } from '@angular/forms';
import { TranslocoPipe, TranslocoService } from '@jsverse/transloco';

import { ApiProblem, orThrow } from '../api/core-api';
import { TrackingApi, type TrackingSchemas } from '../api/tracking-api';

type Element = TrackingSchemas['Element'];
type ContextQuestion = TrackingSchemas['ContextQuestion'];
type HistoryPart = TrackingSchemas['HistoryPart'];
type Pattern = HistoryPart['pattern'];

export const PATTERNS: Pattern[] = ['constant', 'alternating', 'sparse', 'episodes'];

/** One part of the recipe as the form holds it; numbers and lists are typed as text. */
interface Part {
  pattern: Pattern;
  element: string;
  days: string;
  value: string;
  values: string;
  every: string;
  start: string;
  lengths: string;
  currentDay: string;
  activeValue: string;
  activeDays: string;
  afterValue: string;
  afterDays: string;
}

function blankPart(): Part {
  return {
    pattern: 'constant',
    element: '',
    days: '14',
    value: '',
    values: '',
    every: '3',
    start: '',
    lengths: '',
    currentDay: '1',
    activeValue: '',
    activeDays: '5',
    afterValue: '',
    afterDays: '',
  };
}

const list = (text: string) =>
  text
    .split(',')
    .map((x) => x.trim())
    .filter(Boolean);

/**
 * Adds a made-up history next to the rules being written or reviewed (Tracking Service Spec,
 * Testing before publishing; D61): a recipe of parts built back from today, and the context
 * answers "unless" reads. Tracking checks every value against its element; a history is never
 * edited once added. No screen design exists yet, so the layout follows the rules form.
 */
@Component({
  selector: 'sl-history-form',
  imports: [TranslocoPipe, FormsModule],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <form
      class="history"
      (ngSubmit)="add()"
      [attr.aria-label]="'admin.modules.history.title' | transloco"
    >
      <h4 class="sub">{{ 'admin.modules.history.title' | transloco }}</h4>
      <p class="sl-meta">{{ 'admin.modules.history.note' | transloco }}</p>
      <label class="sl-field">
        <span>{{ 'admin.modules.history.code' | transloco }}</span>
        <input class="sl-input" name="code" maxlength="60" [(ngModel)]="code" />
      </label>
      <label class="sl-field">
        <span>{{ 'admin.modules.history.nameEn' | transloco }}</span>
        <input class="sl-input" name="nameEn" lang="en" maxlength="80" [(ngModel)]="nameEn" />
      </label>
      <label class="sl-field">
        <span>{{ 'admin.modules.history.nameFr' | transloco }}</span>
        <input class="sl-input" name="nameFr" lang="fr" maxlength="80" [(ngModel)]="nameFr" />
      </label>

      @for (p of parts(); track $index; let i = $index) {
        <fieldset class="part">
          <legend>{{ 'admin.modules.history.part' | transloco: { n: i + 1 } }}</legend>
          <label class="sl-field">
            <span>{{ 'admin.modules.history.pattern' | transloco }}</span>
            <select
              class="sl-input"
              [name]="'pattern' + i"
              [ngModel]="p.pattern"
              (ngModelChange)="set(i, 'pattern', $event)"
            >
              @for (k of patterns; track k) {
                <option [value]="k">{{ 'admin.modules.history.patterns.' + k | transloco }}</option>
              }
            </select>
          </label>
          @if (p.pattern === 'episodes') {
            <label class="sl-field">
              <span>{{ 'admin.modules.history.start' | transloco }}</span>
              <select
                class="sl-input"
                [name]="'start' + i"
                [ngModel]="p.start"
                (ngModelChange)="set(i, 'start', $event)"
              >
                <option value=""></option>
                @for (e of events(); track e.code) {
                  <option [value]="e.code">{{ nameOf(e) }}</option>
                }
              </select>
            </label>
            <label class="sl-field">
              <span>{{ 'admin.modules.history.lengths' | transloco }}</span>
              <input
                class="sl-input"
                [name]="'lengths' + i"
                inputmode="numeric"
                [ngModel]="p.lengths"
                (ngModelChange)="set(i, 'lengths', $event)"
              />
            </label>
            <label class="sl-field">
              <span>{{ 'admin.modules.history.currentDay' | transloco }}</span>
              <input
                class="sl-input"
                type="number"
                min="1"
                max="180"
                [name]="'currentDay' + i"
                [ngModel]="p.currentDay"
                (ngModelChange)="set(i, 'currentDay', $event)"
              />
            </label>
          }
          <label class="sl-field">
            <span>{{
              (p.pattern === 'episodes'
                ? 'admin.modules.history.activity'
                : 'admin.modules.history.element'
              ) | transloco
            }}</span>
            <select
              class="sl-input"
              [name]="'element' + i"
              [ngModel]="p.element"
              (ngModelChange)="set(i, 'element', $event)"
            >
              <option value=""></option>
              @for (e of dayStates(); track e.code) {
                <option [value]="e.code">{{ nameOf(e) }}</option>
              }
            </select>
          </label>
          @if (p.pattern === 'episodes') {
            @if (p.element) {
              <div class="pair">
                <label class="sl-field">
                  <span>{{ 'admin.modules.history.activeValue' | transloco }}</span>
                  <input
                    class="sl-input"
                    [name]="'activeValue' + i"
                    [ngModel]="p.activeValue"
                    (ngModelChange)="set(i, 'activeValue', $event)"
                  />
                </label>
                <label class="sl-field">
                  <span>{{ 'admin.modules.history.activeDays' | transloco }}</span>
                  <input
                    class="sl-input"
                    type="number"
                    min="1"
                    max="60"
                    [name]="'activeDays' + i"
                    [ngModel]="p.activeDays"
                    (ngModelChange)="set(i, 'activeDays', $event)"
                  />
                </label>
                <label class="sl-field">
                  <span>{{ 'admin.modules.history.afterValue' | transloco }}</span>
                  <input
                    class="sl-input"
                    [name]="'afterValue' + i"
                    [ngModel]="p.afterValue"
                    (ngModelChange)="set(i, 'afterValue', $event)"
                  />
                </label>
                <label class="sl-field">
                  <span>{{ 'admin.modules.history.afterDays' | transloco }}</span>
                  <input
                    class="sl-input"
                    type="number"
                    min="0"
                    max="60"
                    [name]="'afterDays' + i"
                    [ngModel]="p.afterDays"
                    (ngModelChange)="set(i, 'afterDays', $event)"
                  />
                </label>
              </div>
            }
          } @else {
            <label class="sl-field">
              <span>{{ 'admin.modules.history.days' | transloco }}</span>
              <input
                class="sl-input"
                type="number"
                min="1"
                max="366"
                [name]="'days' + i"
                [ngModel]="p.days"
                (ngModelChange)="set(i, 'days', $event)"
              />
            </label>
            @if (p.pattern === 'alternating') {
              <label class="sl-field">
                <span>{{ 'admin.modules.history.values' | transloco }}</span>
                <input
                  class="sl-input"
                  [name]="'values' + i"
                  [ngModel]="p.values"
                  (ngModelChange)="set(i, 'values', $event)"
                />
              </label>
            } @else {
              <label class="sl-field">
                <span>{{ 'admin.modules.history.value' | transloco }}</span>
                <input
                  class="sl-input"
                  [name]="'value' + i"
                  [ngModel]="p.value"
                  (ngModelChange)="set(i, 'value', $event)"
                />
              </label>
            }
            @if (p.pattern === 'sparse') {
              <label class="sl-field">
                <span>{{ 'admin.modules.history.every' | transloco }}</span>
                <input
                  class="sl-input"
                  type="number"
                  min="2"
                  max="30"
                  [name]="'every' + i"
                  [ngModel]="p.every"
                  (ngModelChange)="set(i, 'every', $event)"
                />
              </label>
            }
          }
          @if (p.element; as code) {
            @if (choices(code); as values) {
              @if (values.length) {
                <p class="sl-meta">
                  {{ 'admin.modules.history.choices' | transloco: { values: values.join(', ') } }}
                </p>
              }
            }
          }
          @if (parts().length > 1) {
            <button
              type="button"
              class="sl-button sl-button--quiet"
              [attr.aria-label]="'admin.modules.history.removePart' | transloco: { n: i + 1 }"
              (click)="removePart(i)"
            >
              {{ 'admin.modules.history.remove' | transloco }}
            </button>
          }
        </fieldset>
      }
      @if (parts().length < maxParts) {
        <button type="button" class="sl-button sl-button--quiet" (click)="addPart()">
          {{ 'admin.modules.history.addPart' | transloco }}
        </button>
      }

      @if (yesNo().length) {
        <fieldset class="part">
          <legend>{{ 'admin.modules.history.answers' | transloco }}</legend>
          @for (q of yesNo(); track q.code) {
            <label class="sl-field">
              <span>{{ questionText(q) }}</span>
              <select
                class="sl-input"
                [name]="'answer-' + q.code"
                [ngModel]="answers()[q.code] ?? ''"
                (ngModelChange)="answer(q.code, $event)"
              >
                <option value="">{{ 'admin.modules.history.notAnswered' | transloco }}</option>
                <option value="true">{{ 'admin.modules.rules.yes' | transloco }}</option>
                <option value="false">{{ 'admin.modules.rules.no' | transloco }}</option>
              </select>
            </label>
          }
        </fieldset>
      }

      @if (error(); as e) {
        <p class="error" role="alert">{{ e | transloco }}</p>
      }
      <div class="actions">
        <button type="button" class="sl-button" (click)="cancelled.emit()">
          {{ 'admin.modules.history.cancel' | transloco }}
        </button>
        <button type="submit" class="sl-button sl-button--primary" [disabled]="busy()">
          {{ 'admin.modules.history.add' | transloco }}
        </button>
      </div>
    </form>
  `,
  styles: `
    .history {
      display: grid;
      gap: var(--space-8);
      margin-top: var(--space-16);
      padding-top: var(--space-12);
      border-top: 1px solid var(--color-line, #eef1ec);
    }
    .sub {
      margin: 0;
      font-size: 0.95rem;
    }
    .part {
      border: 1px solid var(--color-line, #eef1ec);
      border-radius: var(--radius-md, 8px);
      padding: var(--space-8) var(--space-12);
      display: grid;
      gap: var(--space-8);
    }
    .pair {
      display: grid;
      grid-template-columns: 1fr 1fr;
      gap: var(--space-8);
    }
    .actions {
      display: flex;
      justify-content: end;
      gap: var(--space-8);
    }
    .error {
      color: var(--danger);
    }
  `,
})
export class HistoryForm {
  private readonly api = inject(TrackingApi);
  private readonly transloco = inject(TranslocoService);

  readonly library = input<Element[]>([]);
  readonly questions = input<ContextQuestion[]>([]);
  readonly added = output<void>();
  readonly cancelled = output<void>();

  readonly patterns = PATTERNS;
  readonly maxParts = 8;
  code = '';
  nameEn = '';
  nameFr = '';
  readonly parts = signal<Part[]>([blankPart()]);
  readonly answers = signal<Record<string, string>>({});
  readonly busy = signal(false);
  readonly error = signal<string | null>(null);

  /** Elements a rule can read day by day: not notes, not dates (D61). */
  readonly dayStates = computed(() =>
    this.library().filter(
      (e) => e.kind === 'DAY_STATE' && e.valueType !== 'TEXT' && e.valueType !== 'DATE',
    ),
  );
  readonly events = computed(() => this.library().filter((e) => e.kind === 'EVENT'));
  /** "Unless" reads yes-or-no answers in the launch rules; other types come with their screens. */
  readonly yesNo = computed(() => this.questions().filter((q) => q.type === 'YES_NO'));

  nameOf(e: Element): string {
    const labels = e.labels as Record<string, { name?: string } | undefined>;
    const lang = this.transloco.getActiveLang() === 'fr' ? 'fr' : 'en';
    return labels[lang]?.name ?? e.code;
  }

  questionText(q: ContextQuestion): string {
    const lang = this.transloco.getActiveLang() === 'fr' ? 'fr' : 'en';
    return q.labels?.[lang]?.question ?? q.code;
  }

  /** The codes an ENUM element takes, shown so values are typed as Tracking stores them. */
  choices(code: string): string[] {
    const e = this.library().find((x) => x.code === code);
    return e?.valueType === 'ENUM' ? ((e.constraints?.values as string[] | undefined) ?? []) : [];
  }

  set<K extends keyof Part>(i: number, key: K, value: Part[K]) {
    this.error.set(null);
    this.parts.update((list) => list.map((p, j) => (j === i ? { ...p, [key]: value } : p)));
  }

  addPart() {
    this.parts.update((list) => [...list, blankPart()]);
  }

  removePart(i: number) {
    this.parts.update((list) => list.filter((_, j) => j !== i));
  }

  answer(code: string, value: string) {
    this.answers.update((a) => {
      const next = { ...a };
      if (value) next[code] = value;
      else delete next[code];
      return next;
    });
  }

  /** A typed value as its element stores it: a number, a yes or no, or a code. */
  private typed(element: string, text: string): unknown {
    const e = this.library().find((x) => x.code === element);
    const t = text.trim();
    if (e?.valueType === 'INT' || e?.valueType === 'MINUTES') return Number(t);
    if (e?.valueType === 'BOOL') return t === 'true';
    return t;
  }

  /** The recipe in Tracking's shape; Tracking checks the rest. */
  recipe(): { parts: HistoryPart[] } {
    return {
      parts: this.parts().map((p): HistoryPart => {
        if (p.pattern === 'episodes') {
          const after = Number(p.afterDays);
          return {
            pattern: 'episodes',
            start: p.start,
            lengths: list(p.lengths).map(Number),
            currentDay: Number(p.currentDay),
            ...(p.element
              ? {
                  activity: p.element,
                  active: [
                    { value: this.typed(p.element, p.activeValue), days: Number(p.activeDays) },
                  ],
                  ...(after > 0
                    ? { after: { value: this.typed(p.element, p.afterValue), days: after } }
                    : {}),
                }
              : {}),
          };
        }
        const base = { pattern: p.pattern, element: p.element, days: Number(p.days) };
        if (p.pattern === 'alternating') {
          return { ...base, values: list(p.values).map((v) => this.typed(p.element, v)) };
        }
        if (p.pattern === 'sparse') {
          return { ...base, value: this.typed(p.element, p.value), every: Number(p.every) };
        }
        return { ...base, value: this.typed(p.element, p.value) };
      }),
    };
  }

  async add() {
    this.busy.set(true);
    this.error.set(null);
    const context = Object.fromEntries(
      Object.entries(this.answers()).map(([k, v]) => [k, v === 'true']),
    );
    try {
      orThrow(
        await this.api.client.POST('/admin/v1/tracking/synthetic-histories', {
          body: {
            code: this.code.trim(),
            labels: { en: this.nameEn.trim(), fr: this.nameFr.trim() },
            recipe: this.recipe(),
            context,
          },
        }),
      );
      this.added.emit();
    } catch (e) {
      const status = e instanceof ApiProblem ? e.status : 0;
      this.error.set(
        status === 409
          ? 'admin.modules.history.codeTaken'
          : status === 400
            ? 'admin.modules.history.refused'
            : status === 403
              ? 'admin.modules.history.notAllowed'
              : 'common.checkConnection',
      );
    } finally {
      this.busy.set(false);
    }
  }
}
