import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { TranslocoPipe, TranslocoService } from '@jsverse/transloco';

import { CoreApi, type CoreSchemas, orThrow } from '../api/core-api';
import { saveFile } from '../shared/download';

type Analytics = CoreSchemas['Analytics'];
type Period = 'LAST_30_DAYS' | 'LAST_7_DAYS' | 'THIS_QUARTER';
type Step = 'searched' | 'choseSlot' | 'paid' | 'completed';

const PERIODS: Period[] = ['LAST_30_DAYS', 'LAST_7_DAYS', 'THIS_QUARTER'];
const STEPS: Step[] = ['searched', 'choseSlot', 'paid', 'completed'];

/**
 * Admin screen 17: aggregated, de-identified numbers for a period (Analytics.dc.html; D46). The
 * funnel bars are drawn with plain boxes, labelled with their numbers, so they read without colour.
 */
@Component({
  selector: 'sl-analytics',
  imports: [TranslocoPipe, FormsModule],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="head">
      <h1 class="sl-title">{{ 'admin.analytics.title' | transloco }}</h1>
      <div class="controls">
        <label class="sl-field">
          <span>{{ 'admin.analytics.period' | transloco }}</span>
          <select class="sl-select" [(ngModel)]="period" (ngModelChange)="load()">
            @for (p of periods; track p) {
              <option [value]="p">{{ 'admin.analytics.periods.' + p | transloco }}</option>
            }
          </select>
        </label>
        <button type="button" class="sl-button" [disabled]="exporting()" (click)="export()">
          {{ 'admin.audit.export' | transloco }}
        </button>
      </div>
    </div>
    <p class="sl-muted subtitle">
      {{
        'admin.analytics.subtitle'
          | transloco: { period: ('admin.analytics.periods.' + period() | transloco) }
      }}
    </p>

    @if (error()) {
      <div class="sl-card">
        <p>{{ 'common.checkConnection' | transloco }}</p>
        <button class="sl-button" type="button" (click)="load()">
          {{ 'common.tryAgain' | transloco }}
        </button>
      </div>
    } @else if (data(); as a) {
      <section class="sl-card" aria-labelledby="funnel-title">
        @if (headline()) {
          <p class="headline">{{ headline()! | transloco }}</p>
        }
        <h2 id="funnel-title" class="section">{{ 'admin.analytics.funnel' | transloco }}</h2>
        <ul class="funnel">
          @for (s of steps; track s) {
            <li>
              <span class="label">{{ 'admin.analytics.step.' + s | transloco }}</span>
              <span class="bar" [style.width.%]="width(a, s)" aria-hidden="true"></span>
              <span class="strong">{{ number(a.funnel[s]) }}</span>
            </li>
          }
        </ul>
        @if (drop(); as d) {
          <p class="sl-meta">{{ 'admin.analytics.drop.' + d | transloco }}</p>
        }
      </section>

      <div class="tiles">
        <div class="sl-card tile">
          <p class="sl-meta">{{ 'admin.analytics.averageRating' | transloco }}</p>
          <p class="figure">{{ a.averageRating ?? '—' }}</p>
        </div>
        <div class="sl-card tile">
          <p class="sl-meta">{{ 'admin.analytics.bookedAgain' | transloco }}</p>
          <p class="figure">
            {{ a.bookedAgainPercent !== undefined ? a.bookedAgainPercent + '%' : '—' }}
          </p>
        </div>
        <div class="sl-card tile">
          <p class="sl-meta">{{ 'admin.analytics.inFrench' | transloco }}</p>
          <p class="figure">{{ share(a, 'fr') }}</p>
        </div>
        <div class="sl-card tile">
          <p class="sl-meta">{{ 'admin.analytics.recommendations' | transloco }}</p>
          <p class="figure muted">{{ 'admin.analytics.fromPhase2' | transloco }}</p>
        </div>
      </div>
    } @else {
      <p class="sl-card sl-muted">{{ 'common.loading' | transloco }}</p>
    }
  `,
  styles: `
    .head {
      display: flex;
      justify-content: space-between;
      align-items: end;
      gap: var(--space-12);
    }
    .controls {
      display: flex;
      gap: var(--space-12);
      align-items: end;
    }
    .subtitle {
      margin: var(--space-4) 0 var(--space-16);
    }
    .headline {
      font-family: var(--font-display);
      font-size: var(--type-title-md-size);
      font-weight: 700;
      margin: 0 0 var(--space-12);
    }
    .section {
      margin: 0 0 var(--space-12);
      font-size: var(--type-body-admin-size);
    }
    .funnel {
      list-style: none;
      margin: 0 0 var(--space-12);
      padding: 0;
      display: grid;
      gap: var(--space-8);
    }
    .funnel li {
      display: grid;
      grid-template-columns: 14rem 1fr 6rem;
      gap: var(--space-12);
      align-items: center;
    }
    .bar {
      display: block;
      height: 20px;
      min-width: 2px;
      background: var(--brand);
      border-radius: var(--radius-10);
    }
    .tiles {
      display: grid;
      grid-template-columns: repeat(4, minmax(0, 1fr));
      gap: var(--space-12);
      margin-top: var(--space-16);
    }
    .tile p {
      margin: 0;
    }
    .figure {
      font-family: var(--font-display);
      font-size: var(--type-title-md-size);
      font-weight: 700;
      margin-top: var(--space-4) !important;
    }
    .muted {
      color: var(--ink-muted);
    }
    .strong {
      font-weight: 700;
      text-align: right;
    }
  `,
})
export class AnalyticsPage {
  private readonly api = inject(CoreApi);
  private readonly transloco = inject(TranslocoService);

  readonly periods = PERIODS;
  readonly steps = STEPS;
  readonly period = signal<Period>('LAST_30_DAYS');
  readonly data = signal<Analytics | null>(null);
  readonly error = signal(false);
  readonly exporting = signal(false);
  private readonly lang = signal(this.transloco.getActiveLang());

  /** Where most people stop: the step with the smallest share of the one before. */
  readonly drop = computed<Step | null>(() => {
    const f = this.data()?.funnel;
    if (!f || f.searched === 0) return null;
    let worst: Step | null = null;
    let lowest = Infinity;
    for (let i = 1; i < STEPS.length; i++) {
      const before = f[STEPS[i - 1]!];
      const rate = before === 0 ? 1 : f[STEPS[i]!] / before;
      if (rate < lowest) {
        lowest = rate;
        worst = STEPS[i]!;
      }
    }
    return worst;
  });

  /** "Most people who pay complete their session", when it is true. */
  readonly headline = computed(() => {
    const f = this.data()?.funnel;
    return f && f.paid > 0 && f.completed / f.paid >= 0.5 ? 'admin.analytics.headline' : null;
  });

  constructor() {
    this.transloco.langChanges$.subscribe((lang) => this.lang.set(lang));
    void this.load();
  }

  async load() {
    this.error.set(false);
    try {
      this.data.set(
        orThrow(
          await this.api.client.GET('/admin/v1/analytics', {
            params: { query: { period: this.period() } },
          }),
        ),
      );
    } catch {
      this.error.set(true);
    }
  }

  async export() {
    this.exporting.set(true);
    try {
      const result = await this.api.client.GET('/admin/v1/analytics/export', {
        params: { query: { period: this.period() } },
        parseAs: 'text',
      });
      saveFile(`analytics-${this.period().toLowerCase()}.csv`, orThrow(result) as string);
    } finally {
      this.exporting.set(false);
    }
  }

  width(a: Analytics, s: Step): number {
    const top = Math.max(1, a.funnel.searched, a.funnel.choseSlot);
    return Math.round((100 * a.funnel[s]) / top);
  }

  number(n: number): string {
    return new Intl.NumberFormat(this.lang()).format(n);
  }

  share(a: Analytics, language: string): string {
    if (a.completedSessions === 0) return '—';
    return `${Math.round((100 * (a.sessionsByLanguage[language] ?? 0)) / a.completedSessions)}%`;
  }
}
