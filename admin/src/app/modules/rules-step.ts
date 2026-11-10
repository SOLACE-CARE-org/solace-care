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
import type { Specialty } from './module-builder.page';

type Version = TrackingSchemas['ModuleVersion'];
type Element = TrackingSchemas['Element'];
type Rule = TrackingSchemas['Rule'];
type Condition = TrackingSchemas['Condition'];
type TestResult = TrackingSchemas['TestResult'];
type Vocabulary = TrackingSchemas['RuleVocabulary'];
type VocabularySignal = Vocabulary['signals'][number];

const MODES = ['at_least', 'at_most'] as const;
type Field = 'count' | 'mode' | 'window' | 'minData';

/**
 * A signal choice from the version's rule vocabulary (D61): a daily element value, or a cycle
 * signal. The vocabulary also says which operators and comparisons each one allows.
 */
interface SignalChoice {
  key: string;
  signal: Condition['signal'];
  element?: string;
  kind: VocabularySignal['kind'];
  label: string;
}

function blankRule(): Rule {
  return {
    code: '',
    conditions: [blankCondition()],
    unless: [],
    outcome: 'PATTERN',
    specialtyCodes: [],
    messageEn: '',
    messageFr: '',
    cooldownDays: 30,
  };
}

function blankCondition(): Condition {
  return {
    signal: 'daily_value',
    operator: 'days_in_window',
    comparison: 'eq',
    threshold: null,
    mode: 'at_least',
    count: 1,
    window: 14,
    minData: 7,
  };
}

/**
 * Builder step 3 (admin screen 24): rules in the spec's fixed form, a test run on made-up
 * histories, and submission for clinical review.
 */
