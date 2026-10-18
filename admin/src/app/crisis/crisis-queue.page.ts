import {
  ChangeDetectionStrategy,
  Component,
  computed,
  effect,
  type ElementRef,
  inject,
  signal,
  viewChild,
} from '@angular/core';
import { FormsModule } from '@angular/forms';
import { TranslocoPipe, TranslocoService } from '@jsverse/transloco';

import { ApiProblem, CoreApi, type CoreSchemas, orThrow } from '../api/core-api';
import { StaffSession } from '../auth/staff-session';
import {
  capitalised,
  clock,
  dayAndTime,
  languageName,
  minutesSince,
  shortDate,
} from '../shared/format';
import { CrisisWatch } from './crisis-watch';

type Status = 'OPEN' | 'IN_PROGRESS' | 'CLOSED';
type Alert = CoreSchemas['CrisisAlert'];
type Outcome = CoreSchemas['CrisisOutcome'];

const STATUSES: Status[] = ['OPEN', 'IN_PROGRESS', 'CLOSED'];
const OUTCOMES: Outcome[] = [
  'SAFE_FOR_NOW',
  'REFERRED_TO_EMERGENCY_SERVICES',
  'NOT_REACHED_FOLLOW_UP',
  'NOT_A_CRISIS',
];

/**
 * Admin screen 3: every crisis alert, oldest first (CrisisQueue.dc.html, CrisisPhone.dc.html).
 * The responder sees only what the alert needs: the flagged text, its category, a masked contact
 * and the next session. Opening an alert and revealing the contact are audited by Core.
 */
