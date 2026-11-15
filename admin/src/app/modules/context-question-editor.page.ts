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
 * Admin screen 34: one context question version (D61; design/screens/admin/png/
 * ContextQuestionEditor.png). Same layout as the element editor. Operations and Super admins draft;
 * a Clinical reviewer who did not draft it approves or returns it; then it is published.
 */
@Component({
  selector: 'sl-context-question-editor',
  imports: [TranslocoPipe, FormsModule, BackLink, LucideDynamicIcon, VersionSteps, Requirements],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <sl-back-link to="/modules/context-questions" [label]="'admin.questions.back' | transloco" />
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
            <h1 class="sl-title">{{ title() }}</h1>
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
              {{ 'admin.questions.meta' | transloco: { code: v.code, who: v.draftedBy || '' } }}
            </p>
          } @else {
            <p class="sl-head__intro">{{ 'admin.questions.newIntro' | transloco }}</p>
          }
        </div>
        <sl-version-steps [status]="version()?.status" />
      </div>

      @if (!editable() && version(); as v) {
        <div class="sl-notice sl-notice--warning notice" role="status">
          <svg [lucideIcon]="lockIcon" [size]="20" [strokeWidth]="1.8" aria-hidden="true"></svg>
          <p>
            <strong>{{ 'admin.versionLocked.' + v.status | transloco }}</strong>
            {{ 'admin.questions.readOnly' | transloco }}
          </p>
        </div>
      }

      <div class="sl-editor">
        <form class="sl-editor__main" (ngSubmit)="save()">
          <fieldset class="sl-card" [disabled]="!editable()">
            <legend class="sl-section">{{ 'admin.questions.basics' | transloco }}</legend>
            <div class="sl-fields">
              <label class="sl-field sl-field--wide">
                <span>{{ 'admin.questions.questionEn' | transloco }}</span>
                <input
                  class="sl-input"
                  name="en"
                  maxlength="200"
                  lang="en"
                  [(ngModel)]="form.en"
                  (ngModelChange)="touch()"
                />
              </label>
              <label class="sl-field sl-field--wide">
                <span>{{ 'admin.questions.questionFr' | transloco }}</span>
                <input
                  class="sl-input"
                  name="fr"
                  maxlength="200"
                  lang="fr"
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
              <label class="sl-field sl-field--wide">
                <span>{{ 'admin.questions.code' | transloco }}</span>
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
                <span class="sl-hint">{{ 'admin.questions.codeHint' | transloco }}</span>
              </label>
            </div>
          </fieldset>

          @if (form.type !== 'YES_NO') {
            <fieldset class="sl-card" [disabled]="!editable()">
              <legend class="sl-section">{{ 'admin.questions.options' | transloco }}</legend>
              <p class="sl-hint">{{ 'admin.questions.optionsHint' | transloco }}</p>
              <div
                class="sl-rows"
                role="group"
                [attr.aria-label]="'admin.questions.options' | transloco"
              >
                <div class="sl-rows__head" aria-hidden="true">
                  <span>{{ 'admin.questions.optionEn' | transloco }}</span>
                  <span>{{ 'admin.questions.optionFr' | transloco }}</span>
                  <span>{{ 'admin.questions.optionCode' | transloco }}</span>
                  <span></span>
                </div>
                @for (o of form.options; track $index; let i = $index) {
                  <div class="sl-rows__row">
                    <input
                      class="sl-input"
                      lang="en"
                      [name]="'oen' + i"
                      maxlength="60"
                      [attr.aria-label]="'admin.questions.optionEnN' | transloco: { n: i + 1 }"
                      [(ngModel)]="o.en"
                      (ngModelChange)="touch()"
                    />
                    <input
                      class="sl-input"
                      lang="fr"
                      [name]="'ofr' + i"
                      maxlength="60"
                      [attr.aria-label]="'admin.questions.optionFrN' | transloco: { n: i + 1 }"
                      [(ngModel)]="o.fr"
                      (ngModelChange)="touch()"
                    />
                    <input
                      class="sl-input code"
                      [name]="'ocode' + i"
                      maxlength="40"
                      pattern="[a-z][a-z0-9_]*"
                      [attr.aria-label]="'admin.questions.optionCodeN' | transloco: { n: i + 1 }"
                      [(ngModel)]="o.code"
                      (ngModelChange)="touch()"
                    />
                    @if (editable()) {
                      <button
                        type="button"
                        class="sl-link-button sl-link-button--danger"
                        [attr.aria-label]="
                          'admin.questions.removeOption' | transloco: { code: o.code || i + 1 }
                        "
                        (click)="removeOption(i)"
                      >
                        {{ 'admin.questions.remove' | transloco }}
                      </button>
                    } @else {
                      <span></span>
                    }
                  </div>
                }
                @if (editable() && form.options.length < maxOptions) {
                  <button type="button" class="sl-button sl-rows__add" (click)="addOption()">
                    {{ 'admin.questions.addOption' | transloco }}
                  </button>
                }
              </div>
            </fieldset>
          }

          @if (editable()) {
            <div class="sl-actions">
              @if (saved()) {
                <span class="sl-status" role="status">{{
                  'admin.questions.saved' | transloco
                }}</span>
              }
              @if (problem()) {
                <span class="sl-error" role="alert">{{ problem()! | transloco }}</span>
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

        <aside class="sl-editor__rail">
          <section class="sl-card" aria-labelledby="preview-title">
            <h2 id="preview-title" class="sl-section">
              {{ 'admin.questions.preview.title' | transloco }}
            </h2>
            <p class="sl-hint">{{ 'admin.questions.preview.where.' + form.scope | transloco }}</p>
            @for (l of langs; track l) {
              <div class="sl-phone" [attr.lang]="l">
                <span class="sl-phone__lang">{{
                  'admin.elements.previewIn.' + l | transloco
                }}</span>
                <p class="sl-phone__title">{{ questionIn(l) }}</p>
                <div class="sl-phone__chips">
                  @for (c of choicesIn(l); track $index) {
                    <span class="sl-phone__chip" [class.sl-phone__chip--empty]="!c">{{ c }}</span>
                  }
                </div>
                <p class="sl-phone__help">{{ skipIn(l) }}</p>
              </div>
            }
          </section>

          @if (editable() || version()?.status === 'IN_REVIEW') {
            <sl-requirements
              [codes]="requirementCodes()"
              [problems]="version()?.problems ?? null"
              prefix="admin.questions.problems."
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
                    <p class="review-line">
                      {{ 'admin.elements.review.approved' | transloco: { who: v.approvedBy } }}
                    </p>
                    @if (mayDraft()) {
                      <p class="sl-hint">{{ 'admin.questions.publishHint' | transloco }}</p>
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
                  @if ((v.usedBy ?? 0) > 0) {
                    <p class="sl-hint">
                      {{ 'admin.questions.usedBy' | transloco: { n: v.usedBy } }}
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
  protected readonly lockIcon = LucideLock;
  private readonly lang = signal(this.transloco.getActiveLang());

  /** The question in the portal's language names the page; a new one says so. */
  readonly title = computed(() => {
    this.edits();
    const v = this.version();
    const text = this.lang() === 'fr' ? this.form.fr || this.form.en : this.form.en || this.form.fr;
    if (!v && !text) return this.transloco.translate('admin.questions.newTitle');
    return text || v?.code || '';
  });

  /** What a version must meet; keeping the answer type only matters once one is published. */
  readonly requirementCodes = computed(() =>
    (this.version()?.version ?? 1) > 1 ? ['question', 'options', 'type'] : ['question', 'options'],
  );

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
    this.transloco.langChanges$.subscribe((l) => this.lang.set(l));
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

  /** The answers the phone offers, in one language; an empty label shows as a gap to fill. */
  choicesIn(l: Lang): string[] {
    this.edits();
    const t = (key: string) => this.transloco.translate(`admin.questions.preview.${key}`, {}, l);
    return this.form.type === 'YES_NO'
      ? [t('yes'), t('no')]
      : this.form.options.map((o) => (l === 'en' ? o.en : o.fr));
  }

  /** Every question can be left unanswered; the phone says so under it. */
  skipIn(l: Lang): string {
    const key = this.form.type === 'MANY_CHOICES' ? 'optionalMany' : 'optional';
    return this.transloco.translate(`admin.questions.preview.${key}`, {}, l);
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
