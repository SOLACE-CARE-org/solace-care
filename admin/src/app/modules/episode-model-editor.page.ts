import {
  ChangeDetectionStrategy,
  Component,
  computed,
  effect,
  inject,
  input,
  signal,
  untracked,
} from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Router } from '@angular/router';
import { TranslocoPipe, TranslocoService } from '@jsverse/transloco';

import { ApiProblem, orThrow } from '../api/core-api';
import { TrackingApi, type TrackingSchemas } from '../api/tracking-api';
import { StaffSession } from '../auth/staff-session';
import { BackLink } from '../shared/back-link';
import { canDraft, canReview, stateOf, textIn } from './module-shared';

type Version = TrackingSchemas['EpisodeModelVersion'];
type Draft = TrackingSchemas['EpisodeModelDraft'];
type Element = TrackingSchemas['Element'];
type TestRun = TrackingSchemas['EpisodeTestRun'];
type Kind = TrackingSchemas['EpisodeMetric']['kind'];
type Span = Draft['span'];
type Lang = 'en' | 'fr';

export const METRIC_KINDS: Kind[] = ['LENGTH_DAYS', 'ACTIVE_DAYS', 'DAYS_WITH_VALUE'];
export const SPANS: Span[] = ['UNTIL_NEXT_START', 'UNTIL_END'];

interface MetricForm {
  code: string;
  kind: Kind;
  value: string;
  en: string;
  fr: string;
}

/** The form as the person edits it; the draft body is built from it. */
export interface ModelForm {
  code: string;
  nameEn: string;
  nameFr: string;
  start: string;
  end: string;
  activity: string;
  active: string[];
  gapDays: number;
  span: Span;
  metrics: MetricForm[];
}

export function emptyModel(): ModelForm {
  return {
    code: '',
    nameEn: '',
    nameFr: '',
    start: '',
    end: '',
    activity: '',
    active: [],
    gapDays: 10,
    span: 'UNTIL_NEXT_START',
    metrics: [{ code: 'length_days', kind: 'LENGTH_DAYS', value: '', en: '', fr: '' }],
  };
}

export function modelFormOf(v: Version): ModelForm {
  return {
    code: v.code ?? '',
    nameEn: v.labels?.en ?? '',
    nameFr: v.labels?.fr ?? '',
    start: v.startElement ?? '',
    end: v.endElement ?? '',
    activity: v.activityElement ?? '',
    active: [...(v.activeValues ?? [])],
    gapDays: v.gapDays ?? 10,
    span: v.span ?? 'UNTIL_NEXT_START',
    metrics: (v.metrics ?? []).map((m) => ({
      code: m.code,
      kind: m.kind,
      value: m.value ?? '',
      en: m.labels.en,
      fr: m.labels.fr,
    })),
  };
}

/** The draft body; active values go only with an activity element, a value only with its kind. */
export function modelDraftOf(f: ModelForm): Draft {
  const activity = f.activity.trim();
  return {
    code: f.code.trim(),
    startElement: f.start,
    ...(f.end ? { endElement: f.end } : {}),
    ...(activity ? { activityElement: activity } : {}),
    activeValues: activity ? f.active : [],
    gapDays: f.gapDays,
    span: f.span,
    metrics: f.metrics.map((m) => ({
      code: m.code.trim(),
      kind: m.kind,
      ...(m.kind === 'DAYS_WITH_VALUE' && m.value ? { value: m.value } : {}),
      labels: { en: m.en.trim(), fr: m.fr.trim() },
    })),
    labels: { en: f.nameEn.trim(), fr: f.nameFr.trim() },
  };
}

/**
 * Admin screen 33: one episode model version (D61). No design yet, so layout and copy are
 * provisional and follow the element editor, which follows the module builder. Operations and
 * Super admins draft from published elements and run it on the test histories; a Clinical reviewer
 * who did not draft it approves or returns it; then it is published.
 */
