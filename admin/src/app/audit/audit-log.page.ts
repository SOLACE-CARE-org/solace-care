import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { TranslocoPipe, TranslocoService } from '@jsverse/transloco';

import { CoreApi, type CoreSchemas, orThrow } from '../api/core-api';
import { saveFile } from '../shared/download';
import { dateTime } from '../shared/format';

type Entry = CoreSchemas['AuditEntryView'];
type Group =
  | 'CONTACT_REVEALED'
  | 'CRISIS_VIEWED'
  | 'REFUND'
  | 'STATUS_CHANGE'
  | 'SETTING_CHANGE'
  | 'BREAK_GLASS';

const STAFF_ROLES = new Set([
  'SUPER_ADMIN',
  'OPERATIONS',
  'SUPPORT',
  'FINANCE',
  'CLINICAL_REVIEWER',
  'CRISIS_RESPONDER',
  'CONTENT_EDITOR',
]);

const GROUPS: Group[] = [
  'CONTACT_REVEALED',
  'CRISIS_VIEWED',
  'REFUND',
  'STATUS_CHANGE',
  'SETTING_CHANGE',
  'BREAK_GLASS',
];

/**
 * Admin screen 18: every admin action and clinical-data read, newest first (AuditLog.dc.html;
 * D42). Append-only: nothing here changes an entry. Export is made by Core and audited.
 */