@Component({
  selector: 'sl-rules-step',
  imports: [TranslocoPipe, FormsModule],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="sl-tabs" role="tablist">
      @for (r of rules(); track r.id) {
        <button
          role="tab"
          type="button"
          [attr.aria-selected]="r.id === selectedId()"
          (click)="select(r)"
        >
          {{ r.code }}
          @if (r.problems?.length) {
            <span
              class="dot"
              role="img"
              [attr.aria-label]="'admin.modules.rules.needsFixing' | transloco"
            ></span>
          }
        </button>
      }
      @if (editable()) {
        <button
          role="tab"
          type="button"
          [attr.aria-selected]="selectedId() === 'new'"
          (click)="startNew()"
        >
          {{ 'admin.modules.rules.add' | transloco }}
        </button>
      }
    </div>

    @if (rules().length === 0 && selectedId() !== 'new') {
      <p class="sl-card sl-muted">{{ 'admin.modules.rules.none' | transloco }}</p>
    }

    @if (draft(); as r) {
      <div class="layout">
        <section class="sl-card">
          <label class="sl-field">
            <span>{{ 'admin.modules.rules.code' | transloco }}</span>
            <input
              class="sl-input"
              name="code"
              maxlength="80"
              pattern="[a-z][a-z0-9_]*\\.[a-z][a-z0-9_]*"
              [disabled]="!editable()"
              [ngModel]="r.code"
              (ngModelChange)="patch({ code: $event })"
            />
            <span class="sl-meta">{{ 'admin.modules.rules.codeHint' | transloco }}</span>
          </label>
          @if (selected()?.problems?.length) {
            <p class="error" role="status">{{ 'admin.modules.rules.needsFixing' | transloco }}</p>
          }

          <h3 class="sub">{{ 'admin.modules.rules.conditions' | transloco }}</h3>
          @for (c of r.conditions; track $index; let i = $index) {
            <fieldset class="condition">
              <legend class="visually-hidden">{{ i + 1 }}</legend>
              <label class="sl-field">
                <span>{{ 'admin.modules.rules.signal' | transloco }}</span>
                <select
                  class="sl-select"
                  [disabled]="!editable()"
                  [ngModel]="signalKey(c)"
                  (ngModelChange)="setSignal(i, $event)"
                >
                  @for (s of signalChoices(); track s.key) {
                    <option [value]="s.key">{{ s.label }}</option>
                  }
                </select>
              </label>
              <label class="sl-field">
                <span>{{ 'admin.modules.rules.operator' | transloco }}</span>
                <select
                  class="sl-select"
                  [disabled]="!editable()"
                  [ngModel]="c.operator"
                  (ngModelChange)="setCondition(i, { operator: $event })"
                >
                  @for (o of operatorsFor(c); track o) {
                    <option [value]="o">
                      {{ 'admin.modules.rules.operators.' + o | transloco }}
                    </option>
                  }
                </select>
              </label>
              <label class="sl-field">
                <span>{{ 'admin.modules.rules.comparison' | transloco }}</span>
                <select
                  class="sl-select"
                  [disabled]="!editable()"
                  [ngModel]="c.comparison"
                  (ngModelChange)="setComparison(i, $event)"
                >
                  @for (o of comparisonsFor(c); track o) {
                    <option [value]="o">
                      {{ 'admin.modules.rules.comparisons.' + o | transloco }}
                    </option>
                  }
                </select>
              </label>
              @if (valuesOf(c); as values) {
                @if (c.comparison === 'in') {
                  <fieldset class="values">
                    <legend>{{ 'admin.modules.rules.value' | transloco }}</legend>
                    @for (v of values; track v.code) {
                      <label class="check">
                        <input
                          type="checkbox"
                          [disabled]="!editable()"
                          [checked]="inThreshold(c, v.code)"
                          (change)="toggleValue(i, v.code)"
                        />
                        {{ v.label }}
                      </label>
                    }
                  </fieldset>
                } @else {
                  <label class="sl-field">
                    <span>{{ 'admin.modules.rules.value' | transloco }}</span>
                    <select
                      class="sl-select"
                      [disabled]="!editable()"
                      [ngModel]="c.threshold"
                      (ngModelChange)="setCondition(i, { threshold: $event })"
                    >
                      @for (v of values; track v.code) {
                        <option [value]="v.code">{{ v.label }}</option>
                      }
                    </select>
                  </label>
                }
              } @else {
                <label class="sl-field">
                  <span>{{ 'admin.modules.rules.value' | transloco }}</span>
                  <input
                    class="sl-input"
                    type="number"
                    [disabled]="!editable()"
                    [ngModel]="c.threshold"
                    (ngModelChange)="setCondition(i, { threshold: num($event) })"
                  />
                </label>
              }
              @if (hasField(c, 'mode')) {
                <label class="sl-field">
                  <span>{{ 'admin.modules.rules.mode' | transloco }}</span>
                  <select
                    class="sl-select"
                    [disabled]="!editable()"
                    [ngModel]="c.mode ?? 'at_least'"
                    (ngModelChange)="setCondition(i, { mode: $event })"
                  >
                    @for (m of modes; track m) {
                      <option [value]="m">
                        {{ 'admin.modules.rules.modes.' + m | transloco }}
                      </option>
                    }
                  </select>
                </label>
              }
              @if (hasField(c, 'count')) {
                <label class="sl-field small">
                  <span>{{ 'admin.modules.rules.count' | transloco }}</span>
                  <input
                    class="sl-input"
                    type="number"
                    min="0"
                    [disabled]="!editable()"
                    [ngModel]="c.count"
                    (ngModelChange)="setCondition(i, { count: num($event) })"
                  />
                </label>
              }
              @if (hasField(c, 'window')) {
                <label class="sl-field small">
                  <span>{{ 'admin.modules.rules.window' | transloco }}</span>
                  <input
                    class="sl-input"
                    type="number"
                    min="1"
                    max="365"
                    [disabled]="!editable()"
                    [ngModel]="c.window"
                    (ngModelChange)="setCondition(i, { window: num($event) })"
                  />
                </label>
                <label class="sl-field small">
                  <span>{{ 'admin.modules.rules.minData' | transloco }}</span>
                  <input
                    class="sl-input"
                    type="number"
                    min="0"
                    [disabled]="!editable()"
                    [ngModel]="c.minData"
                    (ngModelChange)="setCondition(i, { minData: num($event) })"
                  />
                </label>
              }
              @if (editable() && r.conditions.length > 1) {
                <button
                  type="button"
                  class="sl-button sl-button--quiet"
                  (click)="removeCondition(i)"
                >
                  {{ 'admin.modules.rules.removeCondition' | transloco: { n: i + 1 } }}
                </button>
              }
            </fieldset>
          }
          @if (editable() && r.conditions.length < 3) {
            <button type="button" class="sl-button" (click)="addCondition()">
              {{ 'admin.modules.rules.addCondition' | transloco }}
            </button>
          }

          <div class="row">
            <fieldset class="values">
              <legend>{{ 'admin.modules.rules.outcome' | transloco }}</legend>
              @for (o of outcomes; track o) {
                <label class="check">
                  <input
                    type="radio"
                    name="outcome"
                    [value]="o"
                    [disabled]="!editable()"
                    [ngModel]="r.outcome"
                    (ngModelChange)="patch({ outcome: $event })"
                  />
                  {{ 'admin.modules.rules.outcomes.' + o | transloco }}
                </label>
              }
            </fieldset>
            @if (r.outcome === 'INVITE') {
              <label class="sl-field">
                <span>{{ 'admin.modules.rules.specialty' | transloco }}</span>
                <select
                  class="sl-select"
                  [disabled]="!editable()"
                  [ngModel]="r.specialtyCodes?.[0] ?? ''"
                  (ngModelChange)="patch({ specialtyCodes: $event ? [$event] : [] })"
                >
                  <option value=""></option>
                  @for (s of specialtyChoices(); track s.code) {
                    <option [value]="s.code">{{ s.label }}</option>
                  }
                </select>
              </label>
            }
            <label class="sl-field small">
              <span
                >{{ 'admin.modules.rules.cooldown' | transloco }} ({{
                  'admin.modules.rules.days' | transloco
                }})</span
              >
              <input
                class="sl-input"
                type="number"
                min="0"
                max="365"
                [disabled]="!editable()"
                [ngModel]="r.cooldownDays"
                (ngModelChange)="patch({ cooldownDays: num($event) ?? 0 })"
              />
            </label>
            @if (r.outcome === 'PATTERN') {
              <label class="sl-field small">
                <span
                  >{{ 'admin.modules.rules.repeatWithin' | transloco }} ({{
                    'admin.modules.rules.days' | transloco
                  }})</span
                >
                <input
                  class="sl-input"
                  type="number"
                  min="1"
                  max="365"
                  [disabled]="!editable()"
                  [ngModel]="r.repeatWithinDays"
                  (ngModelChange)="patch({ repeatWithinDays: num($event) })"
                />
              </label>
            }
          </div>

          <label class="sl-field">
            <span>{{ 'admin.modules.rules.messageEn' | transloco }}</span>
            <textarea
              class="sl-textarea"
              rows="2"
              maxlength="300"
              lang="en"
              [disabled]="!editable()"
              [ngModel]="r.messageEn"
              (ngModelChange)="patch({ messageEn: $event })"
            ></textarea>
          </label>
          <label class="sl-field">
            <span>{{ 'admin.modules.rules.messageFr' | transloco }}</span>
            <textarea
              class="sl-textarea"
              rows="2"
              maxlength="300"
              lang="fr"
              [disabled]="!editable()"
              [ngModel]="r.messageFr"
              (ngModelChange)="patch({ messageFr: $event })"
            ></textarea>
          </label>
          <p class="sl-meta">{{ 'admin.modules.rules.placeholders' | transloco }}</p>
          <p class="sl-meta">
            {{ 'admin.modules.rules.blockedNote' | transloco: { unless: unlessText(r) } }}
          </p>

          @if (ruleError()) {
            <p class="error" role="alert">{{ ruleError()! }}</p>
          }
          @if (editable()) {
            <div class="actions">
              @if (selected()) {
                <button
                  type="button"
                  class="sl-button sl-button--danger"
                  [disabled]="busy()"
                  (click)="remove()"
                >
                  {{ 'admin.modules.rules.delete' | transloco }}
                </button>
              }
              <button
                type="button"
                class="sl-button sl-button--primary"
                [disabled]="busy() || !r.code"
                (click)="save()"
              >
                {{ 'admin.modules.rules.save' | transloco }}
              </button>
            </div>
          }
        </section>

        <aside class="sl-card" aria-labelledby="test-title" aria-live="polite">
          <h3 id="test-title" class="sub">{{ 'admin.modules.rules.testRun' | transloco }}</h3>
          @if (selected(); as s) {
            <button type="button" class="sl-button" [disabled]="testing()" (click)="runTests()">
              {{ 'admin.modules.rules.runAgain' | transloco }}
            </button>
            @if (resultsFor(s.code); as results) {
              @if (results.length) {
                <p>
                  {{
                    'admin.modules.rules.firesOn'
                      | transloco: { n: firing(results), total: results.length }
                  }}
                </p>
                <ul class="results">
                  @for (t of results; track t.history) {
                    <li>
                      <span>{{ 'admin.modules.rules.history.' + t.history | transloco }}</span>
                      <span class="sl-pill" [class.sl-pill--brand]="t.fires">
                        {{
                          (t.blockedByUnless
                            ? 'admin.modules.rules.switchedOff'
                            : t.fires
                              ? 'admin.modules.rules.fires'
                              : 'admin.modules.rules.doesNotFire'
                          ) | transloco
                        }}
                      </span>
                    </li>
                  }
                </ul>
              }
            }
          }
        </aside>
      </div>
    }

    @if (problems().length && editable()) {
      <section class="sl-card problems" aria-labelledby="problems-title">
        <h3 id="problems-title" class="sub">{{ 'admin.modules.problems.title' | transloco }}</h3>
        <ul>
          @for (p of problems(); track p) {
            <li>{{ problemText(p) }}</li>
          }
        </ul>
      </section>
    }
    @if (failed()) {
      <p class="error" role="alert">{{ 'common.checkConnection' | transloco }}</p>
    }
    <div class="actions">
      <button type="button" class="sl-button" (click)="back.emit()">
        {{ 'admin.modules.rules.back' | transloco }}
      </button>
      @if (editable()) {
        <button
          type="button"
          class="sl-button sl-button--primary"
          [disabled]="busy() || problems().length > 0"
          (click)="submit()"
        >
          {{ 'admin.modules.rules.submit' | transloco }}
        </button>
      }
    </div>
  `,
  styles: `
    .layout {
      display: grid;
      grid-template-columns: minmax(0, 1fr) 20rem;
      gap: var(--space-20);
      align-items: start;
      margin-top: var(--space-16);
    }
    .sub {
      font-size: 1rem;
      margin: var(--space-16) 0 var(--space-8);
    }
    .condition,
    .values {
      border: 0;
      padding: 0;
      margin: 0 0 var(--space-12);
      display: flex;
      flex-wrap: wrap;
      align-items: end;
      gap: var(--space-12);
    }
    .condition {
      padding-bottom: var(--space-12);
      border-bottom: 1px solid var(--color-line, #eef1ec);
    }
    .row {
      display: flex;
      flex-wrap: wrap;
      align-items: end;
      gap: var(--space-16);
      margin: var(--space-16) 0;
    }
    .small {
      max-width: 9rem;
    }
    .check {
      display: inline-flex;
      align-items: center;
      gap: var(--space-4);
      min-height: 44px;
    }
    .results {
      list-style: none;
      padding: 0;
      margin: var(--space-12) 0 0;
    }
    .results li {
      display: flex;
      justify-content: space-between;
      gap: var(--space-8);
      padding: var(--space-8) 0;
      border-bottom: 1px solid var(--color-line, #eef1ec);
    }
    .problems {
      margin-top: var(--space-16);
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
    .dot {
      display: inline-block;
      width: 0.5rem;
      height: 0.5rem;
      border-radius: 50%;
      background: var(--danger);
      margin-left: var(--space-4);
    }
    .visually-hidden {
      position: absolute;
      width: 1px;
      height: 1px;
      overflow: hidden;
      clip: rect(0 0 0 0);
    }
  `,
})
export class RulesStep {
  private readonly api = inject(TrackingApi);
  private readonly transloco = inject(TranslocoService);

  readonly version = input.required<Version>();
  readonly library = input<Element[]>([]);
  readonly specialties = input<Specialty[]>([]);
  readonly editable = input(false);
  readonly changed = output<void>();
  readonly submitted = output<Version>();
  readonly back = output<void>();

  readonly outcomes = ['INVITE', 'PATTERN'] as const;
  readonly modes = MODES;
  readonly selectedId = signal<string | null>(null);
  readonly draft = signal<Rule | null>(null);
  readonly results = signal<TestResult[]>([]);
  readonly vocabulary = signal<Vocabulary | null>(null);
  readonly busy = signal(false);
  readonly testing = signal(false);
  readonly failed = signal(false);
  readonly ruleError = signal<string | null>(null);
  private readonly lang = signal(this.transloco.getActiveLang());

  readonly rules = computed(() => this.version().rules ?? []);
  readonly selected = computed(() => this.rules().find((r) => r.id === this.selectedId()));
  readonly problems = computed(() => this.version().problems ?? []);

  /** The signals Tracking offers this version's rules, labelled in the admin's language. */
  readonly signalChoices = computed<SignalChoice[]>(() => {
    this.lang();
    return (this.vocabulary()?.signals ?? []).map((s) => {
      const e = s.element ? this.library().find((x) => x.code === s.element) : undefined;
      return {
        key: s.key,
        signal: s.signal,
        element: s.element ?? undefined,
        kind: s.kind,
        label:
          s.kind === 'DAILY'
            ? this.transloco.translate('admin.modules.rules.daily', {
                name: e ? this.nameOf(e) : s.element,
              })
            : ((this.lang() === 'fr' ? s.labels?.fr : s.labels?.en) ?? s.signal),
      };
    });
  });

  readonly specialtyChoices = computed(() => {
    const known = this.specialties().map((s) => ({
      code: s.code,
      label: this.lang() === 'fr' ? s.labelFr : s.labelEn,
    }));
    // A code chosen earlier stays visible even when the catalogue cannot be read.
    const current = this.draft()?.specialtyCodes?.[0];
    if (current && !known.some((s) => s.code === current))
      known.push({ code: current, label: current });
    return known;
  });

  constructor() {
    this.transloco.langChanges$.subscribe((lang) => this.lang.set(lang));
    // The vocabulary follows the version's bound elements, so it is read again with the version.
    effect(() => {
      const id = this.version().id;
      void this.version().elements;
      if (id) untracked(() => void this.loadVocabulary(id));
    });
    effect(() => {
      const rules = this.rules();
      untracked(() => {
        const id = this.selectedId();
        if (id === 'new') return;
        const keep = rules.find((r) => r.id === id) ?? rules[0];
        if (keep) this.select(keep);
        else if (this.editable()) this.startNew();
        else this.draft.set(null);
      });
    });
  }

  select(r: Rule) {
    this.selectedId.set(r.id ?? null);
    this.ruleError.set(null);
    this.draft.set(structuredClone({ ...r, conditions: r.conditions ?? [] }));
  }

  startNew() {
    this.selectedId.set('new');
    this.ruleError.set(null);
    const first = this.signalChoices()[0];
    const condition = blankCondition();
    if (first) Object.assign(condition, this.forSignal(first));
    this.draft.set({ ...blankRule(), conditions: [condition] });
  }

  patch(change: Partial<Rule>) {
    this.draft.update((r) => (r ? { ...r, ...change } : r));
  }

  setCondition(i: number, change: Partial<Condition>) {
    this.draft.update((r) =>
      r ? { ...r, conditions: r.conditions.map((c, j) => (j === i ? { ...c, ...change } : c)) } : r,
    );
  }

  setSignal(i: number, key: string) {
    const choice = this.signalChoices().find((s) => s.key === key);
    if (choice) this.setCondition(i, this.forSignal(choice));
  }

  setComparison(i: number, comparison: Condition['comparison']) {
    const c = this.draft()?.conditions[i];
    if (!c) return;
    const values = this.valuesOf(c);
    let threshold = c.threshold;
    if (values && comparison === 'in' && !Array.isArray(threshold))
      threshold = threshold ? [threshold] : [];
    if (values && comparison !== 'in' && Array.isArray(threshold))
      threshold = threshold[0] ?? values[0]?.code;
    this.setCondition(i, { comparison, threshold });
  }

  toggleValue(i: number, code: string) {
    const c = this.draft()?.conditions[i];
    const list = Array.isArray(c?.threshold) ? (c.threshold as string[]) : [];
    this.setCondition(i, {
      threshold: list.includes(code) ? list.filter((x) => x !== code) : [...list, code],
    });
  }

  inThreshold(c: Condition, code: string): boolean {
    return Array.isArray(c.threshold) && (c.threshold as string[]).includes(code);
  }

  addCondition() {
    const first = this.signalChoices()[0];
    const condition = blankCondition();
    if (first) Object.assign(condition, this.forSignal(first));
    this.draft.update((r) => (r ? { ...r, conditions: [...r.conditions, condition] } : r));
  }

  removeCondition(i: number) {
    this.draft.update((r) =>
      r ? { ...r, conditions: r.conditions.filter((_, j) => j !== i) } : r,
    );
  }

  signalKey(c: Condition): string {
    return c.signal === 'daily_value' ? `daily_value:${c.element}` : c.signal;
  }

  private vocabularyFor(c: Condition): VocabularySignal | undefined {
    const key = this.signalKey(c);
    return this.vocabulary()?.signals.find((s) => s.key === key);
  }

  operatorsFor(c: Condition): Condition['operator'][] {
    const ops = this.vocabularyFor(c)?.operators.map((o) => o.code) ?? [];
    // A saved rule whose operator is no longer offered still shows it, so it can be changed.
    return ops.includes(c.operator) || !c.operator ? ops : [...ops, c.operator];
  }

  comparisonsFor(c: Condition): readonly Condition['comparison'][] {
    const op = this.vocabularyFor(c)?.operators.find((o) => o.code === c.operator);
    return op?.comparisons ?? [c.comparison];
  }

  hasField(c: Condition, field: Field): boolean {
    const op = this.vocabulary()?.operators.find((o) => o.code === c.operator);
    return op ? (op.fields as string[]).includes(field) : false;
  }

  async loadVocabulary(versionId: string) {
    try {
      this.vocabulary.set(
        orThrow(
          await this.api.client.GET('/admin/v1/tracking/versions/{versionId}/rule-vocabulary', {
            params: { path: { versionId } },
          }),
        ),
      );
      this.adoptVocabulary();
    } catch {
      this.failed.set(true);
    }
  }

  /** A new rule drafted before the vocabulary arrived starts on its first signal. */
  private adoptVocabulary() {
    const first = this.signalChoices()[0];
    if (this.selectedId() !== 'new' || !first) return;
    const known = new Set(this.signalChoices().map((s) => s.key));
    this.draft.update((r) =>
      r
        ? {
            ...r,
            conditions: r.conditions.map((c) =>
              known.has(this.signalKey(c)) ? c : { ...c, ...this.forSignal(first) },
            ),
          }
        : r,
    );
  }

  /** The labelled values of an ENUM element, for a daily condition on it; else null. */
  valuesOf(c: Condition): { code: string; label: string }[] | null {
    if (c.signal !== 'daily_value' || c.operator === 'average_in_window') return null;
    const e = this.library().find((x) => x.code === c.element);
    if (!e || e.valueType !== 'ENUM') return null;
    const labels = (e.labels as Record<string, { values?: Record<string, string> } | undefined>)[
      this.lang()
    ];
    return (e.constraints.values ?? []).map((v) => ({ code: v, label: labels?.values?.[v] ?? v }));
  }

  num(value: unknown): number | undefined {
    if (value === '' || value === null || value === undefined) return undefined;
    const n = Number(value);
    return Number.isFinite(n) ? n : undefined;
  }

  unlessText(r: Rule): string {
    const fields = (r.unless ?? []).map((u) => u.field).filter(Boolean);
    return fields.length
      ? fields.join(', ')
      : this.transloco.translate('admin.modules.rules.unlessNone');
  }

  problemText(p: string): string {
    if (p.startsWith('rule:')) {
      return this.transloco.translate('admin.modules.problems.rule', { code: p.slice(5) });
    }
    return this.transloco.translate(`admin.modules.problems.${p}`);
  }

  resultsFor(code: string): TestResult[] {
    return this.results().filter((t) => t.ruleCode === code);
  }

  firing(results: TestResult[]): number {
    return results.filter((t) => t.fires).length;
  }

  async runTests() {
    this.testing.set(true);
    try {
      this.results.set(
        orThrow(
          await this.api.client.POST('/admin/v1/tracking/versions/{versionId}/test-run', {
            params: { path: { versionId: this.version().id! } },
          }),
        ),
      );
    } catch {
      this.failed.set(true);
    } finally {
      this.testing.set(false);
    }
  }

  async save() {
    const r = this.draft();
    if (!r) return;
    this.busy.set(true);
    this.ruleError.set(null);
    const body: Rule = {
      code: r.code,
      conditions: r.conditions,
      unless: r.unless ?? [],
      outcome: r.outcome,
      specialtyCodes: r.outcome === 'INVITE' ? (r.specialtyCodes ?? []) : [],
      messageEn: r.messageEn,
      messageFr: r.messageFr,
      cooldownDays: r.cooldownDays,
      ...(r.outcome === 'PATTERN' && r.repeatWithinDays
        ? { repeatWithinDays: r.repeatWithinDays }
        : {}),
    };
    try {
      const saved = this.selected()
        ? await this.api.client.PUT('/admin/v1/tracking/rules/{ruleId}', {
            params: { path: { ruleId: this.selected()!.id! } },
            body,
          })
        : await this.api.client.POST('/admin/v1/tracking/versions/{versionId}/rules', {
            params: { path: { versionId: this.version().id! } },
            body,
          });
      const rule = orThrow(saved as { data?: Rule; error?: unknown; response: Response });
      this.selectedId.set(rule.id ?? null);
      this.changed.emit();
      void this.runTests();
    } catch (e) {
      this.ruleError.set(
        (e as { title?: string }).title ?? this.transloco.translate('common.checkConnection'),
      );
    } finally {
      this.busy.set(false);
    }
  }

  async remove() {
    const id = this.selected()?.id;
    if (!id) return;
    this.busy.set(true);
    try {
      orThrow(
        await this.api.client.DELETE('/admin/v1/tracking/rules/{ruleId}', {
          params: { path: { ruleId: id } },
        }),
      );
      this.selectedId.set(null);
      this.changed.emit();
    } catch {
      this.failed.set(true);
    } finally {
      this.busy.set(false);
    }
  }

  async submit() {
    this.busy.set(true);
    this.failed.set(false);
    try {
      this.submitted.emit(
        orThrow(
          await this.api.client.POST('/admin/v1/tracking/versions/{versionId}/submission', {
            params: { path: { versionId: this.version().id! } },
          }),
        ),
      );
    } catch {
      this.failed.set(true);
    } finally {
      this.busy.set(false);
    }
  }

  private nameOf(e: Element): string {
    const labels = e.labels as Record<string, { name?: string } | undefined>;
    return labels[this.lang()]?.name ?? labels['en']?.name ?? e.code;
  }

  private forSignal(choice: SignalChoice): Partial<Condition> {
    if (choice.kind === 'DAILY') {
      const c: Partial<Condition> = {
        signal: 'daily_value',
        element: choice.element,
        operator: 'days_in_window',
        mode: 'at_least',
        count: 1,
        window: 14,
        minData: 7,
      };
      const values = this.valuesOf({ ...blankCondition(), ...c } as Condition);
      c.comparison = values ? 'eq' : 'gte';
      c.threshold = values ? values.at(-1)?.code : 1;
      return c;
    }
    // An episode signal: the first operator the vocabulary offers for it, over the last episodes.
    const offered = this.vocabulary()?.signals.find((s) => s.key === choice.key)?.operators[0];
    return {
      signal: choice.signal,
      element: undefined,
      operator: offered?.code ?? 'consecutive_episodes',
      comparison: offered?.comparisons.includes('gt') ? 'gt' : offered?.comparisons[0],
      threshold: 1,
      count: 2,
      mode: undefined,
      window: undefined,
      minData: undefined,
    };
  }
}