@Component({
  selector: 'sl-episode-model-editor',
  imports: [TranslocoPipe, FormsModule, BackLink],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <sl-back-link to="/modules/episode-models" [label]="'admin.episodes.back' | transloco" />
    @if (error()) {
      <div class="sl-card">
        <p>{{ 'common.checkConnection' | transloco }}</p>
        <button class="sl-button" type="button" (click)="load()">
          {{ 'common.tryAgain' | transloco }}
        </button>
      </div>
    } @else if (loaded()) {
      <div class="head">
        <h1 class="sl-title">
          @if (version(); as v) {
            {{ 'admin.episodes.heading' | transloco: { name: nameIn(lang()), n: v.version } }}
          } @else {
            {{ 'admin.episodes.newTitle' | transloco }}
          }
        </h1>
        @if (version(); as v) {
          <span class="sl-pill">
            {{ 'admin.modules.state.' + state(v) | transloco: { n: v.version } }}
          </span>
        }
      </div>
      @if (!editable() && version()) {
        <p class="sl-card notice">
          {{ 'admin.episodes.readOnly' | transloco }}
        </p>
      }

      <div class="layout">
        <form class="main" (ngSubmit)="save()">
          <fieldset class="sl-card" [disabled]="!editable()">
            <legend class="sl-section">
              {{ 'admin.episodes.basics' | transloco }}
            </legend>
            <label class="sl-field">
              <span>{{ 'admin.episodes.code' | transloco }}</span>
              <input
                class="sl-input"
                name="code"
                required
                maxlength="40"
                pattern="[a-z][a-z0-9_]*"
                autocomplete="off"
                [disabled]="!!version() || !!from()"
                [(ngModel)]="form.code"
                (ngModelChange)="touch()"
              />
              <span class="sl-meta">{{ 'admin.episodes.codeHint' | transloco }}</span>
            </label>
            <div class="pair">
              <label class="sl-field">
                <span>{{ 'admin.episodes.nameEn' | transloco }}</span>
                <input
                  class="sl-input"
                  name="nameEn"
                  maxlength="60"
                  [(ngModel)]="form.nameEn"
                  (ngModelChange)="touch()"
                />
              </label>
              <label class="sl-field">
                <span>{{ 'admin.episodes.nameFr' | transloco }}</span>
                <input
                  class="sl-input"
                  name="nameFr"
                  maxlength="60"
                  [(ngModel)]="form.nameFr"
                  (ngModelChange)="touch()"
                />
              </label>
            </div>
          </fieldset>

          <fieldset class="sl-card" [disabled]="!editable()">
            <legend class="sl-section">
              {{ 'admin.episodes.finding' | transloco }}
            </legend>
            <label class="sl-field">
              <span>{{ 'admin.episodes.start' | transloco }}</span>
              <select
                class="sl-select"
                name="start"
                [(ngModel)]="form.start"
                (ngModelChange)="touch()"
              >
                <option value=""></option>
                @for (e of events(); track e.code) {
                  <option [value]="e.code">{{ elementName(e) }}</option>
                }
              </select>
            </label>
            <label class="sl-field">
              <span>{{ 'admin.episodes.end' | transloco }}</span>
              <select class="sl-select" name="end" [(ngModel)]="form.end" (ngModelChange)="touch()">
                <option value="">
                  {{ 'admin.episodes.none' | transloco }}
                </option>
                @for (e of events(); track e.code) {
                  <option [value]="e.code">{{ elementName(e) }}</option>
                }
              </select>
            </label>
            <label class="sl-field">
              <span>{{ 'admin.episodes.activity' | transloco }}</span>
              <select
                class="sl-select"
                name="activity"
                [(ngModel)]="form.activity"
                (ngModelChange)="activityChanged()"
              >
                <option value="">
                  {{ 'admin.episodes.none' | transloco }}
                </option>
                @for (e of states(); track e.code) {
                  <option [value]="e.code">{{ elementName(e) }}</option>
                }
              </select>
            </label>
            @if (activityValues().length > 0) {
              <div class="sl-field" role="group" aria-labelledby="active-label">
                <span id="active-label">{{ 'admin.episodes.active' | transloco }}</span>
                <div class="checks">
                  @for (v of activityValues(); track v.code) {
                    <label class="check">
                      <input
                        type="checkbox"
                        [checked]="form.active.includes(v.code)"
                        [disabled]="!editable()"
                        (change)="toggleActive(v.code)"
                      />
                      {{ v.label }}
                    </label>
                  }
                </div>
              </div>
            }
            <div class="pair">
              <label class="sl-field">
                <span>{{ 'admin.episodes.gap' | transloco }}</span>
                <input
                  class="sl-input"
                  type="number"
                  name="gapDays"
                  min="1"
                  max="365"
                  [(ngModel)]="form.gapDays"
                  (ngModelChange)="touch()"
                />
                <span class="sl-meta">{{ 'admin.episodes.gapHint' | transloco }}</span>
              </label>
              <label class="sl-field">
                <span>{{ 'admin.episodes.span' | transloco }}</span>
                <select
                  class="sl-select"
                  name="span"
                  [(ngModel)]="form.span"
                  (ngModelChange)="touch()"
                >
                  @for (s of spans; track s) {
                    <option [value]="s">
                      {{ 'admin.episodes.spans.' + s | transloco }}
                    </option>
                  }
                </select>
              </label>
            </div>
          </fieldset>

          <fieldset class="sl-card" [disabled]="!editable()">
            <legend class="sl-section">
              {{ 'admin.episodes.metrics' | transloco }}
            </legend>
            <p class="sl-meta">
              {{ 'admin.episodes.metricsHint' | transloco }}
            </p>
            @for (m of form.metrics; track $index; let i = $index) {
              <div class="metric">
                <div class="metric-row">
                  <label class="sl-field">
                    <span>{{ 'admin.episodes.metricCode' | transloco }}</span>
                    <input
                      class="sl-input"
                      [name]="'mcode' + i"
                      maxlength="40"
                      pattern="[a-z][a-z0-9_]*"
                      [(ngModel)]="m.code"
                      (ngModelChange)="touch()"
                    />
                  </label>
                  <label class="sl-field">
                    <span>{{ 'admin.episodes.metricKind' | transloco }}</span>
                    <select
                      class="sl-select"
                      [name]="'mkind' + i"
                      [(ngModel)]="m.kind"
                      (ngModelChange)="touch()"
                    >
                      @for (k of kinds; track k) {
                        <option [value]="k">
                          {{ 'admin.episodes.kinds.' + k | transloco }}
                        </option>
                      }
                    </select>
                  </label>
                  @if (m.kind === 'DAYS_WITH_VALUE') {
                    <label class="sl-field">
                      <span>{{ 'admin.episodes.metricValue' | transloco }}</span>
                      <select
                        class="sl-select"
                        [name]="'mvalue' + i"
                        [(ngModel)]="m.value"
                        (ngModelChange)="touch()"
                      >
                        <option value=""></option>
                        @for (v of activityValues(); track v.code) {
                          <option [value]="v.code">{{ v.label }}</option>
                        }
                      </select>
                    </label>
                  }
                </div>
                <div class="metric-row">
                  <label class="sl-field">
                    <span>{{ 'admin.episodes.labelEn' | transloco }}</span>
                    <input
                      class="sl-input"
                      [name]="'men' + i"
                      maxlength="60"
                      [(ngModel)]="m.en"
                      (ngModelChange)="touch()"
                    />
                  </label>
                  <label class="sl-field">
                    <span>{{ 'admin.episodes.labelFr' | transloco }}</span>
                    <input
                      class="sl-input"
                      [name]="'mfr' + i"
                      maxlength="60"
                      [(ngModel)]="m.fr"
                      (ngModelChange)="touch()"
                    />
                  </label>
                  @if (editable()) {
                    <button
                      type="button"
                      class="sl-button sl-button--danger remove"
                      [attr.aria-label]="
                        'admin.episodes.removeMetric' | transloco: { code: m.code || i + 1 }
                      "
                      (click)="removeMetric(i)"
                    >
                      {{ 'admin.episodes.remove' | transloco }}
                    </button>
                  }
                </div>
              </div>
            }
            @if (editable() && form.metrics.length < 8) {
              <button type="button" class="sl-button add" (click)="addMetric()">
                {{ 'admin.episodes.addMetric' | transloco }}
              </button>
            }
          </fieldset>

          @if (editable()) {
            <div class="actions">
              @if (saved()) {
                <span class="sl-meta" role="status">{{ 'admin.episodes.saved' | transloco }}</span>
              }
              @if (problem()) {
                <span class="error" role="alert">{{ problem()! | transloco }}</span>
              }
              <button type="submit" class="sl-button" [disabled]="busy() || !validCode()">
                {{ 'admin.episodes.save' | transloco }}
              </button>
              @if (version()) {
                <button
                  type="button"
                  class="sl-button sl-button--primary"
                  [disabled]="busy() || dirty() || (version()?.problems?.length ?? 0) > 0"
                  (click)="act('submission')"
                >
                  {{ 'admin.episodes.submit' | transloco }}
                </button>
              }
            </div>
          }
        </form>

        <aside class="side">
          @if (version(); as v) {
            <section class="sl-card" aria-labelledby="tests-title" aria-live="polite">
              <h2 id="tests-title" class="sl-section">
                {{ 'admin.episodes.tests.title' | transloco }}
              </h2>
              <p class="sl-meta">
                {{ 'admin.episodes.tests.hint' | transloco }}
              </p>
              <button
                type="button"
                class="sl-button"
                [disabled]="busy() || dirty()"
                (click)="runTests()"
              >
                {{ 'admin.episodes.tests.run' | transloco }}
              </button>
              @if (runs(); as rs) {
                <ul class="runs">
                  @for (r of rs; track r.history) {
                    <li>
                      <span class="strong">{{ historyName(r) }}</span>
                      <span class="sl-meta">
                        {{
                          'admin.episodes.tests.found'
                            | transloco: { n: r.episodes.length, c: complete(r) }
                        }}
                      </span>
                      @for (m of v.metrics ?? []; track m.code) {
                        @if (complete(r) > 0) {
                          <span class="sl-meta">
                            {{ (lang() === 'fr' ? m.labels.fr : m.labels.en) || m.code }}:
                            {{ values(r, m.code) }}
                          </span>
                        }
                      }
                    </li>
                  }
                </ul>
              }
            </section>

            @if (v.status === 'DRAFT' || v.status === 'IN_REVIEW') {
              @if ((v.problems?.length ?? 0) > 0) {
                <section class="sl-card" aria-labelledby="problems-title">
                  <h2 id="problems-title" class="sl-section">
                    {{ 'admin.episodes.problems.title' | transloco }}
                  </h2>
                  <ul>
                    @for (p of v.problems; track p) {
                      <li>{{ 'admin.episodes.problems.' + p | transloco }}</li>
                    }
                  </ul>
                </section>
              }
            }

            @if (v.status === 'IN_REVIEW') {
              <section class="sl-card" aria-labelledby="review-title">
                <h2 id="review-title" class="sl-section">
                  {{ 'admin.elements.review.title' | transloco }}
                </h2>
                @if (v.approvedBy) {
                  <p>
                    {{ 'admin.elements.review.approved' | transloco: { who: v.approvedBy } }}
                  </p>
                  @if (mayDraft()) {
                    <p class="sl-meta">
                      {{ 'admin.episodes.publishHint' | transloco }}
                    </p>
                    <button
                      type="button"
                      class="sl-button sl-button--primary"
                      [disabled]="busy()"
                      (click)="act('publication')"
                    >
                      {{ 'admin.elements.publish' | transloco: { n: v.version } }}
                    </button>
                  }
                } @else if (v.canApprove) {
                  <label class="sl-field">
                    <span>{{ 'admin.elements.review.comments' | transloco }}</span>
                    <textarea
                      class="sl-input"
                      name="comments"
                      maxlength="4000"
                      [(ngModel)]="comments"
                    ></textarea>
                  </label>
                  <div class="actions">
                    <button
                      type="button"
                      class="sl-button"
                      [disabled]="busy() || !comments.trim()"
                      (click)="review(false)"
                    >
                      {{ 'admin.elements.review.return' | transloco }}
                    </button>
                    <button
                      type="button"
                      class="sl-button sl-button--primary"
                      [disabled]="busy()"
                      (click)="review(true)"
                    >
                      {{ 'admin.elements.review.approve' | transloco }}
                    </button>
                  </div>
                } @else if (mayReview()) {
                  <p>{{ 'admin.elements.review.drafter' | transloco }}</p>
                } @else {
                  <p>{{ 'admin.elements.review.waiting' | transloco }}</p>
                }
              </section>
            }

            @if ((v.reviews?.length ?? 0) > 0) {
              <section class="sl-card">
                <ul class="reviews">
                  @for (r of v.reviews; track $index) {
                    <li>
                      <span class="strong">
                        {{
                          'admin.elements.review.decision.' + r.decision
                            | transloco: { who: r.reviewer }
                        }}
                      </span>
                      @if (r.comments) {
                        <span>{{ r.comments }}</span>
                      }
                    </li>
                  }
                </ul>
              </section>
            }

            @if (v.status === 'PUBLISHED' && mayDraft()) {
              <section class="sl-card actions-card">
                <button type="button" class="sl-button" [disabled]="busy()" (click)="newVersion()">
                  {{ 'admin.elements.newVersion' | transloco }}
                </button>
                @if ((v.usedBy ?? 0) > 0) {
                  <p class="sl-meta">
                    {{ 'admin.episodes.usedBy' | transloco: { n: v.usedBy } }}
                  </p>
                } @else {
                  <button
                    type="button"
                    class="sl-button"
                    [disabled]="busy()"
                    (click)="act('retirement')"
                  >
                    {{ 'admin.elements.retire' | transloco: { n: v.version } }}
                  </button>
                }
              </section>
            }
          } @else {
            <section class="sl-card">
              <p class="sl-meta">
                {{ 'admin.episodes.tests.afterSave' | transloco }}
              </p>
            </section>
          }
        </aside>
      </div>
    } @else {
      <p class="sl-muted">{{ 'common.loading' | transloco }}</p>
    }
  `,
  styles: `
    .head {
      display: flex;
      align-items: center;
      gap: var(--space-12);
    }
    .notice {
      margin: var(--space-12) 0;
    }
    .layout {
      display: grid;
      grid-template-columns: minmax(0, 2fr) minmax(16rem, 1fr);
      gap: var(--space-20);
      margin-top: var(--space-16);
    }
    .main,
    .side {
      display: flex;
      flex-direction: column;
      gap: var(--space-16);
    }
    .side .sl-card {
      display: flex;
      flex-direction: column;
      gap: var(--space-8);
      align-items: start;
    }
    fieldset {
      display: flex;
      flex-direction: column;
      gap: var(--space-14);
      min-width: 0;
      margin: 0;
    }
    /* The heading sits inside the card, as on ModuleDetails.dc.html, not on its border. */
    legend {
      float: left;
      width: 100%;
      padding: 0;
    }
    legend + * {
      clear: both;
    }
    .pair {
      display: grid;
      grid-template-columns: repeat(2, minmax(0, 1fr));
      gap: var(--space-12);
    }
    .checks {
      display: flex;
      flex-wrap: wrap;
      gap: var(--space-4) var(--space-16);
    }
    .check {
      display: inline-flex;
      align-items: center;
      gap: var(--space-8);
      min-height: 44px;
    }
    .metric {
      display: flex;
      flex-direction: column;
      gap: var(--space-8);
      padding-bottom: var(--space-12);
      border-bottom: 1px solid var(--line);
    }
    .metric-row {
      display: grid;
      grid-template-columns: repeat(3, minmax(0, 1fr));
      gap: var(--space-8);
      align-items: end;
    }
    fieldset > p,
    .side p {
      margin: 0;
    }
    .side .sl-field,
    .side .actions {
      align-self: stretch;
    }
    .remove {
      justify-self: start;
    }
    .add {
      align-self: start;
    }
    .actions {
      display: flex;
      justify-content: end;
      align-items: center;
      gap: var(--space-8);
    }
    .runs {
      margin: 0;
      padding-left: var(--space-16);
    }
    .runs li,
    .reviews li {
      display: flex;
      flex-direction: column;
      margin-bottom: var(--space-8);
    }
    .reviews {
      margin: 0;
      padding-left: var(--space-16);
    }
    .strong {
      font-weight: 700;
    }
    .error {
      color: var(--danger);
      font-weight: 600;
    }
  `,
})
export class EpisodeModelEditorPage {
  private readonly api = inject(TrackingApi);
  private readonly staff = inject(StaffSession);
  private readonly router = inject(Router);
  private readonly transloco = inject(TranslocoService);

  /** A version id, or "new". */
  readonly id = input.required<string>();
  /** For a new version: the published version to start from. */
  readonly from = input<string>();

  readonly kinds = METRIC_KINDS;
  readonly spans = SPANS;
  readonly elements = signal<Element[]>([]);
  readonly version = signal<Version | null>(null);
  readonly runs = signal<TestRun[] | null>(null);
  readonly loaded = signal(false);
  readonly error = signal(false);
  readonly busy = signal(false);
  readonly saved = signal(false);
  readonly dirty = signal(false);
  readonly problem = signal<string | null>(null);
  /** Bumped on every edit, so what reads the form recomputes. */
  private readonly edits = signal(0);
  readonly lang = signal<Lang>(this.transloco.getActiveLang() === 'fr' ? 'fr' : 'en');

  form: ModelForm = emptyModel();
  comments = '';

  readonly mayDraft = computed(() => canDraft(this.staff.roles()));
  readonly mayReview = computed(() => canReview(this.staff.roles()));
  readonly editable = computed(
    () => this.mayDraft() && (this.version() === null || this.version()?.status === 'DRAFT'),
  );
  readonly events = computed(() => this.elements().filter((e) => e.kind === 'EVENT'));
  readonly states = computed(() =>
    this.elements().filter((e) => e.kind === 'DAY_STATE' && e.valueType === 'ENUM'),
  );
  /** The activity element's values, named in the admin's language. */
  readonly activityValues = computed(() => {
    this.edits();
    const e = this.elements().find((x) => x.code === this.form.activity);
    const values = ((e?.constraints ?? {}) as { values?: string[] }).values ?? [];
    const names = (this.lang() === 'fr' ? e?.labels?.fr : e?.labels?.en)?.values ?? {};
    return values.map((code) => ({ code, label: names[code] || code }));
  });
  readonly validCode = computed(() => {
    this.edits();
    return /^[a-z][a-z0-9_]*$/.test(this.form.code);
  });

  constructor() {
    this.transloco.langChanges$.subscribe((l) => this.lang.set(l === 'fr' ? 'fr' : 'en'));
    effect(() => {
      this.id();
      untracked(() => void this.load());
    });
  }

  async load() {
    this.error.set(false);
    this.runs.set(null);
    try {
      this.elements.set(orThrow(await this.api.client.GET('/admin/v1/tracking/elements')));
      const id = this.id();
      const source = id === 'new' ? this.from() : id;
      if (source) {
        const v = orThrow(
          await this.api.client.GET(
            '/admin/v1/tracking/episode-model-versions/{episodeModelVersionId}',
            {
              params: { path: { episodeModelVersionId: source } },
            },
          ),
        );
        this.form = modelFormOf(v);
        this.version.set(id === 'new' ? null : v);
      } else {
        this.form = emptyModel();
        this.version.set(null);
      }
      this.dirty.set(false);
      this.loaded.set(true);
      this.touch(false);
    } catch {
      this.error.set(true);
    }
  }

  touch(edited = true) {
    this.edits.update((n) => n + 1);
    if (edited) {
      this.dirty.set(true);
      this.saved.set(false);
    }
  }

  /** Another activity element keeps only the active values it has. */
  activityChanged() {
    this.touch();
    const codes = new Set(this.activityValues().map((v) => v.code));
    this.form.active = this.form.active.filter((c) => codes.has(c));
  }

  toggleActive(code: string) {
    this.form.active = this.form.active.includes(code)
      ? this.form.active.filter((c) => c !== code)
      : [...this.form.active, code];
    this.touch();
  }

  addMetric() {
    this.form.metrics = [
      ...this.form.metrics,
      { code: '', kind: 'ACTIVE_DAYS', value: '', en: '', fr: '' },
    ];
    this.touch();
  }

  removeMetric(i: number) {
    this.form.metrics = this.form.metrics.filter((_, n) => n !== i);
    this.touch();
  }

  elementName(e: Element): string {
    return (this.lang() === 'fr' ? e.labels?.fr?.name : e.labels?.en?.name) || e.code;
  }

  nameIn(l: Lang): string {
    this.edits();
    return (l === 'en' ? this.form.nameEn : this.form.nameFr) || this.form.code;
  }

  historyName(r: TestRun): string {
    return textIn(r.historyLabels, this.lang()) || r.history;
  }

  complete(r: TestRun): number {
    return r.episodes.filter((e) => e.complete).length;
  }

  /** A metric across the history's complete episodes, oldest first. */
  values(r: TestRun, code: string): string {
    return r.episodes
      .filter((e) => e.complete && e.metrics[code] != null)
      .map((e) => e.metrics[code])
      .join(', ');
  }

  state(v: Version): string {
    return stateOf(v.status, v.approvedBy);
  }

  async save() {
    if (!this.validCode()) return;
    this.busy.set(true);
    this.problem.set(null);
    try {
      const body = modelDraftOf(this.form);
      const current = this.version();
      if (current?.id) {
        const v = orThrow(
          await this.api.client.PUT(
            '/admin/v1/tracking/episode-model-versions/{episodeModelVersionId}',
            {
              params: { path: { episodeModelVersionId: current.id } },
              body,
            },
          ),
        );
        this.version.set(v);
        this.runs.set(null);
        this.dirty.set(false);
        this.saved.set(true);
      } else {
        const v = orThrow(
          await this.api.client.POST('/admin/v1/tracking/episode-model-versions', { body }),
        );
        this.dirty.set(false);
        await this.router.navigate(['/modules/episode-models', v.id]);
      }
    } catch (e) {
      this.problem.set(
        e instanceof ApiProblem && e.status === 409
          ? 'admin.episodes.codeTaken'
          : 'common.checkConnection',
      );
    } finally {
      this.busy.set(false);
    }
  }

  async runTests() {
    const id = this.version()?.id;
    if (!id) return;
    this.busy.set(true);
    try {
      this.runs.set(
        orThrow(
          await this.api.client.POST(
            '/admin/v1/tracking/episode-model-versions/{episodeModelVersionId}/test-run',
            { params: { path: { episodeModelVersionId: id } } },
          ),
        ),
      );
    } catch {
      this.problem.set('common.checkConnection');
    } finally {
      this.busy.set(false);
    }
  }

  async act(step: 'submission' | 'publication' | 'retirement') {
    const id = this.version()?.id;
    if (!id) return;
    this.busy.set(true);
    this.problem.set(null);
    try {
      const path = {
        submission: '/admin/v1/tracking/episode-model-versions/{episodeModelVersionId}/submission',
        publication:
          '/admin/v1/tracking/episode-model-versions/{episodeModelVersionId}/publication',
        retirement: '/admin/v1/tracking/episode-model-versions/{episodeModelVersionId}/retirement',
      } as const;
      this.version.set(
        orThrow(
          await this.api.client.POST(path[step], {
            params: { path: { episodeModelVersionId: id } },
          }),
        ),
      );
    } catch {
      this.problem.set('common.checkConnection');
    } finally {
      this.busy.set(false);
    }
  }

  async review(approve: boolean) {
    const id = this.version()?.id;
    if (!id) return;
    this.busy.set(true);
    try {
      this.version.set(
        orThrow(
          await this.api.client.POST(
            '/admin/v1/tracking/episode-model-versions/{episodeModelVersionId}/review',
            {
              params: { path: { episodeModelVersionId: id } },
              body: { approve, comments: this.comments.trim() || undefined },
            },
          ),
        ),
      );
      this.comments = '';
      if (!approve) this.form = modelFormOf(this.version()!);
    } catch {
      this.problem.set('common.checkConnection');
    } finally {
      this.busy.set(false);
    }
  }

  /** A new version starts from this one's content and takes the next number when saved. */
  async newVersion() {
    await this.router.navigate(['/modules/episode-models', 'new'], {
      queryParams: { from: this.version()?.id },
    });
  }
}
