import {
  ChangeDetectionStrategy,
  Component,
  computed,
  type ElementRef,
  inject,
  input,
  signal,
  viewChild,
} from '@angular/core';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { TranslocoPipe, TranslocoService } from '@jsverse/transloco';

import { AiApi, type AiSchemas } from '../api/ai-api';
import { ApiProblem, orThrow } from '../api/core-api';
import { TrackingApi } from '../api/tracking-api';
import { StaffSession } from '../auth/staff-session';
import { dateTime } from './format';

type Summary = AiSchemas['ReviewedListSummary'];
type Detail = AiSchemas['ReviewedListDetail'];
type Version = AiSchemas['ReviewedListVersion'];

/**
 * Reviewed lists of AI or Tracking (D61): guardrail words, safety phrases, evaluation sets, the
 * corpus, the companion's wording, the rule wording. A list names who drafts and who signs off; a
 * different clinician signs a draft off, once its check passed. Entries are edited as JSON.
 */
@Component({
  selector: 'sl-reviewed-lists',
  imports: [TranslocoPipe, FormsModule, RouterLink],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <a [routerLink]="back()" class="sl-meta">{{ 'admin.lists.back' | transloco }}</a>
    <h1 class="sl-title">{{ 'admin.lists.title.' + service() | transloco }}</h1>
    <p class="sl-muted subtitle">{{ 'admin.lists.subtitle' | transloco }}</p>

    @if (error()) {
      <div class="sl-card">
        <p>{{ 'common.checkConnection' | transloco }}</p>
        <button class="sl-button" type="button" (click)="load()">
          {{ 'common.tryAgain' | transloco }}
        </button>
      </div>
    } @else if (lists(); as all) {
      <section class="sl-card" aria-labelledby="lists-title">
        <h2 id="lists-title" class="section">{{ 'admin.lists.all' | transloco }}</h2>
        <ul class="rows">
          @for (l of all; track l.code) {
            <li class="row">
              <div>
                <p class="label">{{ l.description }}</p>
                <p class="sl-meta">
                  {{ l.code }} ·
                  @if (l.liveVersion) {
                    {{ 'admin.lists.live' | transloco: { version: l.liveVersion } }}
                  } @else {
                    {{ 'admin.lists.fromCode' | transloco }}
                  }
                  @if (l.drafts) {
                    · {{ 'admin.lists.drafts' | transloco: { n: l.drafts } }}
                  }
                </p>
              </div>
              <button type="button" class="sl-button" (click)="open(l)">
                {{ 'admin.lists.open' | transloco }}
              </button>
            </li>
          }
        </ul>
      </section>

      @if (detail(); as d) {
        <section class="sl-card" aria-labelledby="detail-title">
          <div class="head">
            <h2 id="detail-title" class="section">{{ d.description }}</h2>
            @if (canDraft()) {
              <button type="button" class="sl-button" (click)="askDraft(d)">
                {{ 'admin.lists.draft' | transloco }}
              </button>
            }
          </div>
          <h3 class="group">{{ 'admin.lists.inForce' | transloco }}</h3>
          <pre class="entries">{{ json(d.inForce) }}</pre>
          <h3 class="group">{{ 'admin.lists.versions' | transloco }}</h3>
          @if (!d.versions.length) {
            <p class="sl-muted">{{ 'admin.lists.noVersions' | transloco }}</p>
          }
          <ul class="rows">
            @for (v of d.versions; track v.version) {
              <li class="row">
                <div>
                  <p class="label">
                    v{{ v.version }} · {{ 'admin.lists.status.' + v.status | transloco }}
                  </p>
                  <p class="sl-meta">
                    {{ v.note }} · {{ v.draftedByName }} · {{ when(v.draftedAt) }}
                    @if (v.decidedByName) {
                      · {{ 'admin.lists.decidedBy' | transloco: { name: v.decidedByName } }}
                    }
                    @if (v.checkResult) {
                      · {{ v.checkResult }}
                    }
                  </p>
                </div>
                @if (v.status === 'DRAFT' && canApprove()) {
                  <span class="buttons">
                    <button type="button" class="sl-button" (click)="check(v)">
                      {{ 'admin.lists.check' | transloco }}
                    </button>
                    <button
                      type="button"
                      class="sl-button sl-button--primary"
                      [disabled]="v.draftedBy === me()"
                      (click)="decide(v, 'sign-off')"
                    >
                      {{ 'admin.lists.signOff' | transloco }}
                    </button>
                    <button
                      type="button"
                      class="sl-button"
                      [disabled]="v.draftedBy === me()"
                      (click)="decide(v, 'decline')"
                    >
                      {{ 'admin.lists.decline' | transloco }}
                    </button>
                  </span>
                }
              </li>
            }
          </ul>
          @if (notice()) {
            <p class="sl-meta" role="status">{{ notice() }}</p>
          }
        </section>
      }
    } @else {
      <p class="sl-card sl-muted">{{ 'common.loading' | transloco }}</p>
    }

    <dialog #draftDialog class="sl-dialog" aria-labelledby="draft-title">
      <h2 id="draft-title" class="section">{{ 'admin.lists.draft' | transloco }}</h2>
      <p class="sl-muted">{{ 'admin.lists.draftHelp' | transloco }}</p>
      <label class="sl-field">
        <span>{{ 'admin.lists.entries' | transloco }}</span>
        <textarea class="sl-textarea mono" rows="16" [(ngModel)]="entries"></textarea>
      </label>
      <label class="sl-field">
        <span>{{ 'admin.lists.note' | transloco }}</span>
        <textarea class="sl-textarea" rows="2" maxlength="500" [(ngModel)]="note"></textarea>
      </label>
      @if (problem()) {
        <p class="sl-meta problem" role="alert">{{ problem() }}</p>
      }
      <div class="actions">
        <button type="button" class="sl-button" (click)="draftDialog.close()">
          {{ 'admin.common.cancel' | transloco }}
        </button>
        <button
          type="button"
          class="sl-button sl-button--primary"
          [disabled]="busy() || !note().trim()"
          (click)="saveDraft()"
        >
          {{ 'admin.common.save' | transloco }}
        </button>
      </div>
    </dialog>
  `,
  styles: `
    .subtitle {
      margin: var(--space-4) 0 var(--space-20);
      max-width: 72ch;
    }
    .sl-card + .sl-card {
      margin-top: var(--space-16);
    }
    .head {
      display: flex;
      justify-content: space-between;
      align-items: center;
      gap: var(--space-12);
    }
    .section {
      margin: 0 0 var(--space-12);
      font-size: var(--type-title-md-size);
    }
    .group {
      margin: var(--space-16) 0 var(--space-8);
      font-size: var(--type-body-admin-size);
    }
    .rows {
      list-style: none;
      margin: 0;
      padding: 0;
    }
    .row {
      display: grid;
      grid-template-columns: minmax(0, 1fr) auto;
      gap: var(--space-12);
      align-items: center;
      min-height: 44px;
      padding: var(--space-8) 0;
      border-top: 1px solid var(--line);
    }
    .row p {
      margin: 0;
    }
    .label {
      font-weight: 600;
    }
    .buttons {
      display: flex;
      gap: var(--space-8);
    }
    .entries,
    .mono {
      font-family: var(--font-mono, monospace);
    }
    .entries {
      max-height: 24rem;
      overflow: auto;
      border: 1px solid var(--line);
      padding: var(--space-12);
      white-space: pre-wrap;
    }
    .actions {
      display: flex;
      justify-content: flex-end;
      gap: var(--space-8);
      margin-top: var(--space-12);
    }
    .problem {
      color: var(--danger);
    }
  `,
})
export class ReviewedListsPage {
  private readonly ai = inject(AiApi);
  private readonly tracking = inject(TrackingApi);
  private readonly transloco = inject(TranslocoService);
  private readonly staff = inject(StaffSession);
  private readonly draftDialog = viewChild<ElementRef<HTMLDialogElement>>('draftDialog');

  /** Which service's lists: set by the route. */
  readonly service = input<'ai' | 'tracking'>('ai');
  readonly back = computed(() => (this.service() === 'ai' ? '/ai' : '/modules'));
  readonly lists = signal<Summary[] | null>(null);
  readonly selected = signal<Summary | null>(null);
  readonly detail = signal<Detail | null>(null);
  readonly error = signal(false);
  readonly busy = signal(false);
  readonly problem = signal<string | null>(null);
  readonly notice = signal<string | null>(null);
  readonly entries = signal('');
  readonly note = signal('');
  readonly me = computed(() => this.staff.id());
  readonly canDraft = computed(() =>
    (this.selected()?.drafters ?? []).some((r) => this.staff.roles().includes(r as never)),
  );
  readonly canApprove = computed(() =>
    (this.selected()?.approvers ?? []).some((r) => this.staff.roles().includes(r as never)),
  );
  private readonly lang = signal(this.transloco.getActiveLang());

  constructor() {
    this.transloco.langChanges$.subscribe((lang) => this.lang.set(lang));
    queueMicrotask(() => void this.load());
  }

  /** Both services serve the same lists paths. */
  private client() {
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    return (this.service() === 'ai' ? this.ai.client : this.tracking.client) as any;
  }

  async load() {
    this.error.set(false);
    try {
      this.lists.set(orThrow(await this.client().GET('/admin/v1/lists')) as Summary[]);
      const open = this.selected();
      if (open) await this.open(open);
    } catch {
      this.error.set(true);
    }
  }

  async open(l: Summary) {
    this.selected.set(l);
    this.notice.set(null);
    try {
      this.detail.set(
        orThrow(
          await this.client().GET('/admin/v1/lists/{code}', { params: { path: { code: l.code } } }),
        ) as Detail,
      );
    } catch {
      this.error.set(true);
    }
  }

  json(value: unknown): string {
    return JSON.stringify(value, null, 2);
  }

  when(iso: string | undefined): string {
    return dateTime(iso, this.lang());
  }

  askDraft(d: Detail) {
    this.entries.set(this.json(d.inForce));
    this.note.set('');
    this.problem.set(null);
    this.draftDialog()?.nativeElement.showModal();
  }

  async saveDraft() {
    const l = this.selected();
    if (!l) return;
    let entries: unknown;
    try {
      entries = JSON.parse(this.entries());
    } catch {
      this.problem.set(this.transloco.translate('admin.senders.notJson'));
      return;
    }
    this.busy.set(true);
    this.problem.set(null);
    try {
      orThrow(
        await this.client().POST('/admin/v1/lists/{code}/drafts', {
          params: { path: { code: l.code } },
          body: { entries, note: this.note().trim() },
        }),
      );
      this.draftDialog()?.nativeElement.close();
      await this.load();
    } catch (e) {
      this.problem.set(this.titleOf(e));
    } finally {
      this.busy.set(false);
    }
  }

  async check(v: Version) {
    try {
      const result = orThrow(
        await this.client().POST('/admin/v1/lists/{code}/drafts/{version}/check', {
          params: { path: { code: v.list, version: v.version } },
        }),
      ) as { passed: boolean; problem?: string };
      this.notice.set(
        result.passed
          ? this.transloco.translate('admin.lists.checkPassed')
          : `${this.transloco.translate('admin.lists.checkFailed')} ${result.problem ?? ''}`,
      );
    } catch (e) {
      this.notice.set(this.titleOf(e));
    }
  }

  async decide(v: Version, decision: 'sign-off' | 'decline') {
    try {
      const path =
        decision === 'sign-off'
          ? '/admin/v1/lists/{code}/drafts/{version}/sign-off'
          : '/admin/v1/lists/{code}/drafts/{version}/decline';
      orThrow(
        await this.client().POST(path, { params: { path: { code: v.list, version: v.version } } }),
      );
      await this.load();
    } catch (e) {
      this.notice.set(this.titleOf(e));
    }
  }

  private titleOf(e: unknown): string {
    return e instanceof ApiProblem && e.title
      ? e.title
      : this.transloco.translate('common.checkConnection');
  }
}
