import {
  ChangeDetectionStrategy,
  Component,
  computed,
  effect,
  type ElementRef,
  inject,
  input,
  signal,
  untracked,
  viewChild,
} from '@angular/core';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { TranslocoPipe, TranslocoService } from '@jsverse/transloco';

import { ApiProblem, CoreApi, type CoreSchemas, orThrow } from '../api/core-api';
import { StaffSession } from '../auth/staff-session';
import { calendarDate, dayAndTime, fcfa, languageName, shortDate } from '../shared/format';
import { personLabel } from './people.page';

type Person = CoreSchemas['PersonRecord'];
type Contact = { phone?: string; email?: string };
type Ask = 'REVEAL' | 'EXPORT' | 'ERASE';

/**
 * Admin screen 10: one person's account, bookings, consents and data requests
 * (PersonRecord.dc.html; D45). Opening it is audited. Tracking entries, notes, intake answers and
 * transcripts are never shown here.
 */
@Component({
  selector: 'sl-person-record',
  imports: [TranslocoPipe, FormsModule, RouterLink],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <a class="sl-meta back" routerLink="/people">{{ 'admin.people.title' | transloco }}</a>

    @if (error()) {
      <div class="sl-card">
        <p>{{ 'common.checkConnection' | transloco }}</p>
        <button class="sl-button" type="button" (click)="load()">
          {{ 'common.tryAgain' | transloco }}
        </button>
      </div>
    } @else if (person(); as p) {
      <div class="head">
        <div>
          <h1 class="sl-title">{{ label(p.ref) }}</h1>
          <p class="sl-muted">{{ summary(p) }}</p>
          @if (contact(); as c) {
            <p class="revealed" role="status">{{ c.phone ?? '' }} {{ c.email ?? '' }}</p>
          }
        </div>
        @if (!contact() && p.contact) {
          <button type="button" class="sl-button" (click)="ask('REVEAL')">
            {{ 'admin.people.reveal' | transloco }}
          </button>
        }
      </div>
      <p class="sl-meta">{{ 'admin.people.noClinical' | transloco }}</p>

      <section class="sl-card table-card" aria-labelledby="bookings-title">
        <h2 id="bookings-title" class="section pad">{{ 'admin.people.bookings' | transloco }}</h2>
        @if (p.bookingList.length === 0) {
          <p class="sl-muted pad">{{ 'admin.people.noBookings' | transloco }}</p>
        } @else {
          <table class="sl-table">
            <thead>
              <tr>
                <th scope="col">{{ 'admin.bookings.when' | transloco }}</th>
                <th scope="col">{{ 'admin.bookings.specialist' | transloco }}</th>
                <th scope="col">{{ 'admin.bookings.type' | transloco }}</th>
                <th scope="col">{{ 'admin.bookings.status' | transloco }}</th>
                <th scope="col">{{ 'admin.people.paid' | transloco }}</th>
              </tr>
            </thead>
            <tbody>
              @for (b of p.bookingList; track b.id) {
                <tr>
                  <td>
                    <a [routerLink]="['/bookings', b.id]">{{ when(b.startsAt) }}</a>
                  </td>
                  <td>{{ b.specialist }}</td>
                  <td>
                    {{ 'admin.modality.' + b.modality | transloco }} ·
                    {{ lower(b.sessionLanguage) }}
                  </td>
                  <td>{{ 'admin.bookings.state.' + b.status | transloco }}</td>
                  <td>{{ money(b.price) }}</td>
                </tr>
              }
            </tbody>
          </table>
        }
      </section>

      @if (p.type === 'ACCOUNT') {
        <section class="sl-card table-card" aria-labelledby="consents-title">
          <h2 id="consents-title" class="section pad">{{ 'admin.people.consents' | transloco }}</h2>
          <table class="sl-table">
            <thead>
              <tr>
                <th scope="col">{{ 'admin.people.purpose' | transloco }}</th>
                <th scope="col">{{ 'admin.people.state' | transloco }}</th>
                <th scope="col">{{ 'admin.people.version' | transloco }}</th>
                <th scope="col">{{ 'admin.people.changed' | transloco }}</th>
              </tr>
            </thead>
            <tbody>
              @for (c of p.consents; track c.purpose) {
                <tr>
                  <td>{{ 'admin.people.purposes.' + c.purpose | transloco }}</td>
                  <td>{{ (c.granted ? 'admin.people.on' : 'admin.people.off') | transloco }}</td>
                  <td>{{ c.version }}</td>
                  <td>{{ day(c.recordedAt) }}</td>
                </tr>
              }
            </tbody>
          </table>
          <p class="sl-meta pad">{{ 'admin.people.consentsNote' | transloco }}</p>
        </section>
      }

      <section class="sl-card" aria-labelledby="requests-title">
        <h2 id="requests-title" class="section">{{ 'admin.people.dataRequests' | transloco }}</h2>
        @for (r of p.dataRequests; track r.id) {
          <p>
            <span class="strong">{{
              'admin.people.requested.' + r.kind | transloco: { date: day(r.requestedAt) }
            }}</span>
            · {{ 'admin.people.requestStatus.' + r.status | transloco }} ·
            {{ 'admin.people.dueBy' | transloco: { date: day(r.dueAt) } }}
          </p>
        }
        @if (p.type === 'ACCOUNT') {
          <p class="sl-meta">{{ 'admin.people.exportNote' | transloco }}</p>
        }
        @if (canChange()) {
          <div class="actions">
            @if (p.type === 'ACCOUNT' && p.status !== 'ERASED') {
              <button type="button" class="sl-button" (click)="ask('EXPORT')">
                {{ 'admin.people.prepareExport' | transloco }}
              </button>
            }
          </div>
          <h3 class="sub">{{ 'admin.people.erasure' | transloco }}</h3>
          <p class="sl-meta">{{ 'admin.people.erasureNote' | transloco }}</p>
          @if (!p.canErase && p.status !== 'ERASED') {
            <p class="sl-meta">{{ 'admin.people.cannotErase' | transloco }}</p>
          }
          <div class="actions">
            <button
              type="button"
              class="sl-button sl-button--danger"
              [disabled]="!p.canErase"
              (click)="ask('ERASE')"
            >
              {{ 'admin.people.startErasure' | transloco }}
            </button>
          </div>
        }
      </section>

      @if (p.openCrisisAlerts > 0) {
        <section class="sl-card safety" aria-labelledby="safety-title">
          <h2 id="safety-title" class="section">{{ 'admin.people.safety' | transloco }}</h2>
          <p>
            {{
              (p.openCrisisAlerts === 1 ? 'admin.people.openAlert' : 'admin.people.openAlerts')
                | transloco: { count: p.openCrisisAlerts }
            }}
          </p>
          <a class="sl-button" routerLink="/crisis">{{ 'admin.people.openCrisis' | transloco }}</a>
        </section>
      }
      @if (problem()) {
        <p class="sl-meta problem" role="alert">{{ problem() }}</p>
      }
    } @else {
      <p class="sl-card sl-muted">{{ 'common.loading' | transloco }}</p>
    }

    <dialog #askDialog class="sl-dialog" aria-labelledby="ask-title">
      <h2 id="ask-title" class="section">
        {{ 'admin.people.confirm.' + (asking() ?? 'REVEAL') | transloco }}
      </h2>
      <label class="sl-field">
        <span>{{ 'admin.bookings.reasonRequired' | transloco }}</span>
        <textarea class="sl-textarea" rows="2" maxlength="1000" [(ngModel)]="reason"></textarea>
      </label>
      <div class="actions">
        <button type="button" class="sl-button" (click)="askDialog.close()">
          {{ 'admin.common.cancel' | transloco }}
        </button>
        <button
          type="button"
          class="sl-button"
          [class.sl-button--danger]="asking() === 'ERASE'"
          [class.sl-button--primary]="asking() !== 'ERASE'"
          [disabled]="busy() || !reason().trim()"
          (click)="confirm()"
        >
          {{ 'admin.common.confirm' | transloco }}
        </button>
      </div>
    </dialog>
  `,
  styles: `
    .back {
      display: inline-block;
      margin-bottom: var(--space-8);
    }
    .head {
      display: flex;
      justify-content: space-between;
      align-items: start;
      gap: var(--space-12);
    }
    .head p {
      margin: var(--space-4) 0 0;
    }
    .revealed {
      font-weight: 700;
    }
    .sl-card {
      margin-top: var(--space-16);
    }
    .table-card {
      padding: 0;
      overflow-x: auto;
    }
    .pad {
      padding: var(--space-16) var(--space-16) 0;
    }
    .section {
      margin: 0 0 var(--space-8);
      font-size: var(--type-title-md-size);
    }
    .sub {
      margin: var(--space-16) 0 var(--space-4);
      font-size: var(--type-body-admin-size);
    }
    .strong {
      font-weight: 700;
    }
    .actions {
      display: flex;
      justify-content: flex-end;
      gap: var(--space-8);
      margin-top: var(--space-12);
    }
    .safety {
      border-color: var(--danger);
    }
    .problem {
      color: var(--danger);
    }
  `,
})
export class PersonRecordPage {
  private readonly api = inject(CoreApi);
  private readonly transloco = inject(TranslocoService);
  private readonly staff = inject(StaffSession);
  private readonly askDialog = viewChild<ElementRef<HTMLDialogElement>>('askDialog');

  readonly id = input.required<string>();
  readonly person = signal<Person | null>(null);
  readonly contact = signal<Contact | null>(null);
  readonly error = signal(false);
  readonly busy = signal(false);
  readonly problem = signal<string | null>(null);
  readonly asking = signal<Ask | null>(null);
  readonly reason = signal('');
  readonly canChange = computed(() => this.staff.accessTo('people') === 'F');
  private readonly lang = signal(this.transloco.getActiveLang());

  constructor() {
    this.transloco.langChanges$.subscribe((lang) => this.lang.set(lang));
    effect(() => {
      this.id();
      void untracked(() => this.load());
    });
  }

  async load() {
    this.error.set(false);
    try {
      this.person.set(
        orThrow(
          await this.api.client.GET('/admin/v1/people/{personId}', {
            params: { path: { personId: this.id() } },
          }),
        ),
      );
    } catch {
      this.error.set(true);
    }
  }

  ask(what: Ask) {
    this.asking.set(what);
    this.reason.set('');
    this.askDialog()?.nativeElement.showModal();
  }

  async confirm() {
    const what = this.asking();
    this.askDialog()?.nativeElement.close();
    const params = { path: { personId: this.id() } };
    const body = { reason: this.reason().trim() };
    this.busy.set(true);
    this.problem.set(null);
    try {
      if (what === 'REVEAL') {
        this.contact.set(
          orThrow(
            await this.api.client.POST('/admin/v1/people/{personId}/contact-reveal', {
              params,
              body,
            }),
          ),
        );
      } else if (what === 'EXPORT') {
        orThrow(
          await this.api.client.POST('/admin/v1/people/{personId}/data-export', { params, body }),
        );
        await this.load();
      } else if (what === 'ERASE') {
        this.person.set(
          orThrow(
            await this.api.client.POST('/admin/v1/people/{personId}/erasure', { params, body }),
          ),
        );
        this.contact.set(null);
      }
    } catch (e) {
      this.problem.set(
        e instanceof ApiProblem && e.title
          ? e.title
          : this.transloco.translate('common.checkConnection'),
      );
    } finally {
      this.busy.set(false);
    }
  }

  label(ref: string): string {
    return personLabel(this.transloco, ref);
  }

  summary(p: Person): string {
    const lang = this.lang();
    return this.transloco.translate(
      p.type === 'GUEST' ? 'admin.people.guestSummary' : 'admin.people.accountSummary',
      {
        date: calendarDate(p.since.slice(0, 10), lang),
        language: languageName(p.appLanguage, lang),
        contact: p.contact,
      },
    );
  }

  when(iso: string): string {
    return dayAndTime(iso, this.lang());
  }

  day(iso: string | undefined): string {
    return iso ? shortDate(iso, this.lang()) : '';
  }

  money(n: number): string {
    return fcfa(n, this.lang());
  }

  lower(code: string): string {
    return languageName(code, this.lang());
  }
}
