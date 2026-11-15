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

type Version = TrackingSchemas['ContextQuestionVersion'];
type Draft = TrackingSchemas['ContextQuestionDraft'];
type QuestionType = Draft['type'];
type Scope = Draft['scope'];
type Lang = 'en' | 'fr';

export const QUESTION_TYPES: QuestionType[] = ['YES_NO', 'ONE_CHOICE', 'MANY_CHOICES'];
export const SCOPES: Scope[] = ['PROFILE', 'ENROLMENT'];
const MAX_OPTIONS = 12;

interface OptionForm {
  code: string;
  en: string;
  fr: string;
}

/** The form as the person edits it; the draft body is built from it. */
export interface QuestionForm {
  code: string;
  type: QuestionType;
  scope: Scope;
  en: string;
  fr: string;
  options: OptionForm[];
}

export function emptyQuestion(): QuestionForm {
  return { code: '', type: 'YES_NO', scope: 'PROFILE', en: '', fr: '', options: [] };
}

export function questionFormOf(v: Version): QuestionForm {
  return {
    code: v.code ?? '',
    type: v.type ?? 'YES_NO',
    scope: v.scope ?? 'PROFILE',
    en: v.question?.en ?? '',
    fr: v.question?.fr ?? '',
    options: (v.options ?? []).map((o) => ({ code: o.code, en: o.labels.en, fr: o.labels.fr })),
  };
}

/** The draft body; a yes or no question sends no choices. */
export function questionDraftOf(f: QuestionForm): Draft {
  return {
    code: f.code.trim(),
    type: f.type,
    scope: f.scope,
    question: { en: f.en.trim(), fr: f.fr.trim() },
    options:
      f.type === 'YES_NO'
        ? []
        : f.options.map((o) => ({
            code: o.code.trim(),
            labels: { en: o.en.trim(), fr: o.fr.trim() },
          })),
  };
}

/**
 * Admin screen 34: one context question version (D61). No design yet, so layout and copy are
 * provisional and follow the episode model and element editors. Operations and Super admins draft;
 * a Clinical reviewer who did not draft it approves or returns it; then it is published.
 */
