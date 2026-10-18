import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { TranslocoPipe, TranslocoService } from '@jsverse/transloco';

import { CoreApi, type CoreSchemas, orThrow } from '../api/core-api';
import { fcfa, period } from '../shared/format';

/** Payout runs, newest first; each opens on admin screen 12. */
@Component({
  selector: 'sl-payout-runs',
  imports: [TranslocoPipe, RouterLink],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <h1 class="sl-title">{{ 'admin.payouts.title' | transloco }}</h1>
    <p class="sl-muted subtitle">{{ 'admin.payouts.subtitle' | transloco }}</p>

    <section class="sl-card table-card" aria-live="polite" [attr.aria-busy]="loading()">
      @if (error()) {
        <div class="state">
          <p>{{ 'common.checkConnection' | transloco }}</p>
          <button class="sl-button" type="button" (click)="load()">
            {{ 'common.tryAgain' | transloco }}
          </button>
        </div>
      } @else if (runs() === null) {
        <p class="state sl-muted">{{ 'common.loading' | transloco }}</p>
      } @else if (runs()!.length === 0) {
        <p class="state sl-muted">{{ 'admin.payouts.empty' | transloco }}</p>
      } @else {
        <table class="sl-table">
          <thead>
            <tr>
              <th scope="col">{{ 'admin.payouts.period' | transloco }}</th>
              <th scope="col">{{ 'admin.payouts.status' | transloco }}</th>
              <th scope="col">{{ 'admin.payouts.specialists' | transloco }}</th>
              <th scope="col">{{ 'admin.payouts.net' | transloco }}</th>
              <th scope="col">{{ 'admin.payouts.preparedBy' | transloco }}</th>
              <th scope="col">
                <span class="visually-hidden">{{ 'admin.payouts.open' | transloco }}</span>
              </th>
            </tr>
          </thead>
          <tbody>
            @for (run of runs(); track run.id) {
              <tr>
                <td class="strong">{{ span(run) }}</td>
                <td>
                  <span class="sl-pill" [class.sl-pill--warning]="run.status === 'SUBMITTED'">{{
                    'admin.payouts.runStatus.' + run.status | transloco
                  }}</span>
                </td>
                <td>{{ run.totals.specialists }}</td>
                <td>{{ money(run.totals.net) }}</td>
                <td>{{ run.preparedBy ?? '—' }}</td>
                <td class="action">
                  <a class="sl-button" [routerLink]="['/payouts', run.id]">
                    {{ 'admin.payouts.open' | transloco }}
                  </a>
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
      margin: var(--space-4) 0 var(--space-20);
      max-width: 80ch;
    }
    .table-card {
      padding: 0;
      overflow-x: auto;
    }
    .state {
      padding: var(--space-18);
      margin: 0;
    }
    .strong {
      font-weight: 700;
    }
    .action {
      text-align: right;
    }
    .visually-hidden {
      position: absolute;
      width: 1px;
      height: 1px;
      overflow: hidden;
      clip: rect(0 0 0 0);
    }
  `,
})
export class PayoutRunsPage {
  private readonly api = inject(CoreApi);
  private readonly transloco = inject(TranslocoService);

  readonly runs = signal<CoreSchemas['PayoutRun'][] | null>(null);
  readonly loading = signal(false);
  readonly error = signal(false);

  constructor() {
    void this.load();
  }

  async load() {
    this.loading.set(true);
    this.error.set(false);
    try {
      this.runs.set(orThrow(await this.api.client.GET('/admin/v1/payout-runs')));
    } catch {
      this.error.set(true);
    } finally {
      this.loading.set(false);
    }
  }

  span(run: CoreSchemas['PayoutRun']): string {
    return period(run.periodStart, run.periodEnd, this.transloco.getActiveLang());
  }

  money(amount: number): string {
    return fcfa(amount, this.transloco.getActiveLang());
  }
}
