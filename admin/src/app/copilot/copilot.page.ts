import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { TranslocoPipe, TranslocoService } from '@jsverse/transloco';

import { AiApi, type AiSchemas } from '../api/ai-api';
import { ApiProblem, CoreApi, type CoreSchemas, orThrow } from '../api/core-api';
import { dateTime, shortDate } from '../shared/format';

type Answer = AiSchemas['CopilotAnswer'];
type Metric = AiSchemas['CopilotMetric'];
type Period = AiSchemas['CopilotPeriod'];
type Alert = CoreSchemas['InsightAlert'];

/** The catalogue questions, answered without a model (D58). */
const SUGGESTED: { metric: Metric; period: Period }[] = [
  { metric: 'INVITES_BY_MODULE', period: 'LAST_MONTH' },
  { metric: 'BOOKINGS_BY_SOURCE', period: 'LAST_30_DAYS' },
  { metric: 'SESSIONS_BY_LANGUAGE', period: 'LAST_30_DAYS' },
  { metric: 'PAYMENTS_BY_STATUS', period: 'LAST_7_DAYS' },
  { metric: 'RATINGS', period: 'THIS_QUARTER' },
];

/**
 * Admin screen 27, the admin copilot (Copilot.dc.html; D58): questions about aggregated,
 * de-identified numbers, the table behind each answer, and anomaly alerts. It cannot look up a
 * person and cannot change anything. Staff with Analytics access.
 */
@Component({
  selector: 'sl-copilot',
  imports: [TranslocoPipe, FormsModule, RouterLink],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <h1>{{ 'admin.copilot.title' | transloco }}</h1>
    <p class="sl-meta subtitle">{{ 'admin.copilot.notice' | transloco }}</p>

    @if (alerts().length > 0) {
      <section class="sl-card alerts" aria-labelledby="alerts-title">
        <h2 id="alerts-title" class="section">{{ 'admin.copilot.alerts' | transloco }}</h2>
        <ul>
          @for (a of alerts(); track a.detectedAt + a.kind) {
            <li>
              <span class="sl-pill sl-pill--warning">{{
                'admin.copilot.alert_' + a.kind | transloco
              }}</span>
              {{ alertLine(a) }}
            </li>
          }
        </ul>
      </section>
    }

    <section class="suggestions" aria-labelledby="suggest-title">
      <h2 id="suggest-title" class="sl-meta">{{ 'admin.copilot.suggested' | transloco }}</h2>
      <div class="chips">
        @for (s of suggested; track s.metric) {
          <button
            type="button"
            class="sl-chip"
            [disabled]="busy()"
            (click)="pick(s.metric, s.period)"
          >
            {{ 'admin.copilot.q_' + s.metric | transloco }}
          </button>
        }
      </div>
    </section>

    @for (turn of turns(); track $index) {
      <section class="sl-card turn">
        <p class="question">{{ turn.question }}</p>
        <p class="answer">{{ turn.answer.answer }}</p>
        @if (turn.answer.aiWorded) {
          <p class="sl-meta">{{ 'admin.copilot.aiWorded' | transloco }}</p>
        }
        <div class="table-wrap">
          <table class="sl-table">
            <thead>
              <tr>
                @for (c of turn.answer.columns; track $index) {
                  <th scope="col">{{ c }}</th>
                }
              </tr>
            </thead>
            <tbody>
              @for (r of turn.answer.rows; track $index) {
                <tr>
                  @for (cell of r; track $index) {
                    <td>{{ cell }}</td>
                  }
                </tr>
              }
            </tbody>
          </table>
        </div>
        <p class="sl-meta">
          {{ turn.answer.source }} · {{ range(turn.answer) }}
          @if (turn.answer.suppressed) {
            · {{ 'admin.copilot.suppressed' | transloco }}
          }
        </p>
        <a routerLink="/analytics">{{ 'admin.copilot.openAnalytics' | transloco }}</a>
      </section>
    }

    @if (error()) {
      <p class="sl-error" role="alert">{{ error() }}</p>
    }

    <form class="ask" (ngSubmit)="ask()">
      <label class="sl-field grow">
        <span>{{ 'admin.copilot.ask' | transloco }}</span>
        <input class="sl-input" name="question" maxlength="300" [(ngModel)]="question" />
      </label>
      <button type="submit" class="sl-button" [disabled]="busy() || !question.trim()">
        {{ 'admin.copilot.askButton' | transloco }}
      </button>
    </form>
  `,
  styles: `
    .subtitle {
      margin: var(--space-4) 0 var(--space-16);
      max-width: 80ch;
    }
    .alerts ul {
      list-style: none;
      margin: 0;
      padding: 0;
      display: grid;
      gap: var(--space-8);
    }
    .suggestions {
      margin: var(--space-16) 0;
    }
    .chips {
      display: flex;
      flex-wrap: wrap;
      gap: var(--space-8);
    }
    .sl-chip {
      min-height: 44px;
    }
    .turn {
      margin-bottom: var(--space-16);
      display: grid;
      gap: var(--space-8);
    }
    .question {
      font-weight: 700;
      margin: 0;
    }
    .answer {
      margin: 0;
    }
    .table-wrap {
      overflow-x: auto;
    }
    .ask {
      display: flex;
      gap: var(--space-8);
      align-items: flex-end;
      margin-top: var(--space-16);
    }
    .grow {
      flex: 1;
    }
  `,
})
export class CopilotPage {
  private readonly ai = inject(AiApi);
  private readonly core = inject(CoreApi);
  private readonly transloco = inject(TranslocoService);

  readonly suggested = SUGGESTED;
  readonly turns = signal<{ question: string; answer: Answer }[]>([]);
  readonly alerts = signal<Alert[]>([]);
  readonly busy = signal(false);
  readonly error = signal<string | null>(null);
  question = '';

  constructor() {
    void this.loadAlerts();
  }

  async loadAlerts() {
    try {
      this.alerts.set(orThrow(await this.core.client.GET('/admin/v1/insight/alerts')));
    } catch {
      // Alerts are a help, not the page: without them the copilot still answers.
    }
  }

  pick(metric: Metric, period: Period) {
    return this.send(this.transloco.translate(`admin.copilot.q_${metric}`), { metric, period });
  }

  ask() {
    const question = this.question.trim();
    if (!question) return;
    this.question = '';
    return this.send(question, { question });
  }

  alertLine(a: Alert): string {
    const lang = this.transloco.getActiveLang();
    return this.transloco.translate(`admin.copilot.alertLine_${a.kind}`, {
      value: a.value,
      baseline: a.baseline,
      when: dateTime(a.detectedAt, lang),
    });
  }

  range(a: Answer): string {
    const lang = this.transloco.getActiveLang();
    const last = new Date(new Date(a.to).getTime() - 1).toISOString();
    return `${shortDate(a.from, lang)} – ${shortDate(last, lang)}`;
  }

  private async send(
    question: string,
    body: { question?: string; metric?: Metric; period?: Period },
  ) {
    this.busy.set(true);
    this.error.set(null);
    try {
      const answer = orThrow(await this.ai.client.POST('/admin/v1/copilot/questions', { body }));
      this.turns.update((t) => [...t, { question, answer }]);
    } catch (e) {
      this.error.set(
        e instanceof ApiProblem && e.title
          ? e.title
          : this.transloco.translate('common.checkConnection'),
      );
    } finally {
      this.busy.set(false);
    }
  }
}