@Component({
  selector: 'sl-audit-log',
  imports: [TranslocoPipe, FormsModule],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="head">
      <h1 class="sl-title">{{ 'admin.audit.title' | transloco }}</h1>
      <button type="button" class="sl-button" [disabled]="exporting()" (click)="export()">
        {{ 'admin.audit.export' | transloco }}
      </button>
    </div>
    <p class="sl-muted subtitle">{{ 'admin.audit.subtitle' | transloco }}</p>

    <form class="filters" role="search" (submit)="$event.preventDefault(); load()">
      <label class="sl-field search">
        <span>{{ 'admin.audit.search' | transloco }}</span>
        <input class="sl-input" type="search" name="q" maxlength="80" [(ngModel)]="query" />
      </label>
      <label class="sl-field">
        <span>{{ 'admin.audit.action' | transloco }}</span>
        <select class="sl-select" name="group" [(ngModel)]="group" (ngModelChange)="load()">
          <option value="">{{ 'admin.common.all' | transloco }}</option>
          @for (g of groups; track g) {
            <option [value]="g">{{ 'admin.audit.group.' + g | transloco }}</option>
          }
        </select>
      </label>
      <button type="submit" class="sl-button sl-button--primary">
        {{ 'admin.common.search' | transloco }}
      </button>
    </form>

    <section class="sl-card table-card" aria-live="polite" [attr.aria-busy]="loading()">
      @if (error()) {
        <div class="state">
          <p>{{ 'common.checkConnection' | transloco }}</p>
          <button class="sl-button" type="button" (click)="load()">
            {{ 'common.tryAgain' | transloco }}
          </button>
        </div>
      } @else if (entries() === null) {
        <p class="state sl-muted">{{ 'common.loading' | transloco }}</p>
      } @else if (entries()?.length === 0) {
        <p class="state sl-muted">{{ 'admin.common.noResults' | transloco }}</p>
      } @else {
        <table class="sl-table">
          <thead>
            <tr>
              <th scope="col">{{ 'admin.audit.when' | transloco }}</th>
              <th scope="col">{{ 'admin.audit.staff' | transloco }}</th>
              <th scope="col">{{ 'admin.audit.action' | transloco }}</th>
              <th scope="col">{{ 'admin.audit.on' | transloco }}</th>
              <th scope="col">{{ 'admin.audit.reason' | transloco }}</th>
            </tr>
          </thead>
          <tbody>
            @for (e of entries() ?? []; track e.id) {
              <tr>
                <td>{{ when(e.at) }}</td>
                <td>
                  <span class="strong">{{ e.actor.name }}</span>
                  @if (e.actor.roles.length) {
                    · {{ roles(e) }}
                  }
                </td>
                <td>
                  {{ action(e) }}
                  @if (e.outcome === 'DENIED') {
                    <span class="sl-pill sl-pill--danger">{{
                      'admin.audit.denied' | transloco
                    }}</span>
                  }
                </td>
                <td>{{ target(e) }}</td>
                <td class="reason">{{ e.reason }}</td>
              </tr>
            }
          </tbody>
        </table>
        @if (more()) {
          <div class="state">
            <button type="button" class="sl-button" (click)="next()">
              {{ 'admin.common.next' | transloco }}
            </button>
          </div>
        }
      }
    </section>
  `,
  styles: `
    .head {
      display: flex;
      justify-content: space-between;
      align-items: center;
    }
    .subtitle {
      margin: var(--space-4) 0 var(--space-16);
    }
    .filters {
      display: flex;
      flex-wrap: wrap;
      align-items: end;
      gap: var(--space-12);
      margin-bottom: var(--space-20);
    }
    .search {
      min-width: 18rem;
    }
    .table-card {
      padding: 0;
      overflow-x: auto;
    }
    .state {
      padding: var(--space-20);
      margin: 0;
    }
    .strong {
      font-weight: 700;
    }
    .reason {
      max-width: 40ch;
    }
  `,
})
export class AuditLogPage {
  private readonly api = inject(CoreApi);
  private readonly transloco = inject(TranslocoService);

  readonly groups = GROUPS;
  readonly query = signal('');
  readonly group = signal<Group | ''>('');
  readonly entries = signal<Entry[] | null>(null);
  readonly loading = signal(false);
  readonly error = signal(false);
  readonly more = signal(false);
  readonly exporting = signal(false);
  private readonly lang = signal(this.transloco.getActiveLang());

  constructor() {
    this.transloco.langChanges$.subscribe((lang) => this.lang.set(lang));
    void this.load();
  }

  private params(before?: number) {
    const params: Record<string, string | number> = {};
    if (this.query().trim()) params['q'] = this.query().trim();
    if (this.group()) params['group'] = this.group();
    if (before !== undefined) params['before'] = before;
    return params;
  }

  async load() {
    this.loading.set(true);
    this.error.set(false);
    try {
      const page = orThrow(
        await this.api.client.GET('/admin/v1/audit', { params: { query: this.params() as never } }),
      );
      this.entries.set(page);
      this.more.set(page.length === 100);
    } catch {
      this.error.set(true);
    } finally {
      this.loading.set(false);
    }
  }

  async next() {
    const last = this.entries()?.at(-1);
    if (!last) return;
    try {
      const page = orThrow(
        await this.api.client.GET('/admin/v1/audit', {
          params: { query: this.params(last.id) as never },
        }),
      );
      this.entries.update((e) => [...(e ?? []), ...page]);
      this.more.set(page.length === 100);
    } catch {
      this.error.set(true);
    }
  }

  async export() {
    this.exporting.set(true);
    try {
      const result = await this.api.client.GET('/admin/v1/audit/export', {
        params: { query: this.params() as never },
        parseAs: 'text',
      });
      saveFile('audit-log.csv', orThrow(result) as string);
    } finally {
      this.exporting.set(false);
    }
  }

  when(iso: string): string {
    return dateTime(iso, this.lang());
  }

  roles(e: Entry): string {
    return e.actor.roles
      .filter((r) => STAFF_ROLES.has(r))
      .map((r) => this.transloco.translate(`admin.roles.${r}`))
      .join(', ');
  }

  /** A known action in words; any other shows its dotted name. */
  action(e: Entry): string {
    const key = `admin.audit.actions.${e.action.replaceAll('.', '_')}`;
    const words = this.transloco.translate(key);
    return words === key ? e.action : words;
  }

  target(e: Entry): string {
    const label = e.target.label;
    if (/^#\d{4}$/.test(label))
      return this.transloco.translate('admin.bookings.seeker', { ref: label });
    if (/^G-\d{4}$/.test(label)) {
      return this.transloco.translate('admin.bookings.guest', { ref: `#${label}` });
    }
    return label;
  }
}