@Component({
  selector: 'sl-crisis-queue',
  imports: [TranslocoPipe, FormsModule],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <h1 class="sl-title">{{ 'admin.crisis.title' | transloco }}</h1>
    <p class="sl-muted subtitle">{{ onCallLine() }}</p>
    @if (!canAct()) {
      <p class="sl-meta">{{ 'admin.crisis.viewOnly' | transloco }}</p>
    }

    <div class="sl-tabs" role="tablist">
      @for (s of statuses; track s) {
        <button role="tab" type="button" [attr.aria-selected]="s === status()" (click)="pick(s)">
          {{ 'admin.crisis.tab_' + s | transloco: { count: count(s) } }}
        </button>
      }
    </div>

    <div class="layout" [class.has-detail]="selected() !== null">
      <section class="list" aria-live="polite" [attr.aria-busy]="loading()">
        @if (error()) {
          <div class="sl-card state">
            <p>{{ 'common.checkConnection' | transloco }}</p>
            <button class="sl-button" type="button" (click)="load()">
              {{ 'common.tryAgain' | transloco }}
            </button>
          </div>
        } @else if (loading() && alerts() === null) {
          <p class="sl-card state sl-muted">
            {{ 'common.loading' | transloco }}
          </p>
        } @else if (alerts()?.length === 0) {
          <p class="sl-card state sl-muted">
            {{
              (status() === 'CLOSED' ? 'admin.crisis.emptyClosed' : 'admin.crisis.empty')
                | transloco
            }}
          </p>
        } @else {
          <ul class="alerts">
            @for (a of alerts() ?? []; track a.id) {
              <li>
                <button
                  type="button"
                  class="sl-card alert"
                  [class.alert--urgent]="!a.acknowledgedAt && a.status !== 'CLOSED'"
                  [attr.aria-current]="a.id === selected()?.id"
                  (click)="open(a)"
                >
                  <span class="row">
                    <strong>{{ 'admin.crisis.category.' + a.category | transloco }}</strong>
                    <span
                      class="sl-pill"
                      [class.sl-pill--danger]="!a.acknowledgedAt && a.status !== 'CLOSED'"
                      >{{ age(a) }}</span
                    >
                  </span>
                  <span>{{ person(a) }}</span>
                  <span class="sl-meta">{{ origin(a) }}</span>
                </button>
              </li>
            }
          </ul>
        }
      </section>

      <section class="detail" aria-live="polite">
        @if (selected(); as a) {
          <button
            type="button"
            class="sl-button sl-button--quiet back"
            (click)="selected.set(null)"
          >
            {{ 'admin.crisis.back' | transloco }}
          </button>
          <div class="sl-card">
            <div class="head">
              <div>
                <h2 class="heading">
                  {{ person(a) }} ·
                  {{ 'admin.crisis.category.' + a.category | transloco }}
                </h2>
                <p class="sl-meta">{{ timeline(a) }}</p>
              </div>
              @if (canAct() && !a.acknowledgedAt && a.status !== 'CLOSED') {
                <button
                  type="button"
                  class="sl-button sl-button--primary"
                  [disabled]="busy()"
                  (click)="acknowledge(a)"
                >
                  {{ 'admin.crisis.acknowledge' | transloco }}
                </button>
              }
            </div>

            @if (a.text) {
              <p class="label">
                {{ 'admin.crisis.flaggedText' | transloco: { language: language(a) } }}
              </p>
              <blockquote class="flagged" [attr.lang]="a.language">« {{ a.text }} »</blockquote>
              <p class="sl-meta">
                {{ 'admin.crisis.flaggedNote' | transloco }}
              </p>
            }

            <div class="facts">
              <div>
                <p class="label">{{ 'admin.crisis.contact' | transloco }}</p>
                @if (phone(); as number) {
                  <a class="sl-button sl-button--primary" [href]="'tel:' + number">
                    {{ 'admin.crisis.call' | transloco: { phone: number } }}
                  </a>
                } @else if (a.contact) {
                  <p>{{ a.contact }}</p>
                  @if (canAct() && a.status !== 'CLOSED') {
                    <button type="button" class="sl-button" [disabled]="busy()" (click)="reveal(a)">
                      {{ 'admin.crisis.reveal' | transloco }}
                    </button>
                  }
                } @else {
                  <p class="sl-muted">
                    {{ 'admin.crisis.noContact' | transloco }}
                  </p>
                }
              </div>
              <div>
                <p class="label">
                  {{ 'admin.crisis.nextSession' | transloco }}
                </p>
                @if (a.nextSession; as s) {
                  <p>
                    {{ when(s.at) }} · {{ 'admin.modality.' + s.modality | transloco }} ·
                    {{ capital(name(s.sessionLanguage)) }}
                  </p>
                  <p class="sl-muted">{{ s.specialistName }}</p>
                } @else {
                  <p class="sl-muted">
                    {{ 'admin.crisis.noSession' | transloco }}
                  </p>
                }
              </div>
            </div>

            @if (a.status === 'CLOSED') {
              <p class="label">{{ 'admin.crisis.outcome' | transloco }}</p>
              @if (a.outcome) {
                <p>{{ 'admin.crisis.outcomes.' + a.outcome | transloco }}</p>
              }
              @if (a.note) {
                <p class="sl-muted">{{ a.note }}</p>
              }
              <p class="sl-meta">
                {{ 'admin.crisis.closed' | transloco: { time: time(a.closedAt) } }}
              </p>
            } @else if (canAct() && a.acknowledgedAt) {
              <fieldset class="outcomes">
                <legend class="label">
                  {{ 'admin.crisis.outcome' | transloco }}
                </legend>
                @for (o of outcomes; track o) {
                  <label class="choice">
                    <input type="radio" name="outcome" [value]="o" [(ngModel)]="outcome" />
                    {{ 'admin.crisis.outcomes.' + o | transloco }}
                  </label>
                }
              </fieldset>
              <label class="sl-field">
                <span class="label">{{ 'admin.crisis.note' | transloco }}</span>
                <textarea
                  class="sl-textarea"
                  rows="3"
                  maxlength="2000"
                  [(ngModel)]="note"
                ></textarea>
              </label>
              <button
                type="button"
                class="sl-button sl-button--primary"
                [disabled]="!outcome() || busy()"
                (click)="confirm()"
              >
                {{ 'admin.crisis.close' | transloco }}
              </button>
            }
            @if (problem()) {
              <p class="sl-meta problem" role="alert">{{ problem() }}</p>
            }
          </div>
        } @else {
          <p class="sl-card state sl-muted select">
            {{ 'admin.crisis.select' | transloco }}
          </p>
        }
      </section>
    </div>

    <dialog #closeDialog class="sl-dialog">
      <p>{{ 'admin.crisis.confirmClose' | transloco }}</p>
      <div class="dialog-actions">
        <button type="button" class="sl-button" (click)="closeDialog.close()">
          {{ 'admin.common.cancel' | transloco }}
        </button>
        <button type="button" class="sl-button sl-button--primary" (click)="close()">
          {{ 'admin.crisis.close' | transloco }}
        </button>
      </div>
    </dialog>
  `,
  styles: `
    .subtitle {
      margin: var(--space-4) 0 var(--space-16);
    }
    .sl-tabs {
      margin-bottom: var(--space-16);
    }
    .layout {
      display: grid;
      grid-template-columns: minmax(280px, 380px) 1fr;
      gap: var(--space-20);
      align-items: start;
    }
    .alerts {
      list-style: none;
      margin: 0;
      padding: 0;
      display: grid;
      gap: var(--space-8);
    }
    .alert {
      display: grid;
      gap: var(--space-4);
      width: 100%;
      text-align: left;
      font: inherit;
      color: inherit;
      cursor: pointer;
      min-height: var(--size-tap);
    }
    .alert--urgent {
      border-color: var(--danger);
    }
    .alert[aria-current='true'] {
      outline: 2px solid var(--brand);
    }
    .row,
    .head {
      display: flex;
      justify-content: space-between;
      align-items: flex-start;
      gap: var(--space-12);
    }
    .heading {
      margin: 0;
      font-size: var(--type-title-md-size);
    }
    .label {
      margin: var(--space-16) 0 var(--space-4);
      font-size: var(--type-overline-size);
      font-weight: 700;
      letter-spacing: var(--type-overline-letter-spacing);
      color: var(--ink-muted);
      text-transform: uppercase;
    }
    .flagged {
      margin: 0 0 var(--space-8);
      padding: var(--space-12) var(--space-16);
      border-left: 3px solid var(--danger);
      background: var(--surface-page);
      font-size: var(--type-body-admin-size);
    }
    .facts {
      display: grid;
      grid-template-columns: 1fr 1fr;
      gap: var(--space-20);
    }
    .outcomes {
      border: 0;
      padding: 0;
      margin: 0;
      display: grid;
      gap: var(--space-8);
    }
    .choice {
      display: flex;
      align-items: center;
      gap: var(--space-8);
      min-height: var(--size-tap);
    }
    .state {
      margin: 0;
    }
    .back {
      display: none;
    }
    .problem {
      color: var(--danger);
    }
    .dialog-actions {
      display: flex;
      justify-content: flex-end;
      gap: var(--space-8);
    }
    /* On-call responders use the queue on a phone (CrisisPhone.dc.html): one column at a time. */
    @media (max-width: 760px) {
      .layout {
        grid-template-columns: 1fr;
      }
      .layout.has-detail .list,
      .layout:not(.has-detail) .detail {
        display: none;
      }
      .back {
        display: inline-flex;
        margin-bottom: var(--space-8);
      }
      .facts {
        grid-template-columns: 1fr;
      }
    }
  `,
})
export class CrisisQueuePage {
  private readonly api = inject(CoreApi);
  private readonly transloco = inject(TranslocoService);
  private readonly staff = inject(StaffSession);
  protected readonly watch = inject(CrisisWatch);
  private readonly closeDialog = viewChild<ElementRef<HTMLDialogElement>>('closeDialog');

  readonly statuses = STATUSES;
  readonly outcomes = OUTCOMES;
  readonly status = signal<Status>('OPEN');
  readonly alerts = signal<Alert[] | null>(null);
  readonly selected = signal<Alert | null>(null);
  readonly phone = signal<string | null>(null);
  readonly loading = signal(false);
  readonly error = signal(false);
  readonly busy = signal(false);
  readonly problem = signal<string | null>(null);
  readonly outcome = signal<Outcome | null>(null);
  readonly note = signal('');
  private readonly lang = signal(this.transloco.getActiveLang());
  readonly canAct = computed(() => this.staff.accessTo('crisis') === 'F');

  readonly onCallLine = computed(() => {
    this.lang();
    const s = this.watch.summary();
    if (!s?.primary) return this.transloco.translate('admin.crisis.noRota');
    return this.transloco.translate('admin.crisis.onCall', {
      primary: s.primary.name,
      until: clock(s.primary.endsAt, this.lang()),
      backup: s.backup?.name ?? '—',
    });
  });

  constructor() {
    this.transloco.langChanges$.subscribe((lang) => this.lang.set(lang));
    effect(() => {
      this.status();
      void this.load();
    });
    void this.watch.refresh();
  }

  pick(status: Status) {
    this.status.set(status);
    this.selected.set(null);
  }

  async load() {
    this.loading.set(true);
    this.error.set(false);
    try {
      this.alerts.set(
        orThrow(
          await this.api.client.GET('/admin/v1/crisis/alerts', {
            params: { query: { status: this.status() } },
          }),
        ),
      );
    } catch {
      this.error.set(true);
    } finally {
      this.loading.set(false);
    }
  }

  /** The detail carries the flagged text; Core audits every view. */
  async open(alert: Alert) {
    this.phone.set(null);
    this.problem.set(null);
    this.outcome.set(null);
    this.note.set('');
    this.selected.set(alert);
    try {
      this.selected.set(
        orThrow(
          await this.api.client.GET('/admin/v1/crisis/alerts/{alertId}', {
            params: { path: { alertId: alert.id } },
          }),
        ),
      );
    } catch (e) {
      this.say(e);
    }
  }

  async acknowledge(alert: Alert) {
    await this.act(async () => {
      this.selected.set(
        orThrow(
          await this.api.client.POST('/admin/v1/crisis/alerts/{alertId}/acknowledgement', {
            params: { path: { alertId: alert.id } },
          }),
        ),
      );
    });
  }

  async reveal(alert: Alert) {
    await this.act(async () => {
      const { phone } = orThrow(
        await this.api.client.POST('/admin/v1/crisis/alerts/{alertId}/contact', {
          params: { path: { alertId: alert.id } },
        }),
      );
      this.phone.set(phone);
    });
  }

  confirm() {
    this.closeDialog()?.nativeElement.showModal();
  }

  async close() {
    this.closeDialog()?.nativeElement.close();
    const alert = this.selected();
    const outcome = this.outcome();
    if (!alert || !outcome) return;
    await this.act(async () => {
      this.selected.set(
        orThrow(
          await this.api.client.POST('/admin/v1/crisis/alerts/{alertId}/closure', {
            params: { path: { alertId: alert.id } },
            body: { outcome, note: this.note().trim() || undefined },
          }),
        ),
      );
    });
  }

  private async act(work: () => Promise<void>) {
    this.busy.set(true);
    this.problem.set(null);
    try {
      await work();
      await Promise.all([this.load(), this.watch.refresh()]);
    } catch (e) {
      this.say(e);
    } finally {
      this.busy.set(false);
    }
  }

  private say(e: unknown) {
    this.problem.set(
      e instanceof ApiProblem && e.title
        ? e.title
        : this.transloco.translate('common.checkConnection'),
    );
  }

  count(status: Status): number {
    const s = this.watch.summary();
    if (!s || status === 'CLOSED') return 0;
    if (status === this.status() && this.alerts()) return this.alerts()!.length;
    return status === 'OPEN' ? s.unacknowledged : Math.max(0, s.open - s.unacknowledged);
  }

  person(alert: Alert): string {
    const guest = alert.personRef.startsWith('G-');
    return this.transloco.translate(guest ? 'admin.crisis.guest' : 'admin.crisis.seeker', {
      ref: guest ? `#${alert.personRef}` : alert.personRef,
    });
  }

  origin(alert: Alert): string {
    const from = this.transloco.translate(`admin.crisis.from_${alert.source}`);
    return alert.language ? `${from} · ${this.capital(this.name(alert.language))}` : from;
  }

  age(alert: Alert): string {
    if (alert.status === 'CLOSED') {
      return this.transloco.translate('admin.crisis.closedOn', {
        date: shortDate(alert.closedAt, this.lang()),
      });
    }
    const minutes = this.transloco.translate('admin.crisis.minutes', {
      count: minutesSince(alert.raisedAt),
    });
    const state = alert.acknowledgedAt
      ? this.transloco.translate('admin.crisis.with', {
          name: alert.acknowledgedBy ?? '',
        })
      : this.transloco.translate('admin.crisis.notAcknowledged');
    return `${minutes} · ${state}`;
  }

  timeline(alert: Alert): string {
    const t = (key: string, params: Record<string, string>) =>
      this.transloco.translate(`admin.crisis.${key}`, params);
    const parts = [t('raised', { time: this.time(alert.raisedAt) })];
    if (alert.escalatedToBackupAt) {
      parts.push(t('escalatedBackup', { time: this.time(alert.escalatedToBackupAt) }));
    }
    if (alert.escalatedToAdminsAt) {
      parts.push(t('escalatedAdmins', { time: this.time(alert.escalatedToAdminsAt) }));
    }
    if (alert.acknowledgedAt) {
      parts.push(
        t('acknowledgedBy', {
          name: alert.acknowledgedBy ?? '',
          time: this.time(alert.acknowledgedAt),
        }),
      );
    }
    return parts.join(' · ');
  }

  language(alert: Alert): string {
    return alert.language ? this.capital(this.name(alert.language)) : '';
  }

  time(iso: string | undefined): string {
    return clock(iso, this.lang());
  }

  when(iso: string): string {
    return dayAndTime(iso, this.lang());
  }

  name(code: string): string {
    return languageName(code, this.lang());
  }

  capital(text: string): string {
    return capitalised(text);
  }
}
