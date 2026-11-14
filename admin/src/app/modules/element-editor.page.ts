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
import { canDraft, canReview, stateOf } from './module-shared';

type Version = TrackingSchemas['ElementVersion'];
type View = TrackingSchemas['InputView'];
type Draft = TrackingSchemas['ElementDraft'];
type ValueType = Draft['valueType'];
type Lang = 'en' | 'fr';

export const VALUE_TYPES: ValueType[] = ['ENUM', 'INT', 'MINUTES', 'BOOL', 'DATE', 'TEXT'];

interface Choice {
  code: string;
  en: string;
  fr: string;
}

/** The form as the person edits it; the draft body is built from it. */
export interface ElementForm {
  code: string;
  valueType: ValueType;
  inputView: string;
  min: number | null;
  max: number | null;
  step: number | null;
  maxLength: number | null;
  choices: Choice[];
  start: number | null;
  icon: string;
  nameEn: string;
  nameFr: string;
  helpEn: string;
  helpFr: string;
}

export function emptyForm(): ElementForm {
  return {
    code: '',
    valueType: 'ENUM',
    inputView: 'choice_chips',
    min: null,
    max: null,
    step: null,
    maxLength: null,
    choices: [
      { code: '', en: '', fr: '' },
      { code: '', en: '', fr: '' },
    ],
    start: null,
    icon: '',
    nameEn: '',
    nameFr: '',
    helpEn: '',
    helpFr: '',
  };
}

/** A version's content as the form shows it. */
export function formOf(v: Version): ElementForm {
  const c = (v.constraints ?? {}) as {
    min?: number;
    max?: number;
    step?: number;
    values?: string[];
  };
  const en = v.labels?.en;
  const fr = v.labels?.fr;
  const type = v.valueType ?? 'ENUM';
  let choices: Choice[] = [];
  if (type === 'ENUM') {
    choices = (c.values ?? []).map((code) => ({
      code,
      en: en?.values?.[code] ?? '',
      fr: fr?.values?.[code] ?? '',
    }));
  } else if (type === 'INT' && c.min != null && c.max != null) {
    for (let i = c.min; i <= c.max; i++) {
      const code = String(i);
      choices.push({ code, en: en?.values?.[code] ?? '', fr: fr?.values?.[code] ?? '' });
    }
  }
  const props = (v.viewProps ?? {}) as { start?: number };
  return {
    code: v.code ?? '',
    valueType: type,
    inputView: v.inputView ?? '',
    min: type === 'INT' || type === 'MINUTES' ? (c.min ?? null) : null,
    max: type === 'INT' || type === 'MINUTES' ? (c.max ?? null) : null,
    step: type === 'MINUTES' ? (c.step ?? null) : null,
    maxLength: type === 'TEXT' ? (c.max ?? null) : null,
    choices,
    start: props.start ?? null,
    icon: v.icon ?? '',
    nameEn: en?.name ?? '',
    nameFr: fr?.name ?? '',
    helpEn: en?.help ?? '',
    helpFr: fr?.help ?? '',
  };
}

/** The draft body: constraints, props and labels by what the element records. */
export function draftOf(f: ElementForm, views: View[]): Draft {
  const view = views.find((v) => v.key === f.inputView);
  const constraints: Draft['constraints'] = {};
  const valued = f.valueType === 'ENUM' || f.valueType === 'INT';
  if (f.valueType === 'INT' || f.valueType === 'MINUTES') {
    if (f.min != null) constraints.min = f.min;
    if (f.max != null) constraints.max = f.max;
  }
  if (f.valueType === 'MINUTES' && f.step != null) constraints.step = f.step;
  if (f.valueType === 'TEXT' && f.maxLength != null) constraints.max = f.maxLength;
  if (f.valueType === 'ENUM') constraints.values = f.choices.map((c) => c.code.trim());
  const label = (lang: Lang) => {
    const name = (lang === 'en' ? f.nameEn : f.nameFr).trim();
    const help = (lang === 'en' ? f.helpEn : f.helpFr).trim();
    const out: { name: string; help?: string; values?: Record<string, string> } = { name };
    if (help) out.help = help;
    if (valued) {
      out.values = Object.fromEntries(
        f.choices.map((c) => [c.code.trim(), (lang === 'en' ? c.en : c.fr).trim()]),
      );
    }
    return out;
  };
  const viewProps: Record<string, unknown> = {};
  if (view?.props?.['start'] && f.start != null) viewProps['start'] = f.start;
  return {
    code: f.code.trim(),
    kind: view?.kinds?.[0] ?? (f.valueType === 'DATE' ? 'EVENT' : 'DAY_STATE'),
    valueType: f.valueType,
    constraints,
    inputView: f.inputView,
    viewProps,
    icon: f.icon,
    labels: { en: label('en'), fr: label('fr') },
  };
}

