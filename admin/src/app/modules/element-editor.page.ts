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
import { LucideDynamicIcon, LucideLock } from '@lucide/angular';

import { ApiProblem, orThrow } from '../api/core-api';
import { TrackingApi, type TrackingSchemas } from '../api/tracking-api';
import { StaffSession } from '../auth/staff-session';
import { BackLink } from '../shared/back-link';
import { Requirements } from './requirements';
import { VersionSteps } from './version-steps';
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
 * Admin screen 32: one element version (D61; design/screens/admin/png/TrackingElementEditor.png).
 * The form on the left; on the right how the phone shows it in EN and FR, what is still needed
 * before submission, and the clinical review. Operations and Super admins draft from a view the app
 * ships; a Clinical reviewer who did not draft it approves or returns it; then it is published.
 */
@Component({
  selector: 'sl-element-editor',
  imports: [TranslocoPipe, FormsModule, BackLink, LucideDynamicIcon, VersionSteps, Requirements],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <sl-back-link to="/modules/elements" [label]="'admin.elements.back' | transloco" />
    @if (error()) {
      <div class="sl-notice sl-notice--danger" role="alert">
        <p>{{ 'common.checkConnection' | transloco }}</p>
        <button class="sl-button" type="button" (click)="load()">
          {{ 'common.tryAgain' | transloco }}
        </button>
      </div>
    } @else if (loaded()) {
      <div class="sl-head">
        <div>
          <div class="sl-head__title">
            <h1 class="sl-title">
              @if (version()) {
                {{ nameIn(lang()) || version()?.code }}
              } @else {
                {{ nameIn(lang()) || ('admin.elements.newTitle' | transloco) }}
              }
            </h1>
            @if (version(); as v) {
              <span
                class="sl-pill"
                [class.sl-pill--warning]="v.status !== 'PUBLISHED'"
                [class.sl-pill--brand]="v.status === 'PUBLISHED'"
              >
                {{ 'admin.modules.state.' + state(v) | transloco: { n: v.version } }}
              </span>
            }
          </div>
          @if (version(); as v) {
            <p class="sl-head__intro">
              {{ 'admin.elements.meta' | transloco: { code: v.code, who: v.draftedBy || '' } }}
              @if (v.minAppVersion) {
                · {{ 'admin.elements.minApp' | transloco: { v: v.minAppVersion } }}
              }
            </p>
          } @else {
            <p class="sl-head__intro">{{ 'admin.elements.newIntro' | transloco }}</p>
          }
        </div>
        <sl-version-steps [status]="version()?.status" />
      </div>

      @if (!editable() && version(); as v) {
        <div class="sl-notice sl-notice--warning notice" role="status">
          <svg [lucideIcon]="lockIcon" [size]="20" [strokeWidth]="1.8" aria-hidden="true"></svg>
          <p>
            <strong>{{ 'admin.versionLocked.' + v.status | transloco }}</strong>
            {{ 'admin.elements.readOnly' | transloco }}
          </p>
        </div>
      }

      <div class="sl-editor">
        <form class="sl-editor__main" (ngSubmit)="save()">
          <fieldset class="sl-card" [disabled]="!editable()">
            <legend class="sl-section">{{ 'admin.elements.names' | transloco }}</legend>
            <div class="sl-fields">
              <label class="sl-field">
                <span>{{ 'admin.elements.nameEn' | transloco }}</span>
                <input
                  class="sl-input"
                  name="nameEn"
                  lang="en"
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
                  lang="fr"
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
                  lang="en"
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
                  lang="fr"
                  maxlength="200"
                  [(ngModel)]="form.helpFr"
                  (ngModelChange)="touch()"
                />
              </label>
              <span class="sl-hint sl-field--wide">{{
                'admin.elements.helpHint' | transloco
              }}</span>
            </div>
          </fieldset>

          <fieldset class="sl-card" [disabled]="!editable()">
            <legend class="sl-section">{{ 'admin.elements.basics' | transloco }}</legend>
            <div class="sl-fields">
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
                    <option [value]="v.key">
                      {{ 'admin.elements.views.' + v.key | transloco }}
                    </option>
                  }
                </select>
                @if (fitting().length === 0) {
                  <span class="sl-hint">{{ 'admin.elements.noView' | transloco }}</span>
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
                  <option value="">{{ 'admin.elements.chooseIcon' | transloco }}</option>
                  @for (i of icons(); track i) {
                    <option [value]="i">{{ i }}</option>
                  }
                </select>
              </label>
              <label class="sl-field">
                <span>{{ 'admin.elements.code' | transloco }}</span>
                <input
                  class="sl-input code"
                  name="code"
                  required
                  maxlength="40"
                  pattern="[a-z][a-z0-9_]*"
                  autocomplete="off"
                  [disabled]="!!version() || !!from()"
                  [(ngModel)]="form.code"
                  (ngModelChange)="touch()"
                />
              </label>
              <span class="sl-hint sl-field--wide">{{
                'admin.elements.codeHint' | transloco
              }}</span>
            </div>
          </fieldset>

          @if (
            form.valueType === 'INT' || form.valueType === 'MINUTES' || form.valueType === 'TEXT'
          ) {
            <fieldset class="sl-card" [disabled]="!editable()">
              <legend class="sl-section">{{ 'admin.elements.limits' | transloco }}</legend>
              <div class="sl-fields limits">
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
                @if (view()?.props?.['start']) {
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
                }
              </div>
            </fieldset>
          }

          @if (form.valueType === 'ENUM' || form.valueType === 'INT') {
            <fieldset class="sl-card" [disabled]="!editable()">
              <legend class="sl-section">
                {{
                  (form.valueType === 'ENUM' ? 'admin.elements.choices' : 'admin.elements.points')
                    | transloco
                }}
              </legend>
              <p class="sl-hint">
                {{
                  (form.valueType === 'ENUM'
                    ? 'admin.elements.choicesHint'
                    : 'admin.elements.pointsHint'
                  ) | transloco
                }}
              </p>
              <div class="sl-rows" [class.points]="form.valueType === 'INT'">
                <div class="sl-rows__head" aria-hidden="true">
                  <span>{{ 'admin.elements.labelEn' | transloco }}</span>
                  <span>{{ 'admin.elements.labelFr' | transloco }}</span>
                  <span>
                    {{
                      (form.valueType === 'ENUM'
                        ? 'admin.elements.valueCode'
                        : 'admin.elements.pointCol'
                      ) | transloco
                    }}
                  </span>
                  <span></span>
                </div>
                @for (c of form.choices; track $index; let i = $index) {
                  <div class="sl-rows__row">
                    <input
                      class="sl-input"
                      lang="en"
                      [name]="'en' + i"
                      maxlength="60"
                      [attr.aria-label]="
                        'admin.elements.choiceEnN' | transloco: { n: c.code || i + 1 }
                      "
                      [(ngModel)]="c.en"
                      (ngModelChange)="touch()"
                    />
                    <input
                      class="sl-input"
                      lang="fr"
                      [name]="'fr' + i"
                      maxlength="60"
                      [attr.aria-label]="
                        'admin.elements.choiceFrN' | transloco: { n: c.code || i + 1 }
                      "
                      [(ngModel)]="c.fr"
                      (ngModelChange)="touch()"
                    />
                    @if (form.valueType === 'ENUM') {
                      <input
                        class="sl-input code"
                        [name]="'code' + i"
                        maxlength="40"
                        pattern="[a-z][a-z0-9_]*"
                        [attr.aria-label]="'admin.elements.choiceCodeN' | transloco: { n: i + 1 }"
                        [(ngModel)]="c.code"
                        (ngModelChange)="touch()"
                      />
                      @if (editable()) {
                        <button
                          type="button"
                          class="sl-link-button sl-link-button--danger"
                          [attr.aria-label]="
                            'admin.elements.removeChoice' | transloco: { code: c.code || i + 1 }
                          "
                          (click)="removeChoice(i)"
                        >
                          {{ 'admin.elements.remove' | transloco }}
                        </button>
                      } @else {
                        <span></span>
                      }
                    } @else {
                      <span class="point">{{ c.code }}</span>
                      <span></span>
                    }
                  </div>
                }
                @if (form.valueType === 'ENUM' && editable()) {
                  <button type="button" class="sl-button sl-rows__add" (click)="addChoice()">
                    {{ 'admin.elements.addChoice' | transloco }}
                  </button>
                }
              </div>
            </fieldset>
          }

          @if (editable()) {
            <div class="sl-actions">
              @if (saved()) {
                <span class="sl-status" role="status">{{
                  'admin.elements.saved' | transloco
                }}</span>
              }
              @if (problem()) {
                <span class="sl-error" role="alert">{{ problem()! | transloco }}</span>
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

        <aside class="sl-editor__rail">
          <section class="sl-card" aria-labelledby="preview-title">
            <h2 id="preview-title" class="sl-section">
              {{ 'admin.elements.preview' | transloco }}
            </h2>
            @for (l of langs; track l) {
              <div class="sl-phone" [attr.lang]="l">
                <span class="sl-phone__lang">{{
                  'admin.elements.previewIn.' + l | transloco
                }}</span>
                <p class="sl-phone__title">{{ nameIn(l) || '…' }}</p>
                @switch (previewKind()) {
                  @case ('chips') {
                    <div class="sl-phone__chips">
                      @for (c of form.choices; track $index) {
                        <span
                          class="sl-phone__chip"
                          [class.sl-phone__chip--empty]="!(l === 'en' ? c.en : c.fr)"
                          >{{ l === 'en' ? c.en : c.fr }}</span
                        >
                      }
                    </div>
                  }
                  @case ('stepper') {
                    <div class="sl-phone__chips stepper">
                      <span class="sl-phone__chip" aria-hidden="true">−</span>
                      <span class="strong">{{ 'admin.elements.hours' | transloco: hours() }}</span>
                      <span class="sl-phone__chip" aria-hidden="true">+</span>
                    </div>
                  }
                  @case ('toggle') {
                    <div class="sl-phone__chips">
                      <span class="sl-phone__chip">{{ 'admin.elements.yes' | transloco }}</span>
                    </div>
                  }
                  @case ('marker') {
                    <div class="sl-phone__chips">
                      <span class="sl-phone__chip">{{ helpIn(l) || nameIn(l) }}</span>
                    </div>
                  }
                  @case ('note') {
                    <div class="note" aria-hidden="true"></div>
                  }
                }
                @if (previewKind() !== 'marker' && helpIn(l)) {
                  <p class="sl-phone__help">{{ helpIn(l) }}</p>
                }
              </div>
            }
          </section>

          @if (editable() || version()?.status === 'IN_REVIEW') {
            <sl-requirements
              [codes]="requirementCodes()"
              [problems]="version()?.problems ?? null"
              prefix="admin.elements.problems."
            />
          }

          @if (version(); as v) {
            @if (v.status === 'IN_REVIEW' || (v.reviews?.length ?? 0) > 0) {
              <section class="sl-card" aria-labelledby="review-title">
                <h2 id="review-title" class="sl-section">
                  {{ 'admin.elements.review.title' | transloco }}
                </h2>
                @if (v.status === 'IN_REVIEW') {
                  @if (v.approvedBy) {
                    <p>{{ 'admin.elements.review.approved' | transloco: { who: v.approvedBy } }}</p>
                    @if (mayDraft()) {
                      <p class="sl-hint">{{ 'admin.elements.publishHint' | transloco }}</p>
                      <button
                        type="button"
                        class="sl-button sl-button--primary wide"
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
                        class="sl-textarea"
                        name="comments"
                        rows="3"
                        maxlength="4000"
                        [(ngModel)]="comments"
                      ></textarea>
                      <span class="sl-hint">{{
                        'admin.elements.review.commentsHint' | transloco
                      }}</span>
                    </label>
                    <div class="sl-actions review-actions">
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
                    <p class="sl-hint">{{ 'admin.elements.review.drafter' | transloco }}</p>
                  } @else {
                    <p class="sl-hint">{{ 'admin.elements.review.waiting' | transloco }}</p>
                  }
                }
                @if ((v.reviews?.length ?? 0) > 0) {
                  <ul
                    class="sl-history"
                    [attr.aria-label]="'admin.elements.review.history' | transloco"
                  >
                    @for (r of v.reviews; track $index) {
                      <li>
                        <span class="strong">
                          {{
                            'admin.elements.review.decision.' + r.decision
                              | transloco: { who: r.reviewer }
                          }}
                        </span>
                        @if (r.comments) {
                          <span class="sl-hint">{{ r.comments }}</span>
                        }
                      </li>
                    }
                  </ul>
                }
              </section>
            }

            @if (v.status === 'PUBLISHED' && mayDraft()) {
              <section class="sl-card" aria-labelledby="change-title">
                <h2 id="change-title" class="sl-section">
                  {{ 'admin.versionChange.title' | transloco }}
                </h2>
                <div class="stack">
                  <button
                    type="button"
                    class="sl-button"
                    [disabled]="busy()"
                    (click)="newVersion()"
                  >
                    {{ 'admin.elements.newVersion' | transloco }}
                  </button>
                  @if ((v.boundBy ?? 0) > 0) {
                    <p class="sl-hint">
                      {{ 'admin.elements.boundBy' | transloco: { n: v.boundBy } }}
                    </p>
                  } @else {
                    <button
                      type="button"
                      class="sl-button sl-button--danger"
                      [disabled]="busy()"
                      (click)="act('retirement')"
                    >
                      {{ 'admin.elements.retire' | transloco: { n: v.version } }}
                    </button>
                  }
                </div>
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
    .notice {
      margin-bottom: var(--space-16);
    }
    .limits {
      grid-template-columns: repeat(3, minmax(0, 1fr));
    }
    .points {
      --sl-rows-cols: 1fr 1fr 4rem 0;
    }
    .point {
      font-weight: 700;
      font-variant-numeric: tabular-nums;
      text-align: center;
    }
    .stepper {
      align-items: center;
      gap: var(--space-12);
    }
    .note {
      height: 64px;
      border: 1px solid var(--line-control);
      border-radius: var(--radius-14);
      background: var(--surface-card);
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
  protected readonly lockIcon = LucideLock;
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
  /** What a version must meet; only the checks that apply to what it records are listed. */
  readonly requirementCodes = computed(() => {
    this.edits();
    const t = this.form.valueType;
    return [
      'labels',
      ...(t === 'ENUM' || t === 'INT' ? ['values'] : []),
      'inputView',
      ...(this.view()?.props?.['start'] ? ['viewProps'] : []),
      ...(t === 'INT' || t === 'MINUTES' || t === 'TEXT' || t === 'ENUM' ? ['constraints'] : []),
      'icon',
    ];
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
