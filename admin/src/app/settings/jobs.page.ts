import {
  ChangeDetectionStrategy,
  Component,
  computed,
  type ElementRef,
  inject,
  signal,
  viewChild,
} from '@angular/core';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { TranslocoPipe, TranslocoService } from '@jsverse/transloco';

import { AiApi } from '../api/ai-api';
import { ApiProblem, CoreApi, type CoreSchemas, orThrow } from '../api/core-api';
import { TrackingApi } from '../api/tracking-api';
import { StaffSession } from '../auth/staff-session';
import { dateTime } from '../shared/format';

type Job = CoreSchemas['JobTrigger'];
type Service = 'core' | 'tracking' | 'ai';

/**
 * Every scheduled job of Core, Tracking and AI, from each service's trigger table (D61), opened
 * from Settings and flags. Operations look; a Super admin pauses, resumes or reschedules a job
 * with a reason. A change is saved at once and followed from the service's next restart. Jobs
 * that safety or money depend on carry a warning, but can still be changed.
 */
@Component({
  selector: 'sl-jobs',
  imports: [TranslocoPipe, FormsModule, RouterLink],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <a routerLink="/settings" class="sl-meta">{{ 'admin.senders.back' | transloco }}</a>
    <h1 class="sl-title">{{ 'admin.jobs.title' | transloco }}</h1>
    <p class="sl-muted subtitle">{{ 'admin.jobs.subtitle' | transloco }}</p>

    <div class="tabs" role="tablist">
      @for (s of services; track s) {
        <button
          type="button"
          role="tab"
          class="sl-button"
          [class.sl-button--primary]="service() === s"
          [attr.aria-selected]="service() === s"
          (click)="show(s)"
        >
          {{ 'admin.jobs.service.' + s | transloco }}
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
    } @else if (jobs(); as list) {
      <section class="sl-card" [attr.aria-label]="'admin.jobs.service.' + service() | transloco">
        <ul class="rows">
          @for (j of list; track j.name) {
            <li class="row">
              <div>
                <p class="label">{{ j.description }}</p>
                <p class="sl-meta">
                  {{ j.name }} · {{ scheduleOf(j) }}
                  @if (j.lastFinishedAt) {
                    · {{ 'admin.jobs.last' | transloco: { when: when(j.lastFinishedAt), outcome: j.lastOutcome } }}
                  }
                </p>
                @if (j.pendingRestart) {
                  <p class="sl-meta pending">{{ 'admin.jobs.pending' | transloco }}</p>
                }
                @if (j.caution) {
                  <p class="sl-meta">{{ 'admin.jobs.caution' | transloco }}</p>
                }
              </div>
              <span class="sl-pill">{{ 'admin.jobs.status.' + j.status | transloco }}</span>
              @if (canChange()) {
                <button type="button" class="sl-button" (click)="ask(j)">
                  {{ 'admin.settings.change' | transloco }}
                </button>
              } @else {
                <span></span>
              }
            </li>
          }
        </ul>
      </section>
    } @else {
      <p class="sl-card sl-muted">{{ 'common.loading' | transloco }}</p>
    }

    <dialog #jobDialog class="sl-dialog" aria-labelledby="job-title">
      @if (editing(); as j) {
        <h2 id="job-title" class="section">{{ j.description }}</h2>
        @if (j.caution) {
          <p class="sl-meta warning" role="note">{{ 'admin.jobs.cautionLong' | transloco }}</p>
        }
        <label class="sl-field">
          <span>{{ 'admin.providers.statusLabel' | transloco }}</span>
          <select class="sl-input" [(ngModel)]="status">
            <option value="ACTIVE">{{ 'admin.jobs.status.ACTIVE' | transloco }}</option>
            <option value="PAUSED">{{ 'admin.jobs.status.PAUSED' | transloco }}</option>
          </select>
        </label>
        <label class="sl-field">
          <span>{{ (j.kind === 'CRON' ? 'admin.jobs.cron' : 'admin.jobs.interval') | transloco }}</span>
          <input class="sl-input" [(ngModel)]="schedule" maxlength="120" />
        </label>
        @if (j.kind === 'CRON') {
          <label class="sl-field">
            <span>{{ 'admin.jobs.zone' | transloco }}</span>
            <input class="sl-input" [(ngModel)]="zone" maxlength="60" />
          </label>
        }
        <p class="sl-meta">{{ 'admin.jobs.restart' | transloco }}</p>
        <label class="sl-field">
          <span>{{ 'admin.bookings.reasonRequired' | transloco }}</span>
          <textarea class="sl-textarea" rows="3" maxlength="500" [(ngModel)]="reason"></textarea>
        </label>
      }
      @if (problem()) {
        <p class="sl-meta problem" role="alert">{{ problem() }}</p>
      }
      <div class="actions">
        <button type="button" class="sl-button" (click)="jobDialog.close()">
          {{ 'admin.common.cancel' | transloco }}
        </button>
        <button
          type="button"
          class="sl-button sl-button--primary"
          [disabled]="busy() || !reason().trim() || !schedule().trim()"
          (click)="save()"
        >
          {{ 'admin.common.save' | transloco }}
        </button>
      </div>
    </dialog>
  `,
  styles: `
    .subtitle {
      margin: var(--space-4) 0 var(--space-16);
      max-width: 72ch;
    }
    .tabs {
      display: flex;
      gap: var(--space-8);
      margin-bottom: var(--space-16);
    }
    .section {
      margin: 0 0 var(--space-12);
      font-size: var(--type-title-md-size);
    }
    .rows {
      list-style: none;
      margin: 0;
      padding: 0;
    }
    .row {
      display: grid;
      grid-template-columns: minmax(0, 1fr) 7rem 7rem;
      gap: var(--space-12);
      align-items: center;
      min-height: 44px;
      padding: var(--space-8) 0;
      border-top: 1px solid var(--line);
    }
    .row p {
      margin: 0;
    }
    .label {
      font-weight: 600;
    }
    .pending,
    .warning {
      font-weight: 600;
    }
    .actions {
      display: flex;
      justify-content: flex-end;
      gap: var(--space-8);
      margin-top: var(--space-12);
    }
    .problem {
      color: var(--danger);
    }
  `,
})
export class JobsPage {
  private readonly core = inject(CoreApi);
  private readonly tracking = inject(TrackingApi);
  private readonly ai = inject(AiApi);
  private readonly transloco = inject(TranslocoService);
  private readonly staff = inject(StaffSession);
  private readonly jobDialog = viewChild<ElementRef<HTMLDialogElement>>('jobDialog');

  readonly services: Service[] = ['core', 'tracking', 'ai'];
  readonly service = signal<Service>('core');
  readonly jobs = signal<Job[] | null>(null);
  readonly error = signal(false);
  readonly busy = signal(false);
  readonly problem = signal<string | null>(null);
  readonly editing = signal<Job | null>(null);
  readonly status = signal('ACTIVE');
  readonly schedule = signal('');
  readonly zone = signal('');
  readonly reason = signal('');
  readonly canChange = computed(() => this.staff.accessTo('settings') === 'F');
  private readonly lang = signal(this.transloco.getActiveLang());

  constructor() {
    this.transloco.langChanges$.subscribe((lang) => this.lang.set(lang));
    void this.load();
  }

  /** The three clients share the jobs paths; their types differ only in name. */
  private client() {
    const s = this.service();
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    return (s === 'core' ? this.core.client : s === 'tracking' ? this.tracking.client : this.ai.client) as any;
  }

  show(s: Service) {
    this.service.set(s);
    this.jobs.set(null);
    void this.load();
  }

  async load() {
    this.error.set(false);
    try {
      this.jobs.set(orThrow(await this.client().GET('/admin/v1/jobs')) as Job[]);
    } catch {
      this.error.set(true);
    }
  }

  /** "every PT30S", "0 30 2 * * * (Africa/Douala)". */
  scheduleOf(j: Job): string {
    return j.kind === 'CRON'
      ? `${j.schedule}${j.zone ? ` (${j.zone})` : ''}`
      : this.transloco.translate('admin.jobs.every', { interval: j.schedule });
  }

  when(iso: string | undefined): string {
    return dateTime(iso, this.lang());
  }

  ask(j: Job) {
    this.editing.set(j);
    this.status.set(j.status);
    this.schedule.set(j.schedule);
    this.zone.set(j.zone ?? '');
    this.reason.set('');
    this.problem.set(null);
    this.jobDialog()?.nativeElement.showModal();
  }

  async save() {
    const j = this.editing();
    if (!j) return;
    this.busy.set(true);
    this.problem.set(null);
    try {
      orThrow(
        await this.client().PUT('/admin/v1/jobs/{name}', {
          params: { path: { name: j.name } },
          body: {
            version: j.version,
            status: this.status(),
            schedule: this.schedule().trim(),
            zone: this.zone().trim() || undefined,
            reason: this.reason().trim(),
          },
        }),
      );
      this.jobDialog()?.nativeElement.close();
      await this.load();
    } catch (e) {
      this.problem.set(
        e instanceof ApiProblem && e.title ? e.title : this.transloco.translate('common.checkConnection'),
      );
    } finally {
      this.busy.set(false);
    }
  }
}
