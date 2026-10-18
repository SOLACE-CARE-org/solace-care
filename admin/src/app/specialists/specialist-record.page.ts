import {
  ChangeDetectionStrategy,
  Component,
  type ElementRef,
  computed,
  effect,
  inject,
  input,
  signal,
  viewChild,
} from '@angular/core';
import { Router } from '@angular/router';
import { TranslocoPipe, TranslocoService } from '@jsverse/transloco';

import { ApiProblem, CoreApi, type CoreSchemas, orThrow } from '../api/core-api';
import { StaffSession } from '../auth/staff-session';
import { calendarDate, capitalised, initials, languageName, shortDate } from '../shared/format';

type RecordView = CoreSchemas['ApplicationReview'];
type Summary = CoreSchemas['SpecialistSummary'];
type Tab = 'upcoming' | 'past' | 'earnings' | 'reviews' | 'documents' | 'history';

const TABS: Tab[] = ['upcoming', 'past', 'earnings', 'reviews', 'documents', 'history'];

/**
 * Admin screen 6: an approved specialist's profile, status and history (SpecialistRecord.dc.html).
 * Sessions, earnings and reviews fill in as those epics ship. Edit profile and re-verification
 * arrive with profile editing.
 */
@Component({
  selector: 'sl-specialist-record',
  imports: [TranslocoPipe],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <input
      class="sl-input search"
      type="search"
      [attr.aria-label]="'admin.specialists.record.search' | transloco"
      [placeholder]="'admin.specialists.record.search' | transloco"
      [value]="query()"
      (input)="search(value($event))"
    />
    @if (query().trim().length >= 2) {
      <ul class="sl-card results" aria-live="polite">
        @for (s of results(); track s.id) {
          <li>
            <button type="button" class="result" (click)="choose(s)">
              <strong>{{ s.fullName }}</strong>
              <span class="sl-muted">{{ s.professionLabel }} · {{ s.licenceNumber }}</span>
              <span [class]="statusClass(s.status)">{{
                'admin.specialists.status.' + s.status | transloco
              }}</span>
            </button>
          </li>
        } @empty {
          <li class="sl-muted none">
            {{ 'admin.specialists.record.noResults' | transloco }}
          </li>
        }
      </ul>
    }

    @if (record(); as r) {
      <header class="head">
        <span class="avatar" aria-hidden="true">{{ initialsOf(r.fullName) }}</span>
        <div class="who">
          <h1 class="sl-title">{{ r.fullName }}</h1>
          <p class="sl-muted">{{ meta(r) }}</p>
        </div>
        <span [class]="statusClass(r.status)">{{
          'admin.specialists.status.' + r.status | transloco
        }}</span>
        <div class="actions">
          @if (r.status === 'APPROVED' && canChange()) {
            <button
              class="sl-button sl-button--danger"
              type="button"
              [disabled]="busy()"
              (click)="askSuspend()"
            >
              {{ 'admin.specialists.record.suspend' | transloco }}
            </button>
          }
          @if (r.status === 'SUSPENDED' && superAdmin()) {
            <button
              class="sl-button sl-button--primary"
              type="button"
              [disabled]="busy()"
              (click)="reinstate()"
            >
              {{ 'admin.specialists.record.reinstate' | transloco }}
            </button>
          }
        </div>
      </header>
      @if (error()) {
        <p class="error" role="alert">{{ error() }}</p>
      }

      <div class="stats">
        <div class="sl-card stat">
          <span class="sl-muted">{{ 'admin.specialists.record.sessions' | transloco }}</span>
          <strong>—</strong>
        </div>
        <div class="sl-card stat">
          <span class="sl-muted">{{ 'admin.specialists.record.rating' | transloco }}</span>
          <strong>—</strong>
        </div>
        <div class="sl-card stat">
          <span class="sl-muted">{{ 'admin.specialists.record.noShows' | transloco }}</span>
          <strong>—</strong>
          <span class="sl-meta">{{ 'admin.specialists.record.last90' | transloco }}</span>
        </div>
        <div class="sl-card stat">
          <span class="sl-muted">{{ 'admin.specialists.record.licenceValid' | transloco }}</span>
          <strong>{{ day(r.licence.validUntil) || '—' }}</strong>
          <span class="sl-meta">{{ 'admin.specialists.record.reminder' | transloco }}</span>
        </div>
      </div>

      <div class="tabs" role="tablist">
        @for (t of tabs; track t) {
          <button role="tab" type="button" [attr.aria-selected]="t === tab()" (click)="tab.set(t)">
            {{ 'admin.specialists.record.tab_' + t | transloco }}
          </button>
        }
      </div>

      <section class="sl-card panel" role="tabpanel">
        @switch (tab()) {
          @case ('documents') {
            <ul class="list">
              @for (d of r.documents; track d.id) {
                <li>
                  <button type="button" class="file" (click)="openDocument(r.id, d.id)">
                    {{ 'admin.specialists.review.doc_' + d.kind | transloco }} ·
                    {{ d.fileName }}
                  </button>
                  <span class="sl-meta">{{ short(d.uploadedAt) }}</span>
                </li>
              }
            </ul>
          }
          @case ('history') {
            <ol class="list">
              @for (a of r.activity; track $index) {
                <li>{{ activity(a) }}</li>
              }
            </ol>
          }
          @default {
            <p class="sl-muted">
              {{ 'admin.specialists.record.laterEpic' | transloco }}
            </p>
          }
        }
      </section>

      <dialog #suspendDialog class="sl-dialog">
        <h2>{{ 'admin.specialists.record.suspendTitle' | transloco }}</h2>
        <label class="sl-field">
          <span>{{ 'admin.specialists.record.suspendReason' | transloco }}</span>
          <textarea
            class="sl-textarea"
            maxlength="1000"
            [value]="reason()"
            (input)="reason.set(value($event))"
          ></textarea>
        </label>
        <div class="dialog-actions">
          <button class="sl-button" type="button" (click)="closeDialog()">
            {{ 'admin.specialists.review.cancel' | transloco }}
          </button>
          <button
            class="sl-button sl-button--danger"
            type="button"
            [disabled]="busy() || !reason().trim()"
            (click)="suspend()"
          >
            {{ 'admin.specialists.record.suspend' | transloco }}
          </button>
        </div>
      </dialog>
    } @else if (id() && error()) {
      <p role="alert">{{ error() }}</p>
    }
  `,
  styles: `
    .search {
      width: 100%;
      box-sizing: border-box;
    }
    .results {
      list-style: none;
      margin: var(--space-8) 0 0;
      padding: var(--space-6);
    }
    .result {
      display: flex;
      align-items: center;
      gap: var(--space-12);
      width: 100%;
      min-height: 44px;
      padding: 0 var(--space-10);
      border: 0;
      border-radius: var(--radius-10);
      background: transparent;
      font: inherit;
      text-align: left;
      cursor: pointer;
    }
    .result:hover {
      background: var(--surface-sunken);
    }
    .none {
      padding: var(--space-10);
    }
    .head {
      display: flex;
      flex-wrap: wrap;
      align-items: center;
      gap: var(--space-16);
      margin: var(--space-20) 0 var(--space-16);
    }
    .avatar {
      width: 56px;
      height: 56px;
      border-radius: var(--radius-pill);
      display: inline-flex;
      align-items: center;
      justify-content: center;
      background: var(--track-pink-tint);
      color: var(--track-pink-ink);
      font-family: var(--font-display);
      font-weight: 700;
      font-size: 20px;
    }
    .who p {
      margin: var(--space-2) 0 0;
    }
    .actions {
      display: flex;
      gap: var(--space-10);
      margin-left: auto;
    }
    .error {
      color: var(--danger);
      font-weight: 600;
    }
    .stats {
      display: grid;
      grid-template-columns: repeat(4, minmax(0, 1fr));
      gap: var(--space-14);
    }
    @media (max-width: 1000px) {
      .stats {
        grid-template-columns: repeat(2, minmax(0, 1fr));
      }
    }
    .stat {
      display: flex;
      flex-direction: column;
      gap: var(--space-4);
    }
    .stat strong {
      font-family: var(--font-display);
      font-size: 26px;
    }
    .tabs {
      display: flex;
      flex-wrap: wrap;
      gap: var(--space-4);
      margin: var(--space-20) 0 var(--space-12);
      border-bottom: 1px solid var(--line);
    }
    .tabs button {
      min-height: 44px;
      padding: 0 var(--space-14);
      border: 0;
      border-bottom: 3px solid transparent;
      background: transparent;
      color: var(--ink-muted);
      font: inherit;
      font-weight: 600;
      cursor: pointer;
    }
    .tabs button[aria-selected='true'] {
      color: var(--brand-deep);
      border-bottom-color: var(--brand);
    }
    .list {
      margin: 0;
      padding-left: var(--space-18);
      display: flex;
      flex-direction: column;
      gap: var(--space-8);
    }
    .file {
      min-height: 44px;
      padding: 0 var(--space-12);
      margin-right: var(--space-8);
      border: 1px solid var(--line-control);
      border-radius: var(--radius-10);
      background: var(--surface-card);
      color: var(--brand-deep);
      font: inherit;
      font-weight: 600;
      cursor: pointer;
    }
    .dialog-actions {
      display: flex;
      justify-content: flex-end;
      gap: var(--space-10);
      margin-top: var(--space-16);
    }
  `,
})
export class SpecialistRecordPage {
  private readonly api = inject(CoreApi);
  private readonly router = inject(Router);
  private readonly transloco = inject(TranslocoService);
  private readonly staff = inject(StaffSession);

  /** The specialist's id from the route; empty on the search page. */
  readonly id = input<string>('');

  readonly tabs = TABS;
  readonly tab = signal<Tab>('documents');
  readonly query = signal('');
  readonly results = signal<Summary[]>([]);
  readonly record = signal<RecordView | null>(null);
  readonly error = signal<string | null>(null);
  readonly busy = signal(false);
  readonly reason = signal('');
  readonly lang = signal(this.transloco.getActiveLang());
  readonly canChange = computed(() => this.staff.accessTo('specialists') === 'F');
  readonly superAdmin = computed(() => this.staff.roles().includes('SUPER_ADMIN'));
  readonly dialog = viewChild<ElementRef<HTMLDialogElement>>('suspendDialog');
  private searchTimer: ReturnType<typeof setTimeout> | undefined;

  constructor() {
    this.transloco.langChanges$.subscribe((lang) => this.lang.set(lang));
    effect(() => {
      const id = this.id();
      this.lang();
      if (id) void this.load(id);
      else this.record.set(null);
    });
  }

  async load(id: string) {
    this.error.set(null);
    try {
      this.record.set(
        orThrow(
          await this.api.client.GET('/admin/v1/specialists/{id}', {
            params: { path: { id } },
          }),
        ),
      );
    } catch (e) {
      this.error.set(this.message(e));
    }
  }

  /** Searches once typing pauses, from two characters. */
  search(text: string) {
    this.query.set(text);
    clearTimeout(this.searchTimer);
    if (text.trim().length < 2) {
      this.results.set([]);
      return;
    }
    this.searchTimer = setTimeout(async () => {
      try {
        this.results.set(
          orThrow(
            await this.api.client.GET('/admin/v1/specialists', {
              params: { query: { query: text.trim() } },
            }),
          ),
        );
      } catch (e) {
        this.error.set(this.message(e));
      }
    }, 300);
  }

  choose(s: Summary) {
    this.query.set('');
    this.results.set([]);
    void this.router.navigate(['/specialists', s.id]);
  }

  closeDialog() {
    this.dialog()?.nativeElement.close();
  }

  askSuspend() {
    this.reason.set('');
    this.dialog()?.nativeElement.showModal();
  }

  async suspend() {
    const reason = this.reason().trim();
    const r = this.record();
    if (!reason || !r) return;
    await this.act(() =>
      this.api.client.POST('/admin/v1/specialists/{id}/suspension', {
        params: { path: { id: r.id } },
        body: { reason },
      }),
    );
    this.dialog()?.nativeElement.close();
  }

  reinstate() {
    const r = this.record();
    if (!r) return;
    return this.act(() =>
      this.api.client.DELETE('/admin/v1/specialists/{id}/suspension', {
        params: { path: { id: r.id } },
      }),
    );
  }

  async openDocument(applicationId: string, fileId: string) {
    const tab = window.open('', '_blank');
    try {
      const url = URL.createObjectURL(await this.api.document(applicationId, fileId));
      if (tab) tab.location.href = url;
      setTimeout(() => URL.revokeObjectURL(url), 60_000);
    } catch (e) {
      tab?.close();
      this.error.set(this.message(e));
    }
  }

  meta(r: RecordView): string {
    const languages = r.languages
      .filter((l) => l.verifiedLevel && l.verifiedLevel !== 'BELOW_PROFESSIONAL')
      .map(
        (l) =>
          `${languageName(l.code, this.lang())} ${this.transloco.translate(
            `admin.specialists.levels.${l.verifiedLevel}`,
          )}`,
      )
      .join(', ');
    return [r.professionLabel, capitalised(languages)].filter(Boolean).join(' · ');
  }

  statusClass(status: string): string {
    if (status === 'APPROVED') return 'sl-pill sl-pill--brand';
    if (status === 'SUSPENDED' || status === 'REJECTED') return 'sl-pill sl-pill--danger';
    return 'sl-pill sl-pill--warning';
  }

  activity(a: RecordView['activity'][number]): string {
    const subject =
      a.kind === 'LANGUAGE_VERIFIED' || a.kind === 'BIO_ADDED'
        ? languageName(a.subject ?? '', this.lang())
        : a.subject
          ? this.transloco.translate(`admin.specialists.review.item_${a.subject}`)
          : '';
    const value = a.value
      ? this.transloco.translate(`admin.specialists.review.level_${a.value}`)
      : '';
    const text = this.transloco.translate(`admin.specialists.review.kind_${a.kind}`, {
      subject,
      value,
    });
    return `${this.short(a.at)} · ${capitalised(text)}${a.by ? ` (${a.by.name})` : ''}`;
  }

  initialsOf(name: string): string {
    return initials(name);
  }

  short(iso: string | undefined): string {
    return shortDate(iso, this.lang());
  }

  day(isoDate: string | undefined): string {
    return calendarDate(isoDate, this.lang());
  }

  value(event: Event): string {
    return (event.target as HTMLInputElement).value;
  }

  private async act(
    call: () => Promise<{
      data?: RecordView;
      error?: unknown;
      response: Response;
    }>,
  ) {
    this.busy.set(true);
    this.error.set(null);
    try {
      this.record.set(orThrow(await call()));
    } catch (e) {
      this.error.set(this.message(e));
    } finally {
      this.busy.set(false);
    }
  }

  private message(e: unknown): string {
    return e instanceof ApiProblem && e.title
      ? e.title
      : this.transloco.translate('common.checkConnection');
  }
}