/**
 * Admin screen 33: one element version (D61). No design yet, so layout and copy are provisional
 * and follow the module builder. Operations and Super admins draft from a view the app ships; a
 * Clinical reviewer who did not draft it approves or returns it; then it is published. A preview
 * shows the logging row in EN and FR.
 */
@Component({
  selector: 'sl-element-editor',
  imports: [TranslocoPipe, FormsModule, BackLink],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <sl-back-link to="/modules/elements" [label]="'admin.elements.back' | transloco" />
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
            {{ 'admin.elements.heading' | transloco: { name: nameIn(lang()), n: v.version } }}
          } @else {
            {{ 'admin.elements.newTitle' | transloco }}
          }
        </h1>
        @if (version(); as v) {
          <span class="sl-pill">
            {{ 'admin.modules.state.' + state(v) | transloco: { n: v.version } }}
          </span>
        }
      </div>
      @if (version()?.minAppVersion; as app) {
        <p class="sl-meta">{{ 'admin.elements.minApp' | transloco: { v: app } }}</p>
      }
      @if (!editable() && version()) {
        <p class="sl-card notice">{{ 'admin.elements.readOnly' | transloco }}</p>
      }

      <div class="layout">
        <form class="main" (ngSubmit)="save()">
          <fieldset class="sl-card" [disabled]="!editable()">
            <legend class="sl-section">{{ 'admin.elements.basics' | transloco }}</legend>
            <label class="sl-field">
              <span>{{ 'admin.elements.code' | transloco }}</span>
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
              <span class="sl-meta">{{ 'admin.elements.codeHint' | transloco }}</span>
            </label>
            <label class="sl-field">
              <span>{{ 'admin.elements.valueType' | transloco }}</span>
              <select
                class="sl-select"
                name="valueType"
                [(ngModel)]="form.valueType"
                (ngModelChange)="typeChanged()"
              >
                @for (t of types; track t) {
                  <option [value]="t">{{ 'admin.elements.type.' + t | transloco }}</option>
                }
              </select>
            </label>
            <label class="sl-field">
              <span>{{ 'admin.elements.view' | transloco }}</span>
              <select
                class="sl-select"
                name="inputView"
                [(ngModel)]="form.inputView"
                (ngModelChange)="touch()"
              >
                @for (v of fitting(); track v.key) {
                  <option [value]="v.key">{{ 'admin.elements.views.' + v.key | transloco }}</option>
                }
              </select>
              @if (fitting().length === 0) {
                <span class="sl-meta">{{ 'admin.elements.noView' | transloco }}</span>
              }
            </label>
            <label class="sl-field">
              <span>{{ 'admin.elements.icon' | transloco }}</span>
              <select
                class="sl-select"
                name="icon"
                [(ngModel)]="form.icon"
                (ngModelChange)="touch()"
              >
                <option value=""></option>
                @for (i of icons(); track i) {
                  <option [value]="i">{{ i }}</option>
                }
              </select>
            </label>
          </fieldset>

          <fieldset class="sl-card" [disabled]="!editable()">
            <legend class="sl-section">{{ 'admin.elements.names' | transloco }}</legend>
            <div class="pair">
              <label class="sl-field">
                <span>{{ 'admin.elements.nameEn' | transloco }}</span>
                <input
                  class="sl-input"
                  name="nameEn"
                  maxlength="60"
                  [(ngModel)]="form.nameEn"
                  (ngModelChange)="touch()"
                />
              </label>
              <label class="sl-field">
                <span>{{ 'admin.elements.nameFr' | transloco }}</span>
                <input
                  class="sl-input"
                  name="nameFr"
                  maxlength="60"
                  [(ngModel)]="form.nameFr"
                  (ngModelChange)="touch()"
                />
              </label>
              <label class="sl-field">
                <span>{{ 'admin.elements.helpEn' | transloco }}</span>
                <input
                  class="sl-input"
                  name="helpEn"
                  maxlength="200"
                  [(ngModel)]="form.helpEn"
                  (ngModelChange)="touch()"
                />
              </label>
              <label class="sl-field">
                <span>{{ 'admin.elements.helpFr' | transloco }}</span>
                <input
                  class="sl-input"
                  name="helpFr"
                  maxlength="200"
                  [(ngModel)]="form.helpFr"
                  (ngModelChange)="touch()"
                />
              </label>
            </div>
          </fieldset>

          @if (
            form.valueType === 'INT' || form.valueType === 'MINUTES' || form.valueType === 'TEXT'
          ) {
            <fieldset class="sl-card" [disabled]="!editable()">
              <legend class="sl-section">{{ 'admin.elements.limits' | transloco }}</legend>
              <div class="pair">
                @if (form.valueType === 'TEXT') {
                  <label class="sl-field">
                    <span>{{ 'admin.elements.maxLength' | transloco }}</span>
                    <input
                      class="sl-input"
                      type="number"
                      name="maxLength"
                      [(ngModel)]="form.maxLength"
                      (ngModelChange)="touch()"
                    />
                  </label>
                } @else {
                  <label class="sl-field">
                    <span>{{ 'admin.elements.min' | transloco }}</span>
                    <input
                      class="sl-input"
                      type="number"
                      name="min"
                      [(ngModel)]="form.min"
                      (ngModelChange)="limitsChanged()"
                    />
                  </label>
                  <label class="sl-field">
                    <span>{{ 'admin.elements.max' | transloco }}</span>
                    <input
                      class="sl-input"
                      type="number"
                      name="max"
                      [(ngModel)]="form.max"
                      (ngModelChange)="limitsChanged()"
                    />
                  </label>
                }
                @if (form.valueType === 'MINUTES') {
                  <label class="sl-field">
                    <span>{{ 'admin.elements.step' | transloco }}</span>
                    <input
                      class="sl-input"
                      type="number"
                      name="step"
                      [(ngModel)]="form.step"
                      (ngModelChange)="touch()"
                    />
                  </label>
                }
              </div>
            </fieldset>
          }

          @if (form.valueType === 'ENUM' || form.valueType === 'INT') {
            <fieldset class="sl-card choices" [disabled]="!editable()">
              <legend class="sl-section">
                {{
                  (form.valueType === 'ENUM' ? 'admin.elements.choices' : 'admin.elements.points')
                    | transloco
                }}
              </legend>
              @for (c of form.choices; track $index; let i = $index) {
                <div class="choice">
                  @if (form.valueType === 'ENUM') {
                    <label class="sl-field">
                      <span>{{ 'admin.elements.valueCode' | transloco }}</span>
                      <input
                        class="sl-input"
                        [name]="'code' + i"
                        maxlength="40"
                        pattern="[a-z][a-z0-9_]*"
                        [(ngModel)]="c.code"
                        (ngModelChange)="touch()"
                      />
                    </label>
                  } @else {
                    <span class="point">{{
                      'admin.elements.point' | transloco: { n: c.code }
                    }}</span>
                  }
                  <label class="sl-field">
                    <span>{{ 'admin.elements.labelEn' | transloco }}</span>
                    <input
                      class="sl-input"
                      [name]="'en' + i"
                      maxlength="60"
                      [(ngModel)]="c.en"
                      (ngModelChange)="touch()"
                    />
                  </label>
                  <label class="sl-field">
                    <span>{{ 'admin.elements.labelFr' | transloco }}</span>
                    <input
                      class="sl-input"
                      [name]="'fr' + i"
                      maxlength="60"
                      [(ngModel)]="c.fr"
                      (ngModelChange)="touch()"
                    />
                  </label>
                  @if (form.valueType === 'ENUM') {
                    <button
                      type="button"
                      class="sl-button sl-button--danger"
                      [attr.aria-label]="
                        'admin.elements.removeChoice' | transloco: { code: c.code || i + 1 }
                      "
                      (click)="removeChoice(i)"
                    >
                      {{ 'admin.elements.remove' | transloco }}
                    </button>
                  }
                </div>
              }
              @if (form.valueType === 'ENUM') {
                <button type="button" class="sl-button add" (click)="addChoice()">
                  {{ 'admin.elements.addChoice' | transloco }}
                </button>
              }
            </fieldset>
          }

          @if (view()?.props?.['start']) {
            <fieldset class="sl-card" [disabled]="!editable()">
              <legend class="sl-section">{{ 'admin.elements.props' | transloco }}</legend>
              <label class="sl-field">
                <span>{{ 'admin.elements.prop.start' | transloco }}</span>
                <input
                  class="sl-input"
                  type="number"
                  name="start"
                  [(ngModel)]="form.start"
                  (ngModelChange)="touch()"
                />
              </label>
            </fieldset>
          }

          @if (editable()) {
            <div class="actions">
              @if (saved()) {
                <span class="sl-meta" role="status">{{ 'admin.elements.saved' | transloco }}</span>
              }
              @if (problem()) {
                <span class="error" role="alert">{{ problem()! | transloco }}</span>
              }
              <button type="submit" class="sl-button" [disabled]="busy() || !validCode()">
                {{ 'admin.elements.save' | transloco }}
              </button>
              @if (version()) {
                <button
                  type="button"
                  class="sl-button sl-button--primary"
                  [disabled]="busy() || dirty() || (version()?.problems?.length ?? 0) > 0"
                  (click)="act('submission')"
                >
                  {{ 'admin.elements.submit' | transloco }}
                </button>
              }
            </div>
          }
        </form>

        <aside class="side">
          <section class="sl-card" aria-labelledby="preview-title">
            <h2 id="preview-title" class="sl-section">
              {{ 'admin.elements.preview' | transloco }}
            </h2>
            @for (l of langs; track l) {
              <div class="phone" [attr.lang]="l">
                <p class="sl-meta">{{ 'admin.elements.previewIn.' + l | transloco }}</p>
                <p class="strong">{{ nameIn(l) }}</p>
                @switch (previewKind()) {
                  @case ('chips') {
                    <div class="chips">
                      @for (c of form.choices; track $index) {
                        <span class="chip">{{ l === 'en' ? c.en : c.fr }}</span>
                      }
                    </div>
                  }
                  @case ('stepper') {
                    <p class="stepper">
                      <span class="chip" aria-hidden="true">−</span>
                      {{ 'admin.elements.hours' | transloco: hours() }}
                      <span class="chip" aria-hidden="true">+</span>
                    </p>
                  }
                  @case ('toggle') {
                    <p class="chip">{{ 'admin.elements.yes' | transloco }}</p>
                  }
                  @case ('marker') {
                    <span class="chip">{{ helpIn(l) || nameIn(l) }}</span>
                  }
                  @case ('note') {
                    <div class="note" aria-hidden="true"></div>
                  }
                }
                @if (previewKind() !== 'marker' && helpIn(l)) {
                  <p class="sl-meta">{{ helpIn(l) }}</p>
                }
              </div>
            }
          </section>

          @if (version(); as v) {
            @if (v.status === 'DRAFT' || v.status === 'IN_REVIEW') {
              @if ((v.problems?.length ?? 0) > 0) {
                <section class="sl-card" aria-labelledby="problems-title">
                  <h2 id="problems-title" class="sl-section">
                    {{ 'admin.elements.problems.title' | transloco }}
                  </h2>
                  <ul>
                    @for (p of v.problems; track p) {
                      <li>{{ 'admin.elements.problems.' + p | transloco }}</li>
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
                  <p>{{ 'admin.elements.review.approved' | transloco: { who: v.approvedBy } }}</p>
                  @if (mayDraft()) {
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
                @if ((v.boundBy ?? 0) > 0) {
                  <p class="sl-meta">
                    {{ 'admin.elements.boundBy' | transloco: { n: v.boundBy } }}
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
    .add {
      align-self: start;
    }
    .pair {
      display: grid;
      grid-template-columns: repeat(2, minmax(0, 1fr));
      gap: var(--space-12);
    }
    .choice {
      display: grid;
      grid-template-columns: 1fr 1fr 1fr auto;
      gap: var(--space-8);
      align-items: end;
      margin-bottom: var(--space-8);
    }
    .point {
      align-self: center;
      font-weight: 700;
    }
    .actions {
      display: flex;
      justify-content: end;
      align-items: center;
      gap: var(--space-8);
    }
    .actions-card {
      display: flex;
      flex-direction: column;
      gap: var(--space-8);
      align-items: start;
    }
    .phone {
      border: 1px solid var(--line);
      border-radius: var(--radius-14);
      background: var(--surface-page);
      padding: var(--space-12) var(--space-14);
      margin-top: var(--space-12);
    }
    .phone p {
      margin: 0 0 var(--space-8);
    }
    .chips {
      display: flex;
      flex-wrap: wrap;
      gap: var(--space-8);
    }
    /* Chip, off (design-system Chip): the preview is not interactive. */
    .chip {
      display: inline-flex;
      align-items: center;
      min-height: var(--size-chip);
      min-width: var(--size-chip);
      box-sizing: border-box;
      padding: 0 var(--space-12);
      border: 1px solid var(--line-control);
      border-radius: var(--radius-pill);
      background: var(--surface-card);
      font-weight: 600;
    }
    .stepper {
      display: flex;
      align-items: center;
      gap: var(--space-12);
    }
    .note {
      min-height: 4rem;
      border: 1px solid var(--line-control);
      border-radius: var(--radius-12);
      background: var(--surface-card);
    }
    .strong {
      font-weight: 700;
    }
    .reviews {
      margin: 0;
      padding-left: var(--space-16);
    }
    .reviews li {
      display: flex;
      flex-direction: column;
    }
    .error {
      color: var(--danger);
      font-weight: 600;
    }
  `,
})
export class ElementEditorPage {
  private readonly api = inject(TrackingApi);
  private readonly staff = inject(StaffSession);
  private readonly router = inject(Router);
  private readonly transloco = inject(TranslocoService);

  /** A version id, or "new". */
  readonly id = input.required<string>();
  /** For a new version: the published version to start from. */
  readonly from = input<string>();

  readonly types = VALUE_TYPES;
  readonly langs: Lang[] = ['en', 'fr'];
  readonly views = signal<View[]>([]);
  readonly icons = signal<string[]>([]);
  readonly version = signal<Version | null>(null);
  readonly loaded = signal(false);
  readonly error = signal(false);
  readonly busy = signal(false);
  readonly saved = signal(false);
  readonly dirty = signal(false);
  readonly problem = signal<string | null>(null);
  /** Bumped on every edit, so what reads the form recomputes. */
  private readonly edits = signal(0);
  readonly lang = signal<Lang>(this.transloco.getActiveLang() === 'fr' ? 'fr' : 'en');

  form: ElementForm = emptyForm();
  comments = '';

  readonly mayDraft = computed(() => canDraft(this.staff.roles()));
  readonly mayReview = computed(() => canReview(this.staff.roles()));
  readonly editable = computed(
    () => this.mayDraft() && (this.version() === null || this.version()?.status === 'DRAFT'),
  );
  readonly fitting = computed(() => {
    this.edits();
    return this.views().filter((v) => v.valueTypes.includes(this.form.valueType));
  });
  readonly view = computed(() => {
    this.edits();
    return this.views().find((v) => v.key === this.form.inputView);
  });
  readonly validCode = computed(() => {
    this.edits();
    return /^[a-z][a-z0-9_]*$/.test(this.form.code);
  });
  readonly previewKind = computed(() => {
    switch (this.view()?.key) {
      case 'duration_stepper':
        return 'stepper';
      case 'toggle':
        return 'toggle';
      case 'event_marker':
        return 'marker';
      case 'free_text':
        return 'note';
      case undefined:
        return 'none';
      default:
        return 'chips';
    }
  });
  readonly hours = computed(() => {
    this.edits();
    const minutes = this.form.start ?? this.form.min ?? 0;
    return { h: Math.floor(minutes / 60), m: String(minutes % 60).padStart(2, '0') };
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
    try {
      const [views, icons] = await Promise.all([
        this.api.client.GET('/admin/v1/tracking/input-views'),
        this.api.client.GET('/admin/v1/tracking/element-icons'),
      ]);
      this.views.set(orThrow(views));
      this.icons.set(orThrow(icons));
      const id = this.id();
      const source = id === 'new' ? this.from() : id;
      if (source) {
        const v = orThrow(
          await this.api.client.GET('/admin/v1/tracking/element-versions/{elementVersionId}', {
            params: { path: { elementVersionId: source } },
          }),
        );
        this.form = formOf(v);
        this.version.set(id === 'new' ? null : v);
      } else {
        this.form = emptyForm();
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

  /** A new value type keeps only the views that take it, and resets its limits. */
  typeChanged() {
    const fits = this.views().filter((v) => v.valueTypes.includes(this.form.valueType));
    if (!fits.some((v) => v.key === this.form.inputView)) {
      this.form.inputView = fits[0]?.key ?? '';
    }
    this.form.min =
      this.form.valueType === 'INT' ? 1 : this.form.valueType === 'MINUTES' ? 0 : null;
    this.form.max =
      this.form.valueType === 'INT' ? 5 : this.form.valueType === 'MINUTES' ? 1440 : null;
    this.form.step = this.form.valueType === 'MINUTES' ? 15 : null;
    this.form.choices =
      this.form.valueType === 'ENUM'
        ? [
            { code: '', en: '', fr: '' },
            { code: '', en: '', fr: '' },
          ]
        : [];
    this.limitsChanged();
  }

  /** A scale has one labelled point per value between its limits. */
  limitsChanged() {
    if (this.form.valueType === 'INT' && this.form.min != null && this.form.max != null) {
      const old = new Map(this.form.choices.map((c) => [c.code, c]));
      const points: Choice[] = [];
      for (let i = this.form.min; i <= this.form.max && points.length < 11; i++) {
        points.push(old.get(String(i)) ?? { code: String(i), en: '', fr: '' });
      }
      this.form.choices = points;
    }
    this.touch();
  }

  addChoice() {
    this.form.choices = [...this.form.choices, { code: '', en: '', fr: '' }];
    this.touch();
  }

  removeChoice(i: number) {
    this.form.choices = this.form.choices.filter((_, n) => n !== i);
    this.touch();
  }

  nameIn(l: Lang): string {
    this.edits();
    return (l === 'en' ? this.form.nameEn : this.form.nameFr) || this.form.code;
  }

  helpIn(l: Lang): string {
    return l === 'en' ? this.form.helpEn : this.form.helpFr;
  }

  state(v: Version): string {
    return stateOf(v.status, v.approvedBy);
  }

  async save() {
    if (!this.validCode()) return;
    this.busy.set(true);
    this.problem.set(null);
    try {
      const body = draftOf(this.form, this.views());
      const current = this.version();
      if (current?.id) {
        const v = orThrow(
          await this.api.client.PUT('/admin/v1/tracking/element-versions/{elementVersionId}', {
            params: { path: { elementVersionId: current.id } },
            body,
          }),
        );
        this.version.set(v);
        this.dirty.set(false);
        this.saved.set(true);
      } else {
        const v = orThrow(
          await this.api.client.POST('/admin/v1/tracking/element-versions', { body }),
        );
        this.dirty.set(false);
        await this.router.navigate(['/modules/elements', v.id]);
      }
    } catch (e) {
      this.problem.set(
        e instanceof ApiProblem && e.status === 409
          ? 'admin.elements.codeTaken'
          : 'common.checkConnection',
      );
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
        submission: '/admin/v1/tracking/element-versions/{elementVersionId}/submission',
        publication: '/admin/v1/tracking/element-versions/{elementVersionId}/publication',
        retirement: '/admin/v1/tracking/element-versions/{elementVersionId}/retirement',
      } as const;
      this.version.set(
        orThrow(
          await this.api.client.POST(path[step], { params: { path: { elementVersionId: id } } }),
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
            '/admin/v1/tracking/element-versions/{elementVersionId}/review',
            {
              params: { path: { elementVersionId: id } },
              body: { approve, comments: this.comments.trim() || undefined },
            },
          ),
        ),
      );
      this.comments = '';
      if (!approve) this.form = formOf(this.version()!);
    } catch {
      this.problem.set('common.checkConnection');
    } finally {
      this.busy.set(false);
    }
  }

  /** A new version starts from this one's content; it is numbered when it is saved. */
  async newVersion() {
    await this.router.navigate(['/modules/elements', 'new'], {
      queryParams: { from: this.version()?.id },
    });
  }
}