@Component({
  selector: 'sl-context-question-editor',
  imports: [TranslocoPipe, FormsModule, BackLink],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <sl-back-link to="/modules/context-questions" [label]="'admin.questions.back' | transloco" />
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
            {{ 'admin.questions.heading' | transloco: { code: v.code, n: v.version } }}
          } @else {
            {{ 'admin.questions.newTitle' | transloco }}
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
          {{ 'admin.questions.readOnly' | transloco }}
        </p>
      }

      <div class="layout">
        <form class="main" (ngSubmit)="save()">
          <fieldset class="sl-card" [disabled]="!editable()">
            <legend class="sl-section">
              {{ 'admin.questions.basics' | transloco }}
            </legend>
            <label class="sl-field">
              <span>{{ 'admin.questions.code' | transloco }}</span>
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
              <span class="sl-meta">{{ 'admin.questions.codeHint' | transloco }}</span>
            </label>
            <label class="sl-field">
              <span>{{ 'admin.questions.questionEn' | transloco }}</span>
              <input
                class="sl-input"
                name="en"
                maxlength="200"
                [(ngModel)]="form.en"
                (ngModelChange)="touch()"
              />
            </label>
            <label class="sl-field">
              <span>{{ 'admin.questions.questionFr' | transloco }}</span>
              <input
                class="sl-input"
                name="fr"
                maxlength="200"
                [(ngModel)]="form.fr"
                (ngModelChange)="touch()"
              />
            </label>
            <label class="sl-field">
              <span>{{ 'admin.questions.type' | transloco }}</span>
              <select
                class="sl-select"
                name="type"
                [(ngModel)]="form.type"
                (ngModelChange)="typeChanged()"
              >
                @for (t of types; track t) {
                  <option [value]="t">{{ 'admin.questions.types.' + t | transloco }}</option>
                }
              </select>
            </label>
            <label class="sl-field">
              <span>{{ 'admin.questions.scope' | transloco }}</span>
              <select
                class="sl-select"
                name="scope"
                [(ngModel)]="form.scope"
                (ngModelChange)="touch()"
              >
                @for (s of scopes; track s) {
                  <option [value]="s">{{ 'admin.questions.scopes.' + s | transloco }}</option>
                }
              </select>
            </label>
          </fieldset>

          @if (form.type !== 'YES_NO') {
            <fieldset class="sl-card" [disabled]="!editable()">
              <legend class="sl-section">
                {{ 'admin.questions.options' | transloco }}
              </legend>
              <p class="sl-meta">
                {{ 'admin.questions.optionsHint' | transloco }}
              </p>
              @for (o of form.options; track $index; let i = $index) {
                <div class="option">
                  <label class="sl-field">
                    <span>{{ 'admin.questions.optionCode' | transloco }}</span>
                    <input
                      class="sl-input"
                      [name]="'ocode' + i"
                      maxlength="40"
                      pattern="[a-z][a-z0-9_]*"
                      [(ngModel)]="o.code"
                      (ngModelChange)="touch()"
                    />
                  </label>
                  <label class="sl-field">
                    <span>{{ 'admin.questions.optionEn' | transloco }}</span>
                    <input
                      class="sl-input"
                      [name]="'oen' + i"
                      maxlength="60"
                      [(ngModel)]="o.en"
                      (ngModelChange)="touch()"
                    />
                  </label>
                  <label class="sl-field">
                    <span>{{ 'admin.questions.optionFr' | transloco }}</span>
                    <input
                      class="sl-input"
                      [name]="'ofr' + i"
                      maxlength="60"
                      [(ngModel)]="o.fr"
                      (ngModelChange)="touch()"
                    />
                  </label>
                  @if (editable()) {
                    <button
                      type="button"
                      class="sl-button sl-button--danger remove"
                      [attr.aria-label]="
                        'admin.questions.removeOption' | transloco: { code: o.code || i + 1 }
                      "
                      (click)="removeOption(i)"
                    >
                      {{ 'admin.questions.remove' | transloco }}
                    </button>
                  }
                </div>
              }
              @if (editable() && form.options.length < maxOptions) {
                <button type="button" class="sl-button add" (click)="addOption()">
                  {{ 'admin.questions.addOption' | transloco }}
                </button>
              }
            </fieldset>
          }

          @if (editable()) {
            <div class="actions">
              @if (saved()) {
                <span class="sl-meta" role="status">{{ 'admin.questions.saved' | transloco }}</span>
              }
              @if (problem()) {
                <span class="error" role="alert">{{ problem()! | transloco }}</span>
              }
              <button type="submit" class="sl-button" [disabled]="busy() || !validCode()">
                {{ 'admin.questions.save' | transloco }}
              </button>
              @if (version()) {
                <button
                  type="button"
                  class="sl-button sl-button--primary"
                  [disabled]="busy() || dirty() || (version()?.problems?.length ?? 0) > 0"
                  (click)="act('submission')"
                >
                  {{ 'admin.questions.submit' | transloco }}
                </button>
              }
            </div>
          }
        </form>

        <aside class="side">
          <section class="sl-card" aria-labelledby="preview-title">
            <h2 id="preview-title" class="sl-section">
              {{ 'admin.questions.preview.title' | transloco }}
            </h2>
            @for (l of langs; track l) {
              <div class="preview" [attr.lang]="l">
                <span class="sl-meta">{{ l.toUpperCase() }}</span>
                <span class="strong">{{ questionIn(l) }}</span>
                <div class="chips">
                  @for (c of choicesIn(l); track $index) {
                    <span class="sl-pill">{{ c }}</span>
                  }
                </div>
              </div>
            }
          </section>

          @if (version(); as v) {
            @if (v.status === 'DRAFT' || v.status === 'IN_REVIEW') {
              @if ((v.problems?.length ?? 0) > 0) {
                <section class="sl-card" aria-labelledby="problems-title">
                  <h2 id="problems-title" class="sl-section">
                    {{ 'admin.questions.problems.title' | transloco }}
                  </h2>
                  <ul>
                    @for (p of v.problems; track p) {
                      <li>{{ 'admin.questions.problems.' + p | transloco }}</li>
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
                      {{ 'admin.questions.publishHint' | transloco }}
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
                    {{ 'admin.questions.usedBy' | transloco: { n: v.usedBy } }}
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
    .option {
      display: grid;
      grid-template-columns: repeat(3, minmax(0, 1fr)) auto;
      gap: var(--space-8);
      align-items: end;
      padding-bottom: var(--space-12);
      border-bottom: 1px solid var(--line);
    }
    .preview {
      display: flex;
      flex-direction: column;
      gap: var(--space-4);
      align-self: stretch;
      padding-bottom: var(--space-8);
      border-bottom: 1px solid var(--line);
    }
    .preview:last-child {
      border-bottom: 0;
      padding-bottom: 0;
    }
    .chips {
      display: flex;
      flex-wrap: wrap;
      gap: var(--space-4);
    }
    fieldset > p,
    .side p {
      margin: 0;
    }
    .side .sl-field,
    .side .actions {
      align-self: stretch;
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
    .side ul {
      margin: 0;
      padding-left: var(--space-16);
    }
    .reviews li {
      display: flex;
      flex-direction: column;
      margin-bottom: var(--space-8);
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
export class ContextQuestionEditorPage {
  private readonly api = inject(TrackingApi);
  private readonly staff = inject(StaffSession);
  private readonly router = inject(Router);
  private readonly transloco = inject(TranslocoService);

  /** A version id, or "new". */
  readonly id = input.required<string>();
  /** For a new version: the published version to start from. */
  readonly from = input<string>();

  readonly types = QUESTION_TYPES;
  readonly scopes = SCOPES;
  readonly langs: Lang[] = ['en', 'fr'];
  readonly maxOptions = MAX_OPTIONS;
  readonly version = signal<Version | null>(null);
  readonly loaded = signal(false);
  readonly error = signal(false);
  readonly busy = signal(false);
  readonly saved = signal(false);
  readonly dirty = signal(false);
  readonly problem = signal<string | null>(null);
  /** Bumped on every edit, so what reads the form recomputes. */
  private readonly edits = signal(0);

  form: QuestionForm = emptyQuestion();
  comments = '';

  readonly mayDraft = computed(() => canDraft(this.staff.roles()));
  readonly mayReview = computed(() => canReview(this.staff.roles()));
  readonly editable = computed(
    () => this.mayDraft() && (this.version() === null || this.version()?.status === 'DRAFT'),
  );
  readonly validCode = computed(() => {
    this.edits();
    return /^[a-z][a-z0-9_]*$/.test(this.form.code);
  });

  constructor() {
    // The preview shows both languages, whichever the portal is in.
    for (const l of this.langs) this.transloco.load(l).subscribe();
    effect(() => {
      this.id();
      untracked(() => void this.load());
    });
  }

  async load() {
    this.error.set(false);
    try {
      const id = this.id();
      const source = id === 'new' ? this.from() : id;
      if (source) {
        const v = orThrow(
          await this.api.client.GET(
            '/admin/v1/tracking/context-question-versions/{contextQuestionVersionId}',
            { params: { path: { contextQuestionVersionId: source } } },
          ),
        );
        this.form = questionFormOf(v);
        this.version.set(id === 'new' ? null : v);
      } else {
        this.form = emptyQuestion();
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

  /** A choice question starts with two empty choices to fill in. */
  typeChanged() {
    if (this.form.type !== 'YES_NO' && this.form.options.length === 0) {
      this.form.options = [
        { code: '', en: '', fr: '' },
        { code: '', en: '', fr: '' },
      ];
    }
    this.touch();
  }

  addOption() {
    this.form.options = [...this.form.options, { code: '', en: '', fr: '' }];
    this.touch();
  }

  removeOption(i: number) {
    this.form.options = this.form.options.filter((_, n) => n !== i);
    this.touch();
  }

  /** The question as the phone shows it, in one language. */
  questionIn(l: Lang): string {
    this.edits();
    return (l === 'en' ? this.form.en : this.form.fr) || this.form.code;
  }

  /** The answers the phone offers, in one language; every question can be skipped. */
  choicesIn(l: Lang): string[] {
    this.edits();
    const t = (key: string) => this.transloco.translate(`admin.questions.preview.${key}`, {}, l);
    const choices =
      this.form.type === 'YES_NO'
        ? [t('yes'), t('no')]
        : this.form.options.map((o) => (l === 'en' ? o.en : o.fr) || o.code);
    return [...choices, t('skip')];
  }

  state(v: Version): string {
    return stateOf(v.status, v.approvedBy);
  }

  async save() {
    if (!this.validCode()) return;
    this.busy.set(true);
    this.problem.set(null);
    try {
      const body = questionDraftOf(this.form);
      const current = this.version();
      if (current?.id) {
        const v = orThrow(
          await this.api.client.PUT(
            '/admin/v1/tracking/context-question-versions/{contextQuestionVersionId}',
            { params: { path: { contextQuestionVersionId: current.id } }, body },
          ),
        );
        this.version.set(v);
        this.dirty.set(false);
        this.saved.set(true);
      } else {
        const v = orThrow(
          await this.api.client.POST('/admin/v1/tracking/context-question-versions', { body }),
        );
        this.dirty.set(false);
        await this.router.navigate(['/modules/context-questions', v.id]);
      }
    } catch (e) {
      this.problem.set(
        e instanceof ApiProblem && e.status === 409
          ? 'admin.questions.codeTaken'
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
        submission:
          '/admin/v1/tracking/context-question-versions/{contextQuestionVersionId}/submission',
        publication:
          '/admin/v1/tracking/context-question-versions/{contextQuestionVersionId}/publication',
        retirement:
          '/admin/v1/tracking/context-question-versions/{contextQuestionVersionId}/retirement',
      } as const;
      this.version.set(
        orThrow(
          await this.api.client.POST(path[step], {
            params: { path: { contextQuestionVersionId: id } },
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
            '/admin/v1/tracking/context-question-versions/{contextQuestionVersionId}/review',
            {
              params: { path: { contextQuestionVersionId: id } },
              body: { approve, comments: this.comments.trim() || undefined },
            },
          ),
        ),
      );
      this.comments = '';
      if (!approve) this.form = questionFormOf(this.version()!);
    } catch {
      this.problem.set('common.checkConnection');
    } finally {
      this.busy.set(false);
    }
  }

  /** A new version starts from this one's content and takes the next number when saved. */
  async newVersion() {
    await this.router.navigate(['/modules/context-questions', 'new'], {
      queryParams: { from: this.version()?.id },
    });
  }
}
