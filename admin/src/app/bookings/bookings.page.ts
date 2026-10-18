import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { TranslocoPipe, TranslocoService } from '@jsverse/transloco';

import { ApiProblem, CoreApi, type CoreSchemas, orThrow } from '../api/core-api';
import { saveFile } from '../shared/download';
import { capitalised, dayAndTime, fcfa, languageName } from '../shared/format';

type Row = CoreSchemas['AdminBookingRow'];
type Status = CoreSchemas['AdminBookingStatus'];
type Range = 'TODAY' | 'NEXT_7' | 'LAST_7' | 'CUSTOM' | 'ALL';

const STATUSES: (Status | 'ALL')[] = [
  'ALL',
  'CONFIRMED',
  'IN_PROGRESS',
  'COMPLETED',
  'CANCELLED',
  'NO_SHOW',
  'DISPUTED',
];
const RANGES: Range[] = ['TODAY', 'NEXT_7', 'LAST_7', 'CUSTOM', 'ALL'];
const DAY = 86_400_000;

/**
 * Admin screen 7: every booking, filtered by status, dates and session language, or found by its
 * reference (Bookings.dc.html; D41). People appear by a pseudonymous reference only. Export is made
 * by Core so it is audited.
 */
@Component({
  selector: 'sl-bookings',
  imports: [TranslocoPipe, FormsModule, RouterLink],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="head">
      <h1 class="sl-title">{{ 'admin.bookings.title' | transloco }}</h1>
      <button type="button" class="sl-button" [disabled]="exporting()" (click)="export()">
        {{ 'admin.common.export' | transloco }}
      </button>
    </div>

    <form class="filters" role="search" (submit)="$event.preventDefault(); load()">
      <label class="sl-field search">
        <span>{{ 'admin.bookings.search' | transloco }}</span>
        <input
          class="sl-input"
          type="search"
          maxlength="40"
          name="q"
          [placeholder]="'BK-7F9D1C2E'"
          [(ngModel)]="query"
        />
      </label>
      <label class="sl-field">
        <span>{{ 'admin.bookings.status' | transloco }}</span>
        <select class="sl-select" name="status" [(ngModel)]="status" (ngModelChange)="load()">
          @for (s of statuses; track s) {
            <option [value]="s">
              {{ 'admin.bookings.statusFilter.' + s | transloco }}
            </option>
          }
        </select>
      </label>
      <label class="sl-field">
        <span>{{ 'admin.bookings.dates' | transloco }}</span>
        <select class="sl-select" name="range" [(ngModel)]="range" (ngModelChange)="load()">
          @for (r of ranges; track r) {
            <option [value]="r">
              {{ 'admin.bookings.range.' + r | transloco }}
            </option>
          }
        </select>
      </label>
      @if (range() === 'CUSTOM') {
        <label class="sl-field">
          <span>{{ 'admin.bookings.from' | transloco }}</span>
          <input class="sl-input" type="date" name="from" [(ngModel)]="from" (change)="load()" />
        </label>
        <label class="sl-field">
          <span>{{ 'admin.bookings.to' | transloco }}</span>
          <input class="sl-input" type="date" name="to" [(ngModel)]="to" (change)="load()" />
        </label>
      }
      <label class="sl-field">
        <span>{{ 'admin.bookings.language' | transloco }}</span>
        <select class="sl-select" name="language" [(ngModel)]="language" (ngModelChange)="load()">
          <option value="">{{ 'admin.common.all' | transloco }}</option>
          <option value="en">{{ name('en') }}</option>
          <option value="fr">{{ name('fr') }}</option>
        </select>
      </label>
      <button type="submit" class="sl-button sl-button--primary">
        {{ 'admin.common.search' | transloco }}
      </button>
    </form>

    <section class="sl-card table-card" aria-live="polite" [attr.aria-busy]="loading()">
      @if (error()) {
        <div class="state">
          <p>{{ problem() ?? 'common.checkConnection' | transloco }}</p>
          <button class="sl-button" type="button" (click)="load()">
            {{ 'common.tryAgain' | transloco }}
          </button>
        </div>
      } @else if (loading() && rows() === null) {
        <p class="state sl-muted">{{ 'common.loading' | transloco }}</p>
      } @else if (rows()?.length === 0) {
        <p class="state sl-muted">{{ 'admin.common.noResults' | transloco }}</p>
      } @else {
        <table class="sl-table">
          <thead>
            <tr>
              <th scope="col">{{ 'admin.bookings.booking' | transloco }}</th>
              <th scope="col">{{ 'admin.bookings.when' | transloco }}</th>
              <th scope="col">{{ 'admin.bookings.specialist' | transloco }}</th>
              <th scope="col">{{ 'admin.bookings.person' | transloco }}</th>
              <th scope="col">{{ 'admin.bookings.type' | transloco }}</th>
              <th scope="col">{{ 'admin.bookings.language' | transloco }}</th>
              <th scope="col">{{ 'admin.bookings.amount' | transloco }}</th>
              <th scope="col">{{ 'admin.bookings.status' | transloco }}</th>
            </tr>
          </thead>
          <tbody>
            @for (b of rows() ?? []; track b.id) {
              <tr>
                <td class="strong">
                  <a [routerLink]="['/bookings', b.id]">{{ b.reference }}</a>
                </td>
                <td>{{ when(b.startsAt) }}</td>
                <td>{{ b.specialist.fullName }}</td>
                <td>{{ person(b.personRef) }}</td>
                <td>{{ 'admin.modality.' + b.modality | transloco }}</td>
                <td>{{ name(b.sessionLanguage) }}</td>
                <td>{{ money(b.price) }}</td>
                <td>
                  <span
                    class="sl-pill"
                    [class.sl-pill--danger]="b.disputed"
                    [class.sl-pill--warning]="!b.disputed && !!b.waiting"
                    [class.sl-pill--brand]="!b.disputed && b.status === 'IN_PROGRESS'"
                    >{{ statusLabel(b) }}</span
                  >
                </td>
              </tr>
            }
          </tbody>
        </table>
      }
    </section>
  `,
  styles: `
    .head {
      display: flex;
      justify-content: space-between;
      align-items: center;
      gap: var(--space-12);
    }
    .filters {
      display: flex;
      flex-wrap: wrap;
      align-items: end;
      gap: var(--space-12);
      margin: var(--space-16) 0 var(--space-20);
    }
    .search {
      min-width: 16rem;
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
  `,
})
export class BookingsPage {
  private readonly api = inject(CoreApi);
  private readonly transloco = inject(TranslocoService);

  readonly statuses = STATUSES;
  readonly ranges = RANGES;
  readonly query = signal('');
  readonly status = signal<Status | 'ALL'>('ALL');
  readonly range = signal<Range>('TODAY');
  readonly from = signal('');
  readonly to = signal('');
  readonly language = signal('');
  readonly rows = signal<Row[] | null>(null);
  readonly loading = signal(false);
  readonly error = signal(false);
  readonly problem = signal<string | null>(null);
  readonly exporting = signal(false);
  private readonly lang = signal(this.transloco.getActiveLang());

  constructor() {
    this.transloco.langChanges$.subscribe((lang) => this.lang.set(lang));
    void this.load();
  }

  /** The search as Core takes it; a reference search ignores the dates. */
  private params() {
    const q = this.query().trim();
    const params: Record<string, string> = {};
    if (q) params['q'] = q;
    if (this.status() !== 'ALL') params['status'] = this.status();
    if (this.language()) params['language'] = this.language();
    if (!q) Object.assign(params, this.window());
    return params;
  }

  private window(): { from?: string; to?: string } {
    const start = new Date();
    start.setHours(0, 0, 0, 0);
    const at = (t: number) => new Date(t).toISOString();
    switch (this.range()) {
      case 'TODAY':
        return { from: at(start.getTime()), to: at(start.getTime() + DAY) };
      case 'NEXT_7':
        return {
          from: new Date().toISOString(),
          to: at(start.getTime() + 8 * DAY),
        };
      case 'LAST_7':
        return {
          from: at(start.getTime() - 7 * DAY),
          to: new Date().toISOString(),
        };
      case 'CUSTOM': {
        const w: { from?: string; to?: string } = {};
        if (this.from()) w.from = new Date(`${this.from()}T00:00`).toISOString();
        if (this.to())
          w.to = new Date(new Date(`${this.to()}T00:00`).getTime() + DAY).toISOString();
        return w;
      }
      default:
        return {};
    }
  }

  async load() {
    this.loading.set(true);
    this.error.set(false);
    this.problem.set(null);
    try {
      this.rows.set(
        orThrow(
          await this.api.client.GET('/admin/v1/bookings', {
            params: { query: this.params() as never },
          }),
        ),
      );
    } catch (e) {
      this.error.set(true);
      if (e instanceof ApiProblem && e.status === 400) {
        this.problem.set('admin.bookings.badReference');
      }
    } finally {
      this.loading.set(false);
    }
  }

  async export() {
    this.exporting.set(true);
    try {
      const result = await this.api.client.GET('/admin/v1/bookings/export', {
        params: { query: this.params() as never },
        parseAs: 'text',
      });
      saveFile('bookings.csv', orThrow(result) as string);
    } catch {
      this.problem.set('common.checkConnection');
      this.error.set(true);
    } finally {
      this.exporting.set(false);
    }
  }

  statusLabel(b: Row): string {
    if (b.disputed) {
      return this.transloco.translate(
        b.status === 'NO_SHOW' || b.status === 'CONFIRMED' || b.status === 'IN_PROGRESS'
          ? 'admin.bookings.noShowDisputed'
          : 'admin.bookings.disputed',
      );
    }
    if (b.waiting) return this.transloco.translate(`admin.bookings.waiting_${b.waiting}`);
    if (b.status === 'CANCELLED' && b.cancelledBy) {
      return this.transloco.translate(`admin.bookings.cancelledBy_${b.cancelledBy}`);
    }
    return this.transloco.translate(`admin.bookings.state.${b.status}`);
  }

  person(ref: string): string {
    return ref.startsWith('G-')
      ? this.transloco.translate('admin.bookings.guest', { ref: `#${ref}` })
      : this.transloco.translate('admin.bookings.seeker', { ref });
  }

  when(iso: string): string {
    return dayAndTime(iso, this.lang());
  }

  money(amount: number): string {
    return fcfa(amount, this.lang());
  }

  name(code: string): string {
    return capitalised(languageName(code, this.lang()));
  }
}
