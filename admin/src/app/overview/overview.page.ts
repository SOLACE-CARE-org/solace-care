import { ChangeDetectionStrategy, Component, DestroyRef, inject, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { TranslocoPipe, TranslocoService } from '@jsverse/transloco';

import { CoreApi, type CoreSchemas, orThrow } from '../api/core-api';
import { capitalised, clock, fcfa, languageName, minutesSince } from '../shared/format';

type Overview = CoreSchemas['Overview'];
type Row = CoreSchemas['AdminBookingRow'];

/** Live numbers refresh on their own; not in the specs. */
const REFRESH_MS = 30_000;

/**
 * Admin screen 2: what is happening now (Overview.dc.html; D46). Every role can see it; the links
 * lead only where the person's roles allow.
 */
@Component({
  selector: 'sl-overview',
  imports: [TranslocoPipe, RouterLink],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <h1 class="sl-title">{{ 'admin.overview.title' | transloco }}</h1>
    @if (error() && !data()) {
      <div class="sl-card">
        <p>{{ 'common.checkConnection' | transloco }}</p>
        <button class="sl-button" type="button" (click)="load()">
          {{ 'common.tryAgain' | transloco }}
        </button>
      </div>
    } @else if (data(); as d) {
      <p class="sl-muted subtitle">
        <span class="dot" aria-hidden="true"></span>{{ live(d.at) }}
      </p>

      @if (d.crisis.unacknowledged > 0) {
        <section class="alert" role="alert">
          <svg
            width="24"
            height="24"
            viewBox="0 0 24 24"
            fill="none"
            stroke="currentColor"
            stroke-width="1.8"
            stroke-linecap="round"
            stroke-linejoin="round"
            aria-hidden="true"
          >
            <circle cx="12" cy="12" r="9" />
            <circle cx="12" cy="12" r="4" />
            <path d="M5.6 5.6l3.6 3.6M14.8 14.8l3.6 3.6M18.4 5.6l-3.6 3.6M9.2 14.8l-3.6 3.6" />
          </svg>
          <div class="alert-text">
            <p class="alert-title">
              {{
                (d.crisis.unacknowledged === 1
                  ? 'admin.overview.crisisOne'
                  : 'admin.overview.crisisCount'
                ) | transloco: { count: d.crisis.unacknowledged }
              }}
            </p>
            <p>
              {{ 'admin.overview.oldest' | transloco: { minutes: minutes(d.crisis.oldestAt) } }}
              @if (d.crisis.onCall) {
                {{ 'admin.overview.onCall' | transloco: { name: d.crisis.onCall } }}
              }
            </p>
          </div>
          <a class="sl-button sl-button--danger-solid" routerLink="/crisis">
            {{ 'admin.overview.openCrisis' | transloco }}
          </a>
        </section>
      }

      <div class="tiles">
        <div class="sl-card tile">
          <p class="tile-label">{{ 'admin.overview.inSession' | transloco }}</p>
          <p class="figure">{{ d.inSession.total }}</p>
          <p class="sl-meta">
            {{
              'admin.overview.inSessionSplit'
                | transloco: { video: d.inSession.video, audio: d.inSession.audio }
            }}
          </p>
        </div>
        <div class="sl-card tile">
          <p class="tile-label">{{ 'admin.overview.sessionsToday' | transloco }}</p>
          <p class="figure">{{ d.sessionsToday.total }}</p>
          <p class="sl-meta">
            {{
              'admin.overview.sessionsSplit'
                | transloco
                  : { completed: d.sessionsToday.completed, noShows: d.sessionsToday.noShows }
            }}
          </p>
        </div>
        <div class="sl-card tile">
          <p class="tile-label">{{ 'admin.overview.bookingsToday' | transloco }}</p>
          <p class="figure">{{ d.bookingsToday.total }}</p>
          <p class="sl-meta">{{ byLanguage(d) }}</p>
        </div>
        <div class="sl-card tile">
          <p class="tile-label">{{ 'admin.overview.paymentsToday' | transloco }}</p>
          <p class="figure">{{ money(d.paymentsToday.captured) }}</p>
          @if (d.paymentsToday.failed > 0) {
            <p class="sl-meta">
              {{ 'admin.overview.failed' | transloco: { count: d.paymentsToday.failed } }}
            </p>
          }
        </div>
      </div>

      <div class="columns">
        <section class="sl-card table-card" aria-labelledby="now-title">
          <div class="pad row">
            <h2 id="now-title" class="section">{{ 'admin.overview.sessionsNow' | transloco }}</h2>
            <a routerLink="/bookings">{{ 'admin.overview.allBookings' | transloco }}</a>
          </div>
          @if (d.sessionsNow.length === 0) {
            <p class="sl-muted pad">{{ 'admin.overview.noSessions' | transloco }}</p>
          } @else {
            <table class="sl-table">
              <thead>
                <tr>
                  <th scope="col">{{ 'admin.overview.time' | transloco }}</th>
                  <th scope="col">{{ 'admin.bookings.specialist' | transloco }}</th>
                  <th scope="col">{{ 'admin.overview.seeker' | transloco }}</th>
                  <th scope="col">{{ 'admin.bookings.type' | transloco }}</th>
                  <th scope="col">{{ 'admin.bookings.language' | transloco }}</th>
                  <th scope="col">{{ 'admin.bookings.status' | transloco }}</th>
                </tr>
              </thead>
              <tbody>
                @for (s of d.sessionsNow; track s.id) {
                  <tr>
                    <td>
                      <a [routerLink]="['/bookings', s.id]">{{ time(s.startsAt) }}</a>
                    </td>
                    <td>{{ s.specialist.fullName }}</td>
                    <td>{{ person(s.personRef) }}</td>
                    <td>{{ 'admin.modality.' + s.modality | transloco }}</td>
                    <td>{{ language(s.sessionLanguage) }}</td>
                    <td>
                      <span
                        class="sl-pill"
                        [class.sl-pill--brand]="s.status === 'IN_PROGRESS'"
                        [class.sl-pill--warning]="!!s.waiting"
                        >{{ state(s, d.at) }}</span
                      >
                    </td>
                  </tr>
                }
              </tbody>
            </table>
          }
        </section>

        <div class="side">
          <section class="sl-card" aria-labelledby="attention-title">
            <h2 id="attention-title" class="section">
              {{ 'admin.overview.attention' | transloco }}
            </h2>
            <ul class="list attention">
              <li>
                <a routerLink="/applications"
                  ><span>{{ 'admin.overview.applications' | transloco }}</span
                  ><span class="strong">{{ d.attention.applications }}</span></a
                >
              </li>
              <li>
                <a routerLink="/payments"
                  ><span>{{ 'admin.overview.refunds' | transloco }}</span
                  ><span class="strong">{{ d.attention.refunds }}</span></a
                >
              </li>
              <li>
                <a routerLink="/reviews"
                  ><span>{{ 'admin.overview.reviews' | transloco }}</span
                  ><span class="strong">{{ d.attention.reviews }}</span></a
                >
              </li>
              <li>
                <a routerLink="/bookings"
                  ><span>{{ 'admin.overview.disputes' | transloco }}</span
                  ><span class="strong">{{ d.attention.disputes }}</span></a
                >
              </li>
              <li>
                <a routerLink="/people"
                  ><span>{{ 'admin.overview.dataRequests' | transloco }}</span
                  ><span class="strong">{{ d.attention.dataRequestsDue }}</span></a
                >
              </li>
            </ul>
          </section>
          <section class="sl-card" aria-labelledby="health-title">
            <h2 id="health-title" class="section">{{ 'admin.overview.health' | transloco }}</h2>
            <ul class="list">
              @for (h of d.health; track h.service) {
                <li>
                  <span>{{ 'admin.overview.service.' + h.service | transloco }}</span>
                  <span
                    class="sl-pill"
                    [class.sl-pill--brand]="h.status === 'HEALTHY'"
                    [class.sl-pill--warning]="h.status === 'SLOW'"
                    [class.sl-pill--danger]="h.status === 'DOWN'"
                    >{{ 'admin.overview.status.' + h.service + '_' + h.status | transloco }}</span
                  >
                </li>
              }
            </ul>
          </section>
        </div>
      </div>
    } @else {
      <p class="sl-card sl-muted">{{ 'common.loading' | transloco }}</p>
    }
  `,
  styles: `
    :host {
      display: block;
    }
    .subtitle {
      display: flex;
      align-items: center;
      gap: var(--space-8);
      margin: var(--space-4) 0 var(--space-20);
    }
    .dot {
      width: 8px;
      height: 8px;
      border-radius: 50%;
      background: var(--success);
    }
    .alert {
      display: flex;
      flex-wrap: wrap;
      align-items: center;
      gap: var(--space-14);
      margin-bottom: var(--space-20);
      padding: var(--space-16) var(--space-18);
      border: 1px solid var(--danger-line);
      border-radius: var(--radius-16);
      background: var(--danger-tint);
      color: var(--danger);
    }
    .alert-text {
      flex: 1;
      min-width: 240px;
    }
    .alert p {
      margin: 0;
    }
    .alert-title {
      font-size: var(--type-body-lg-size);
      font-weight: 700;
    }
    .tiles {
      display: grid;
      grid-template-columns: repeat(4, minmax(0, 1fr));
      gap: var(--space-14);
      margin-bottom: var(--space-20);
    }
    .tile {
      display: flex;
      flex-direction: column;
      gap: var(--space-6);
      padding: var(--space-16) var(--space-18);
    }
    .tile p {
      margin: 0;
    }
    .tile-label {
      color: var(--ink-muted);
    }
    .figure {
      font-family: var(--font-display);
      font-size: var(--type-title-lg-size);
      line-height: var(--type-title-lg-line-height);
      font-weight: 700;
    }
    .columns {
      display: grid;
      grid-template-columns: minmax(0, 2fr) minmax(0, 1fr);
      gap: var(--space-16);
      align-items: start;
    }
    .side {
      display: grid;
      gap: var(--space-16);
    }
    .table-card {
      padding: 0;
      overflow-x: auto;
    }
    .pad {
      padding: var(--space-16) var(--space-18);
    }
    .row {
      display: flex;
      justify-content: space-between;
      align-items: center;
    }
    .row .section {
      margin: 0;
    }
    .section {
      margin: 0 0 var(--space-10);
      font-size: var(--type-heading-sm-size);
      font-weight: 700;
    }
    .list {
      list-style: none;
      margin: 0;
      padding: 0;
      display: grid;
      gap: var(--space-8);
    }
    .list li {
      display: flex;
      justify-content: space-between;
      gap: var(--space-8);
      min-height: 32px;
      align-items: center;
    }
    .attention {
      gap: 0;
    }
    .attention li {
      display: block;
    }
    .attention a {
      display: flex;
      justify-content: space-between;
      align-items: center;
      gap: var(--space-8);
      min-height: var(--size-tap);
      border-bottom: 1px solid var(--surface-sunken);
      color: var(--ink);
      font-weight: 400;
      text-decoration: none;
    }
    .attention li:last-child a {
      border-bottom: 0;
    }
    .strong {
      font-weight: 700;
    }
  `,
})
export class OverviewPage {
  private readonly api = inject(CoreApi);
  private readonly transloco = inject(TranslocoService);

  readonly data = signal<Overview | null>(null);
  readonly error = signal(false);
  private readonly lang = signal(this.transloco.getActiveLang());

  constructor() {
    this.transloco.langChanges$.subscribe((lang) => this.lang.set(lang));
    void this.load();
    const timer = setInterval(() => void this.load(), REFRESH_MS);
    inject(DestroyRef).onDestroy(() => clearInterval(timer));
  }

  async load() {
    try {
      this.data.set(orThrow(await this.api.client.GET('/admin/v1/overview')));
      this.error.set(false);
    } catch {
      this.error.set(true);
    }
  }

  /** "Live · Friday 16 October, 14:32 Douala time". */
  live(iso: string): string {
    const when = new Intl.DateTimeFormat(this.lang(), {
      weekday: 'long',
      day: 'numeric',
      month: 'long',
      hour: '2-digit',
      minute: '2-digit',
      timeZone: 'Africa/Douala',
    }).format(new Date(iso));
    return this.transloco.translate('admin.overview.live', { when });
  }

  minutes(iso: string | undefined): number {
    return minutesSince(iso);
  }

  byLanguage(d: Overview): string {
    return Object.entries(d.bookingsToday.byLanguage)
      .map(([code, n]) =>
        this.transloco.translate('admin.overview.inLanguage', {
          count: n,
          language: languageName(code, this.lang()),
        }),
      )
      .join(' · ');
  }

  state(s: Row, now: string): string {
    if (s.waiting) {
      return this.transloco.translate(`admin.overview.waiting_${s.waiting}`);
    }
    if (s.status === 'CONFIRMED') {
      const minutes = Math.max(
        0,
        Math.round((new Date(s.startsAt).getTime() - new Date(now).getTime()) / 60_000),
      );
      return this.transloco.translate('admin.overview.startsIn', { minutes });
    }
    return this.transloco.translate(`admin.bookings.state.${s.status}`);
  }

  person(ref: string): string {
    return ref.startsWith('G-')
      ? this.transloco.translate('admin.bookings.guest', { ref: `#${ref}` })
      : this.transloco.translate('admin.bookings.seeker', { ref });
  }

  time(iso: string): string {
    return clock(iso, this.lang());
  }

  money(n: number): string {
    return fcfa(n, this.lang());
  }

  language(code: string): string {
    return capitalised(languageName(code, this.lang()));
  }
}
