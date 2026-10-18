import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { TranslocoPipe, TranslocoService } from '@jsverse/transloco';

import { CoreApi, type CoreSchemas, orThrow } from '../api/core-api';
import { capitalised, languageName } from '../shared/format';

type Row = CoreSchemas['PersonRow'];

/** "#4821" as "Seeker #4821", "G-2198" as "Guest #G-2198" (D41). */
export function personLabel(t: TranslocoService, ref: string): string {
  return ref.startsWith('G-')
    ? t.translate('admin.bookings.guest', { ref: `#${ref}` })
    : t.translate('admin.bookings.seeker', { ref });
}

/**
 * Admin screen 9: seekers with accounts and guests who booked (People.dc.html; D45). Contacts stay
 * masked until someone reveals one on the person's record, which is logged.
 */
@Component({
  selector: 'sl-people',
  imports: [TranslocoPipe, FormsModule, RouterLink],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <h1 class="sl-title">{{ 'admin.people.title' | transloco }}</h1>
    <p class="sl-muted subtitle">{{ 'admin.people.subtitle' | transloco }}</p>

    <form class="filters" role="search" (submit)="$event.preventDefault(); load()">
      <label class="sl-field search">
        <span>{{ 'admin.people.search' | transloco }}</span>
        <input class="sl-input" type="search" name="q" maxlength="120" [(ngModel)]="query" />
      </label>
      <label class="sl-field">
        <span>{{ 'admin.people.type' | transloco }}</span>
        <select class="sl-select" name="type" [(ngModel)]="type" (ngModelChange)="load()">
          <option value="">{{ 'admin.people.everyone' | transloco }}</option>
          <option value="ACCOUNT">{{ 'admin.people.accounts' | transloco }}</option>
          <option value="GUEST">{{ 'admin.people.guests' | transloco }}</option>
        </select>
      </label>
      <button type="submit" class="sl-button sl-button--primary">
        {{ 'admin.common.search' | transloco }}
      </button>
    </form>

    <section class="sl-card table-card" aria-live="polite">
      @if (error()) {
        <div class="state">
          <p>{{ 'common.checkConnection' | transloco }}</p>
          <button class="sl-button" type="button" (click)="load()">
            {{ 'common.tryAgain' | transloco }}
          </button>
        </div>
      } @else if (rows() === null) {
        <p class="state sl-muted">{{ 'common.loading' | transloco }}</p>
      } @else if (rows()?.length === 0) {
        <p class="state sl-muted">{{ 'admin.common.noResults' | transloco }}</p>
      } @else {
        <table class="sl-table">
          <thead>
            <tr>
              <th scope="col">{{ 'admin.people.person' | transloco }}</th>
              <th scope="col">{{ 'admin.people.type' | transloco }}</th>
              <th scope="col">{{ 'admin.people.contact' | transloco }}</th>
              <th scope="col">{{ 'admin.people.appLanguage' | transloco }}</th>
              <th scope="col">{{ 'admin.people.bookings' | transloco }}</th>
              <th scope="col">{{ 'admin.people.since' | transloco }}</th>
              <th scope="col">{{ 'admin.people.openRequests' | transloco }}</th>
            </tr>
          </thead>
          <tbody>
            @for (p of rows() ?? []; track p.id) {
              <tr>
                <td class="strong">
                  <a [routerLink]="['/people', p.id]">{{ label(p.ref) }}</a>
                </td>
                <td>{{ 'admin.people.kind.' + p.type | transloco }}</td>
                <td>{{ p.contact }}</td>
                <td>{{ language(p.appLanguage) }}</td>
                <td>{{ p.bookings }}</td>
                <td>{{ month(p.since) }}</td>
                <td>
                  @if (p.openRequests.length === 0) {
                    {{ 'admin.people.none' | transloco }}
                  } @else {
                    @for (r of p.openRequests; track r) {
                      <span class="sl-pill sl-pill--warning">{{
                        'admin.people.request.' + r | transloco
                      }}</span>
                    }
                  }
                </td>
              </tr>
            }
          </tbody>
        </table>
      }
    </section>
  `,
  styles: `
    .subtitle {
      margin: var(--space-4) 0 var(--space-16);
      max-width: 80ch;
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
  `,
})
export class PeoplePage {
  private readonly api = inject(CoreApi);
  private readonly transloco = inject(TranslocoService);

  readonly query = signal('');
  readonly type = signal<'' | 'ACCOUNT' | 'GUEST'>('');
  readonly rows = signal<Row[] | null>(null);
  readonly error = signal(false);
  private readonly lang = signal(this.transloco.getActiveLang());

  constructor() {
    this.transloco.langChanges$.subscribe((lang) => this.lang.set(lang));
    void this.load();
  }

  async load() {
    this.error.set(false);
    const query: { q?: string; type?: 'ACCOUNT' | 'GUEST' } = {};
    if (this.query().trim()) query.q = this.query().trim();
    if (this.type()) query.type = this.type() as 'ACCOUNT' | 'GUEST';
    try {
      this.rows.set(orThrow(await this.api.client.GET('/admin/v1/people', { params: { query } })));
    } catch {
      this.error.set(true);
    }
  }

  label(ref: string): string {
    return personLabel(this.transloco, ref);
  }

  language(code: string): string {
    return capitalised(languageName(code, this.lang()));
  }

  month(iso: string): string {
    return new Intl.DateTimeFormat(this.lang(), { month: 'short', year: 'numeric' }).format(
      new Date(iso),
    );
  }
}
