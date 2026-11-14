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
import { RouterLink } from '@angular/router';
import { TranslocoPipe, TranslocoService } from '@jsverse/transloco';

import { orThrow } from '../api/core-api';
import { TrackingApi, type TrackingSchemas } from '../api/tracking-api';
import { StaffSession } from '../auth/staff-session';
import { canDraft, canReview, textIn } from './module-shared';
import { BackLink } from '../shared/back-link';

type Version = TrackingSchemas['ModuleVersion'];
type Rule = TrackingSchemas['Rule'];
type TestResult = TrackingSchemas['TestResult'];

interface Change {
  part: string;
  before: string;
  after: string;
}

const CHECKS = ['thresholds', 'wording', 'languages', 'specialty'] as const;

/**
 * Admin screen 25: clinical review (ModuleReview.dc.html; D51). A Clinical reviewer who did not
 * draft the version approves it, or returns it with comments; once approved, Operations or a
 * Super admin publishes it.
 */
@Component({
  selector: 'sl-module-review',
  imports: [BackLink, TranslocoPipe, FormsModule, RouterLink],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <sl-back-link to="/modules" [label]="'admin.modules.title' | transloco" />
    @if (error()) {
      <div class="sl-card">
        <p>{{ 'common.checkConnection' | transloco }}</p>
        <button class="sl-button" type="button" (click)="load()">
          {{ 'common.tryAgain' | transloco }}
        </button>
      </div>
    } @else if (draft(); as v) {
      <h1 class="sl-title">{{ 'admin.modules.review.title' | transloco }}</h1>
      <p class="heading">
        {{ 'admin.modules.review.heading' | transloco: { name: name(v), n: v.number } }}
      </p>
      <p class="sl-meta">
        {{ 'admin.modules.review.submitted' | transloco: { who: v.draftedBy, me: me() } }}
      </p>

      @if (v.status !== 'IN_REVIEW') {
        <p class="sl-card">{{ 'admin.modules.review.notInReview' | transloco }}</p>
      } @else if (v.approvedBy) {
        <div class="sl-card approved">
          <p>{{ 'admin.modules.review.approved' | transloco: { who: v.approvedBy } }}</p>
          @if (mayPublish()) {
            <button
              type="button"
              class="sl-button sl-button--primary"
              [disabled]="busy()"
              (click)="publish()"
            >
              {{ 'admin.modules.review.publish' | transloco: { n: v.number } }}
            </button>
          }
        </div>
      }

      <div class="layout">
        <div class="main">
          <section class="sl-card table-card" aria-labelledby="changes-title">
            <h2 id="changes-title" class="section">
              @if (published(); as p) {
                {{ 'admin.modules.review.changed' | transloco: { n: p.number } }}
              } @else {
                {{ 'admin.modules.review.changedFirst' | transloco }}
              }
            </h2>
            <table class="sl-table">
              <thead>
                <tr>
                  <th scope="col">{{ 'admin.modules.review.part' | transloco }}</th>
                  @if (published(); as p) {
                    <th scope="col">
                      {{ 'admin.modules.review.before' | transloco: { n: p.number } }}
                    </th>
                  }
                  <th scope="col">
                    {{ 'admin.modules.review.after' | transloco: { n: v.number } }}
                  </th>
                </tr>
              </thead>
              <tbody>
                @for (c of changes(); track c.part) {
                  <tr>
                    <th scope="row">{{ c.part }}</th>
                    @if (published()) {
                      <td>{{ c.before }}</td>
                    }
                    <td>{{ c.after }}</td>
                  </tr>
                } @empty {
                  <tr>
                    <td [attr.colspan]="published() ? 3 : 2">
                      {{ 'admin.modules.review.noChange' | transloco }}
                    </td>
                  </tr>
                }
              </tbody>
            </table>
          </section>

          <section class="sl-card" aria-labelledby="effect-title" aria-live="polite">
            <h2 id="effect-title" class="section">
              {{ 'admin.modules.review.effect' | transloco }}
            </h2>
            @if (effectText(); as text) {
              <p>{{ text }}</p>
            }
            <a class="sl-button" [routerLink]="['/modules/versions', v.id]">
              {{ 'admin.modules.review.openTestRun' | transloco }}
            </a>
          </section>
        </div>

        @if (mayReview() && v.status === 'IN_REVIEW' && !v.approvedBy) {
          <aside class="sl-card" aria-labelledby="checklist-title">
            <h2 id="checklist-title" class="section">
              {{ 'admin.modules.review.checklist' | transloco }}
            </h2>
            @for (c of checks; track c) {
              <label class="check">
                <input
                  type="checkbox"
                  [checked]="ticked().has(c)"
                  [disabled]="!v.canApprove"
                  (change)="tick(c)"
                />
                {{ 'admin.modules.review.check.' + c | transloco }}
              </label>
            }
            <label class="sl-field">
              <span>{{ 'admin.modules.review.comments' | transloco }}</span>
              <textarea
                class="sl-textarea"
                rows="4"
                maxlength="4000"
                [disabled]="!v.canApprove"
                [(ngModel)]="comments"
              ></textarea>
            </label>
            @if (!v.canApprove) {
              <p class="sl-meta">{{ 'admin.modules.review.ownDraft' | transloco }}</p>
            }
            @if (failed()) {
              <p class="error" role="alert">{{ 'common.checkConnection' | transloco }}</p>
            }
            <div class="actions">
              <button
                type="button"
                class="sl-button"
                [disabled]="busy() || !v.canApprove || !comments().trim()"
                (click)="decide(false)"
              >
                {{ 'admin.modules.review.return' | transloco }}
              </button>
              <button
                type="button"
                class="sl-button sl-button--primary"
                [disabled]="busy() || !v.canApprove || ticked().size < checks.length"
                (click)="decide(true)"
              >
                {{ 'admin.modules.review.approve' | transloco: { n: v.number } }}
              </button>
            </div>
          </aside>
        }
      </div>
    } @else {
      <p class="sl-muted">{{ 'common.loading' | transloco }}</p>
    }
  `,
  styles: `
    .heading {
      font-weight: 600;
      margin: var(--space-4) 0;
    }
    .layout {
      display: grid;
      grid-template-columns: minmax(0, 1fr) 22rem;
      gap: var(--space-20);
      align-items: start;
      margin-top: var(--space-20);
    }
    .main {
      display: grid;
      gap: var(--space-16);
    }
    .section {
      font-size: 1rem;
      margin: 0 0 var(--space-12);
    }
    .table-card {
      overflow-x: auto;
    }
    .check {
      display: flex;
      align-items: center;
      gap: var(--space-8);
      min-height: 44px;
    }
    .approved {
      display: flex;
      align-items: center;
      justify-content: space-between;
      gap: var(--space-12);
      margin-top: var(--space-16);
    }
    .actions {
      display: flex;
      justify-content: end;
      flex-wrap: wrap;
      gap: var(--space-8);
      margin-top: var(--space-12);
    }
    .error {
      color: var(--danger);
    }
  `,
})
export class ModuleReviewPage {
  private readonly api = inject(TrackingApi);
  private readonly staff = inject(StaffSession);
  private readonly transloco = inject(TranslocoService);

  readonly id = input.required<string>();
  readonly checks = CHECKS;
  readonly draft = signal<Version | null>(null);
  readonly published = signal<Version | null>(null);
  readonly draftResults = signal<TestResult[]>([]);
  readonly publishedResults = signal<TestResult[]>([]);
  readonly ticked = signal(new Set<string>());
  readonly comments = signal('');
  readonly error = signal(false);
  readonly failed = signal(false);
  readonly busy = signal(false);
  private readonly lang = signal(this.transloco.getActiveLang());

  readonly me = computed(() => this.staff.name());
  readonly mayReview = computed(() => canReview(this.staff.roles()));
  readonly mayPublish = computed(() => canDraft(this.staff.roles()));

  readonly changes = computed<Change[]>(() => {
    const v = this.draft();
    if (!v) return [];
    const p = this.published();
    const t = (key: string) => this.transloco.translate(`admin.modules.review.fields.${key}`);
    const none = this.transloco.translate('admin.modules.review.none');
    const rows: Change[] = [];
    const add = (part: string, before: string, after: string) => {
      if (!p || before !== after) rows.push({ part, before: before || none, after: after || none });
    };
    const lang = this.lang();
    add(t('name'), textIn(p?.name, lang), textIn(v.name, lang));
    add(t('description'), textIn(p?.description, lang), textIn(v.description, lang));
    add(t('audience'), textIn(p?.audience, lang), textIn(v.audience, lang));
    add(t('imageAlt'), textIn(p?.imageAlt, lang), textIn(v.imageAlt, lang));
    if (p && p.imageId !== v.imageId) {
      rows.push({
        part: t('image'),
        before: '',
        after: this.transloco.translate('admin.modules.review.newImage'),
      });
    }
    add(t('minAge'), String(p?.minAge ?? ''), String(v.minAge ?? ''));
    add(
      t('reminder'),
      (p?.reminderDefault ?? '').slice(0, 5),
      (v.reminderDefault ?? '').slice(0, 5),
    );
    add(
      t('question'),
      textIn(p?.enrolmentQuestion?.question, lang),
      textIn(v.enrolmentQuestion?.question, lang),
    );
    add(t('questions'), (p?.questions ?? []).join(', '), (v.questions ?? []).join(', '));
    add(t('elements'), elementsText(p), elementsText(v));
    add(t('groups'), groupsText(p, lang), groupsText(v, lang));
    add(t('presentation'), presentationText(p, lang), presentationText(v, lang));
    const before = new Map((p?.rules ?? []).map((r) => [r.code, r]));
    for (const r of v.rules ?? []) {
      const old = before.get(r.code);
      for (const [part, text] of ruleParts(r, lang)) {
        add(
          this.transloco.translate('admin.modules.review.ruleField', {
            code: r.code,
            part: this.transloco.translate(`admin.modules.review.ruleParts.${part}`),
          }),
          old ? (ruleParts(old, lang).get(part) ?? '') : '',
          text,
        );
      }
    }
    return rows;
  });

  readonly effectText = computed(() => {
    const v = this.draft();
    const d = this.draftResults();
    if (!v || d.length === 0) return '';
    const total = new Set(d.map((r) => r.history)).size;
    const fires = (results: TestResult[]) =>
      new Set(results.filter((r) => r.fires).map((r) => r.history)).size;
    const p = this.published();
    if (p && this.publishedResults().length) {
      return this.transloco.translate('admin.modules.review.effectLine', {
        a: p.number,
        x: fires(this.publishedResults()),
        b: v.number,
        y: fires(d),
        total,
      });
    }
    return this.transloco.translate('admin.modules.review.effectOne', {
      b: v.number,
      y: fires(d),
      total,
    });
  });

  constructor() {
    this.transloco.langChanges$.subscribe((lang) => this.lang.set(lang));
    effect(() => {
      this.id();
      untracked(() => void this.load());
    });
  }

  name(v: Version): string {
    return textIn(v.name, this.lang()) || v.moduleCode || '';
  }

  tick(check: string) {
    this.ticked.update((set) => {
      const next = new Set(set);
      if (next.has(check)) next.delete(check);
      else next.add(check);
      return next;
    });
  }

  async load() {
    this.error.set(false);
    try {
      const v = await this.version(this.id());
      this.draft.set(v);
      this.draftResults.set(await this.testRun(v.id!));
      const modules = orThrow(await this.api.client.GET('/admin/v1/tracking/modules'));
      const live = modules
        .find((m) => m.id === v.moduleId)
        ?.versions?.find((x) => x.status === 'PUBLISHED' && x.id !== v.id);
      if (live?.id) {
        this.published.set(await this.version(live.id));
        this.publishedResults.set(await this.testRun(live.id));
      } else {
        this.published.set(null);
      }
    } catch {
      this.error.set(true);
    }
  }

  async decide(approve: boolean) {
    const v = this.draft();
    if (!v) return;
    this.busy.set(true);
    this.failed.set(false);
    try {
      this.draft.set(
        orThrow(
          await this.api.client.POST('/admin/v1/tracking/versions/{versionId}/review', {
            params: { path: { versionId: v.id! } },
            body: {
              approve,
              ...(this.comments().trim() ? { comments: this.comments().trim() } : {}),
            },
          }),
        ),
      );
    } catch {
      this.failed.set(true);
    } finally {
      this.busy.set(false);
    }
  }

  async publish() {
    const v = this.draft();
    if (!v) return;
    this.busy.set(true);
    try {
      this.draft.set(
        orThrow(
          await this.api.client.POST('/admin/v1/tracking/versions/{versionId}/publication', {
            params: { path: { versionId: v.id! } },
          }),
        ),
      );
    } catch {
      this.failed.set(true);
    } finally {
      this.busy.set(false);
    }
  }

  private async version(id: string): Promise<Version> {
    return orThrow(
      await this.api.client.GET('/admin/v1/tracking/versions/{versionId}', {
        params: { path: { versionId: id } },
      }),
    );
  }

  private async testRun(id: string): Promise<TestResult[]> {
    try {
      return orThrow(
        await this.api.client.POST('/admin/v1/tracking/versions/{versionId}/test-run', {
          params: { path: { versionId: id } },
        }),
      );
    } catch {
      return [];
    }
  }
}

function elementsText(v: Version | null): string {
  return (v?.elements ?? []).map((b) => b.code).join(', ');
}

function groupsText(v: Version | null, lang: string): string {
  return (v?.elements ?? [])
    .filter((b) => b.group)
    .map((b) => `${b.code}: ${textIn(b.group!.labels, lang)}`)
    .join(', ');
}

/** What the app shows for the module, as plain text, so two versions can be compared (D61). */
function presentationText(v: Version | null, lang: string): string {
  const p = v?.presentation;
  if (!p) return '';
  return [
    p.title ? textIn(p.title, lang) : '',
    p.saveLabel ? textIn(p.saveLabel, lang) : '',
    p.episode ? `${p.episode.model}: ${textIn(p.episode.dayLabel, lang)}` : '',
    p.episode?.historyTitle ? textIn(p.episode.historyTitle, lang) : '',
    (p.trends ?? []).join(', '),
    p.quickLog ?? '',
    p.quickLogTitle ? textIn(p.quickLogTitle, lang) : '',
  ]
    .filter(Boolean)
    .join(' · ');
}

/** A rule's reviewable parts as plain text, so two versions can be compared. */
function ruleParts(r: Rule, lang: string): Map<string, string> {
  const conditions = (r.conditions ?? [])
    .map((c) =>
      [
        c.element ?? c.signal,
        c.operator,
        c.comparison,
        JSON.stringify(c.threshold),
        c.mode,
        c.count,
        c.window,
        c.minData,
      ]
        .filter((x) => x !== undefined && x !== null)
        .join(' '),
    )
    .join('; ');
  return new Map([
    ['conditions', conditions],
    [
      'unless',
      (r.unless ?? [])
        .map((u) =>
          u.question ? `${u.question} = ${String(u.equals ?? u.includes)}` : (u.field ?? ''),
        )
        .join(', '),
    ],
    ['outcome', [r.outcome, ...(r.specialtyCodes ?? [])].join(' ')],
    ['message', lang === 'fr' ? r.messageFr : r.messageEn],
    ['cooldown', String(r.cooldownDays)],
  ]);
}
