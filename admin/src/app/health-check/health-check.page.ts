import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { Router, RouterLink } from '@angular/router';
import { TranslocoPipe, TranslocoService } from '@jsverse/transloco';

import { CoreApi, type CoreSchemas, orThrow } from '../api/core-api';
import { StaffSession } from '../auth/staff-session';
import { canWrite, hcState, specialtyNames } from './health-check-shared';

type Row = CoreSchemas['HealthCheckVersionRow'];
type Stats = {
  completedLast7Days: number;
  startedLast7Days: number;
  safetyStopsLast7Days: number;
  suggestionsLast30Days: { code: string; count: number }[];
};

/**
 * Admin screen 28: the health check's versions, completions and safety stops, and what the
 * published version suggests (HealthCheck.dc.html; D55). Counts only; no one's answers.
 */
@Component({
  selector: 'sl-health-check',
  imports: [TranslocoPipe, RouterLink],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="head">
      <div>
        <h1 class="sl-title">{{ 'admin.healthCheck.title' | transloco }}</h1>
        <p class="sl-muted subtitle">{{ 'admin.healthCheck.subtitle' | transloco }}</p>
      </div>
      @if (mayWrite() && !working()) {
        <button
          type="button"
          class="sl-button sl-button--primary"
          [disabled]="busy()"
          (click)="newVersion()"
        >
          {{ 'admin.healthCheck.new' | transloco }}
        </button>
      }
    </div>

    @if (error()) {
      <div class="sl-card">
        <p>{{ 'common.checkConnection' | transloco }}</p>
        <button class="sl-button" type="button" (click)="load()">
          {{ 'common.tryAgain' | transloco }}
        </button>
      </div>
    } @else {
      @if (stats(); as s) {
        <div class="stats">
          <section class="sl-card stat">
            <p class="sl-meta">{{ 'admin.healthCheck.completed' | transloco }}</p>
            <p class="value">{{ s.completedLast7Days }}</p>
          </section>
          <section class="sl-card stat">
            <p class="sl-meta">{{ 'admin.healthCheck.finished' | transloco }}</p>
            <p class="value">{{ finishedShare() }}</p>
          </section>
          <section class="sl-card stat">
            <p class="sl-meta">{{ 'admin.healthCheck.stops' | transloco }}</p>
            <p class="value">{{ s.safetyStopsLast7Days }}</p>
            <a routerLink="/crisis">{{ 'admin.healthCheck.openCrisis' | transloco }}</a>
          </section>
        </div>

        <section class="sl-card" aria-labelledby="suggests-title">
          <h2 id="suggests-title" class="section">
            @if (published(); as p) {
              {{ 'admin.healthCheck.suggests' | transloco: { n: p.number } }}
            } @else {
              {{ 'admin.healthCheck.suggestsNone' | transloco }}
            }
          </h2>
          <p class="sl-meta">{{ 'admin.healthCheck.suggestsNote' | transloco }}</p>
          @for (s of shares(); track s.code) {
            <div class="share">
              <span>{{ s.name }}</span>
              <span class="bar" aria-hidden="true"><span [style.width.%]="s.percent"></span></span>
              <span class="strong">{{ s.percent }}%</span>
            </div>
          } @empty {
            <p class="sl-muted">{{ 'admin.healthCheck.noSuggestions' | transloco }}</p>
          }
        </section>
      }

      <section class="sl-card table-card" aria-labelledby="versions-title">
        <h2 id="versions-title" class="section pad">
          {{ 'admin.healthCheck.versions' | transloco }}
        </h2>
        @if (rows() === null) {
          <p class="pad sl-muted">{{ 'common.loading' | transloco }}</p>
        } @else {
          <table class="sl-table">
            <thead>
              <tr>
                <th scope="col">{{ 'admin.healthCheck.col.version' | transloco }}</th>
                <th scope="col">{{ 'admin.healthCheck.col.status' | transloco }}</th>
                <th scope="col">{{ 'admin.healthCheck.col.writtenBy' | transloco }}</th>
                <th scope="col">{{ 'admin.healthCheck.col.approvedBy' | transloco }}</th>
                <th scope="col">{{ 'admin.healthCheck.col.completions' | transloco }}</th>
                <th scope="col">{{ 'admin.healthCheck.col.stops' | transloco }}</th>
              </tr>
            </thead>
            <tbody>
              @for (v of rows() ?? []; track v.id) {
                <tr>
                  <td>
                    <a [routerLink]="['/health-check/versions', v.id]">v{{ v.number }}</a>
                  </td>
                  <td>
                    <span
                      class="sl-pill"
                      [class.sl-pill--brand]="v.status === 'PUBLISHED'"
                      [class.sl-pill--warning]="v.status === 'DRAFT' || v.status === 'IN_REVIEW'"
                    >
                      {{ 'admin.healthCheck.status.' + state(v) | transloco }}
                    </span>
                  </td>
                  <td>{{ v.draftedBy }}</td>
                  <td>{{ v.approvedBy ?? '—' }}</td>
                  <td>{{ v.completions }}</td>
                  <td>{{ v.safetyStops }}</td>
                </tr>
              }
            </tbody>
          </table>
        }
      </section>
    }
  `,
  styles: `
    .head {
      display: flex;
      justify-content: space-between;
      align-items: start;
      gap: var(--space-16);
      margin-bottom: var(--space-20);
    }
    .subtitle {
      margin: var(--space-4) 0 0;
      max-width: 80ch;
    }
    .stats {
      display: grid;
      grid-template-columns: repeat(3, 1fr);
      gap: var(--space-16);
      margin-bottom: var(--space-16);
    }
    .stat .value {
      font-size: 1.75rem;
      font-weight: 700;
      margin: var(--space-4) 0;
    }
    .section {
      font-size: 1rem;
      margin: 0 0 var(--space-8);
    }
    .pad {
      padding: var(--space-16) var(--space-20) 0;
    }
    .table-card {
      padding: 0;
      overflow-x: auto;
      margin-top: var(--space-16);
    }
    .share {
      display: grid;
      grid-template-columns: 14rem 1fr 3rem;
      align-items: center;
      gap: var(--space-12);
      margin: var(--space-8) 0;
    }
    .bar {
      height: 0.5rem;
      border-radius: 999px;
      background: var(--surface-sunken, #eef1ec);
      overflow: hidden;
    }
    .bar span {
      display: block;
      height: 100%;
      background: var(--brand);
    }
    .strong {
      font-weight: 700;
      text-align: right;
    }
  `,
})
export class HealthCheckPage {
  private readonly api = inject(CoreApi);
  private readonly staff = inject(StaffSession);
  private readonly router = inject(Router);
  private readonly transloco = inject(TranslocoService);

  readonly rows = signal<Row[] | null>(null);
  readonly stats = signal<Stats | null>(null);
  readonly names = signal<Record<string, string>>({});
  readonly error = signal(false);
  readonly busy = signal(false);
  private readonly lang = signal(this.transloco.getActiveLang());

  readonly mayWrite = computed(() => canWrite(this.staff.roles()));
  readonly published = computed(() => this.rows()?.find((v) => v.status === 'PUBLISHED'));
  readonly working = computed(() =>
    this.rows()?.find((v) => v.status === 'DRAFT' || v.status === 'IN_REVIEW'),
  );
  readonly finishedShare = computed(() => {
    const s = this.stats();
    if (!s || s.startedLast7Days === 0) return '—';
    return `${Math.round((s.completedLast7Days / s.startedLast7Days) * 100)}%`;
  });
  readonly shares = computed(() => {
    const list = this.stats()?.suggestionsLast30Days ?? [];
    const total = list.reduce((n, s) => n + s.count, 0);
    return list.map((s) => ({
      code: s.code,
      name: this.names()[s.code] ?? s.code,
      percent: total === 0 ? 0 : Math.round((s.count / total) * 100),
    }));
  });

  constructor() {
    this.transloco.langChanges$.subscribe((lang) => {
      this.lang.set(lang);
      void this.loadNames();
    });
    void this.load();
  }

  state(v: Row): string {
    return hcState(v.status, v.approvedBy);
  }

  async load() {
    this.error.set(false);
    try {
      const [rows, stats] = await Promise.all([
        this.api.client.GET('/admin/v1/health-check/versions'),
        this.api.client.GET('/admin/v1/health-check/stats'),
      ]);
      this.rows.set(orThrow(rows));
      this.stats.set(orThrow(stats) as Stats);
    } catch {
      this.error.set(true);
    }
  }

  private async loadNames() {
    this.names.set(await specialtyNames(this.api, this.lang()));
  }

  async newVersion() {
    this.busy.set(true);
    try {
      const v = orThrow(await this.api.client.POST('/admin/v1/health-check/versions'));
      await this.router.navigate(['/health-check/versions', v.id]);
    } catch {
      this.error.set(true);
    } finally {
      this.busy.set(false);
    }
  }
}
