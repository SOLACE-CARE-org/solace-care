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
import { clock, dayAndTime, fcfa, languageName, shortDate } from '../shared/format';

type Booking = CoreSchemas['AdminBooking'];
type Outcome = CoreSchemas['DisputeOutcome'];
type Entry = CoreSchemas['BookingTimelineEntry'];

const OUTCOMES: Outcome[] = ['SPECIALIST_NO_SHOW', 'SEEKER_NO_SHOW', 'CONNECTION_PROBLEM'];
const UNSETTLED = ['CONFIRMED', 'IN_PROGRESS'];

/**
 * Admin screen 8: one booking's timeline, money and policy, and the Refund or no-show dispute
 * workflow (BookingDetail.dc.html; D41). Any refund then waits for a second approver in Finance
 * (D14). People appear by role and pseudonymous reference only.
 */
@Component({
  selector: 'sl-booking-detail',
  imports: [TranslocoPipe, FormsModule, RouterLink],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <a class="sl-meta back" routerLink="/bookings">{{ 'admin.bookings.title' | transloco }}</a>

    @if (error()) {
      <div class="sl-card">
        <p>{{ 'common.checkConnection' | transloco }}</p>
        <button class="sl-button" type="button" (click)="load()">
          {{ 'common.tryAgain' | transloco }}
        </button>
      </div>
    } @else if (booking(); as b) {
      <div class="head">
        <div>
          <h1 class="sl-title">
            {{ b.reference }} · {{ 'admin.modality.' + b.modality | transloco }},
            {{ lower(b.sessionLanguage) }}
          </h1>
          <p class="sl-muted">{{ summary(b) }}</p>
        </div>
        <span class="sl-pill" [class.sl-pill--danger]="b.disputed">{{ statusLabel(b) }}</span>
      </div>

      <div class="columns">
        <section class="sl-card" aria-labelledby="timeline-title">
          <h2 id="timeline-title" class="section">
            {{ 'admin.bookings.timeline' | transloco }}
          </h2>
          <ol class="timeline">
            @for (e of b.timeline; track $index) {
              <li>
                <span class="sl-meta at">{{ at(e.at) }}</span>
                <span>{{ entry(e) }}</span>
              </li>
            }
          </ol>
        </section>

        <div class="side">
          <section class="sl-card" aria-labelledby="money-title">
            <h2 id="money-title" class="section">
              {{ 'admin.bookings.money' | transloco }}
            </h2>
            <dl class="facts">
              <dt>{{ 'admin.bookings.captured' | transloco }}</dt>
              <dd>{{ b.money ? money(b.money.amount) : '—' }}</dd>
              <dt>{{ 'admin.bookings.moneyState' | transloco }}</dt>
              <dd>{{ moneyState(b) }}</dd>
              <dt>{{ 'admin.bookings.policy' | transloco }}</dt>
              <dd>
                {{ 'admin.bookings.policyVersion' | transloco: { version: b.policy.version } }}
              </dd>
            </dl>
          </section>

          @if (canChange()) {
            @if (b.disputed) {
              <section class="sl-card" aria-labelledby="resolve-title">
                <h2 id="resolve-title" class="section">
                  {{ 'admin.bookings.resolveTitle' | transloco }}
                </h2>
                @if (b.dispute?.note) {
                  <p class="sl-meta">
                    {{ 'admin.bookings.toldUs' | transloco: { name: b.dispute?.openedBy ?? '' } }}
                    “{{ b.dispute?.note }}”
                  </p>
                }
                <fieldset class="outcomes">
                  <legend>{{ 'admin.bookings.whoMissed' | transloco }}</legend>
                  @for (o of outcomes; track o) {
                    <label class="option">
                      <input
                        type="radio"
                        name="outcome"
                        [value]="o"
                        [ngModel]="outcome()"
                        (ngModelChange)="pick($event)"
                      />
                      <span>{{ 'admin.bookings.outcome.' + o | transloco }}</span>
                    </label>
                  }
                </fieldset>
                @if (outcome(); as o) {
                  <p class="proposal">
                    {{ 'admin.bookings.proposes' | transloco: { amount: money(proposed(b, o)) } }}
                  </p>
                  <p class="sl-meta">
                    {{ 'admin.bookings.changeNote' | transloco }}
                  </p>
                  @if (settled(b) || o === 'CONNECTION_PROBLEM') {
                    <label class="sl-field">
                      <span>{{ 'admin.bookings.amountLabel' | transloco }}</span>
                      <input
                        class="sl-input"
                        type="number"
                        min="0"
                        [max]="b.price"
                        [(ngModel)]="amount"
                      />
                    </label>
                  }
                  <label class="sl-field">
                    <span>{{
                      (changed() ? 'admin.bookings.reasonRequired' : 'admin.common.reason')
                        | transloco
                    }}</span>
                    <textarea class="sl-textarea" rows="3" maxlength="500" [(ngModel)]="reason">
                    </textarea>
                  </label>
                }
                <div class="actions">
                  <button
                    type="button"
                    class="sl-button sl-button--primary"
                    [disabled]="busy() || !outcome() || (changed() && !reason().trim())"
                    (click)="resolve()"
                  >
                    {{ 'admin.bookings.resolve' | transloco }}
                  </button>
                </div>
              </section>
            } @else if (b.status !== 'PENDING_PAYMENT' && b.status !== 'EXPIRED') {
              <section class="sl-card" aria-labelledby="dispute-title">
                <h2 id="dispute-title" class="section">
                  {{ 'admin.bookings.openTitle' | transloco }}
                </h2>
                <label class="sl-field">
                  <span>{{ 'admin.bookings.noteLabel' | transloco }}</span>
                  <textarea class="sl-textarea" rows="3" maxlength="500" [(ngModel)]="note">
                  </textarea>
                </label>
                <div class="actions">
                  <button
                    type="button"
                    class="sl-button"
                    [disabled]="busy() || !note().trim()"
                    (click)="openDispute()"
                  >
                    {{ 'admin.bookings.openDispute' | transloco }}
                  </button>
                </div>
              </section>
            }
            @if (b.canCancel) {
              <button type="button" class="sl-button sl-button--danger" (click)="askCancel()">
                {{ 'admin.bookings.cancel' | transloco }}
              </button>
            }
          }
          @if (problem()) {
            <p class="sl-meta problem" role="alert">{{ problem() }}</p>
          }
        </div>
      </div>
    } @else {
      <p class="sl-card sl-muted">{{ 'common.loading' | transloco }}</p>
    }

    <dialog #cancelDialog class="sl-dialog" aria-labelledby="cancel-title">
      <h2 id="cancel-title" class="section">
        {{ 'admin.bookings.cancelTitle' | transloco }}
      </h2>
      <p class="sl-muted">{{ 'admin.bookings.cancelNote' | transloco }}</p>
      <label class="sl-field">
        <span>{{ 'admin.common.reason' | transloco }}</span>
        <textarea class="sl-textarea" rows="3" maxlength="1000" [(ngModel)]="cancelReason">
        </textarea>
      </label>
      <div class="actions">
        <button type="button" class="sl-button" (click)="cancelDialog.close()">
          {{ 'admin.common.cancel' | transloco }}
        </button>
        <button
          type="button"
          class="sl-button sl-button--danger"
          [disabled]="!cancelReason().trim()"
          (click)="cancel()"
        >
          {{ 'admin.bookings.confirmCancel' | transloco }}
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
      margin-bottom: var(--space-20);
    }
    .head p {
      margin: var(--space-4) 0 0;
    }
    .columns {
      display: grid;
      grid-template-columns: minmax(0, 3fr) minmax(0, 2fr);
      gap: var(--space-16);
      align-items: start;
    }
    @media (max-width: 960px) {
      .columns {
        grid-template-columns: 1fr;
      }
    }
    .side {
      display: grid;
      gap: var(--space-16);
    }
    .section {
      margin: 0 0 var(--space-12);
      font-size: var(--type-title-md-size);
    }
    .timeline {
      list-style: none;
      margin: 0;
      padding: 0;
      display: grid;
      gap: var(--space-12);
    }
    .timeline li {
      display: grid;
      grid-template-columns: 9rem 1fr;
      gap: var(--space-12);
    }
    .facts {
      display: grid;
      grid-template-columns: auto 1fr;
      gap: var(--space-8) var(--space-16);
      margin: 0;
    }
    .facts dd {
      margin: 0;
      font-weight: 600;
    }
    .outcomes {
      border: 0;
      padding: 0;
      margin: 0 0 var(--space-12);
      display: grid;
      gap: var(--space-8);
    }
    .outcomes legend {
      font-weight: 600;
      margin-bottom: var(--space-8);
    }
    .option {
      display: flex;
      gap: var(--space-8);
      align-items: center;
      min-height: 44px;
    }
    .proposal {
      font-weight: 600;
      margin: 0 0 var(--space-4);
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
export class BookingDetailPage {
  private readonly api = inject(CoreApi);
  private readonly transloco = inject(TranslocoService);
  private readonly staff = inject(StaffSession);
  readonly id = input.required<string>();
  private readonly cancelDialog = viewChild<ElementRef<HTMLDialogElement>>('cancelDialog');

  readonly outcomes = OUTCOMES;
  readonly booking = signal<Booking | null>(null);
  readonly error = signal(false);
  readonly busy = signal(false);
  readonly problem = signal<string | null>(null);
  readonly outcome = signal<Outcome | null>(null);
  readonly amount = signal<number | null>(null);
  readonly reason = signal('');
  readonly note = signal('');
  readonly cancelReason = signal('');
  readonly canChange = computed(() => this.staff.accessTo('bookings') === 'F');
  private readonly lang = signal(this.transloco.getActiveLang());

  /** True when staff typed an amount other than the policy's. */
  readonly changed = computed(() => {
    const b = this.booking();
    const o = this.outcome();
    const a = this.amount();
    return !!b && !!o && a !== null && a !== this.proposed(b, o);
  });

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
      this.booking.set(
        orThrow(
          await this.api.client.GET('/admin/v1/bookings/{bookingId}', {
            params: { path: { bookingId: this.id() } },
          }),
        ),
      );
    } catch {
      this.error.set(true);
    }
  }

  settled(b: Booking): boolean {
    return !UNSETTLED.includes(b.status);
  }

  /** What the booking's own policy gives back (D33), as Core will propose it. */
  proposed(b: Booking, o: Outcome): number {
    const percent =
      o === 'SPECIALIST_NO_SHOW'
        ? b.policy.specialistNoShowRefundPercent
        : o === 'SEEKER_NO_SHOW'
          ? b.policy.seekerNoShowRefundPercent
          : 100;
    const refundable = b.money ? b.money.amount - b.money.refunded : 0;
    return Math.max(0, Math.min(refundable, Math.floor((b.price * percent) / 100)));
  }

  pick(o: Outcome) {
    this.outcome.set(o);
    this.amount.set(null);
  }

  async resolve() {
    const o = this.outcome();
    if (!o) return;
    const body: { outcome: Outcome; amount?: number; reason?: string } = {
      outcome: o,
    };
    if (this.changed()) body.amount = this.amount()!;
    if (this.reason().trim()) body.reason = this.reason().trim();
    await this.act(() =>
      this.api.client.POST('/admin/v1/bookings/{bookingId}/dispute-resolution', {
        params: { path: { bookingId: this.id() } },
        body,
      }),
    );
    this.outcome.set(null);
    this.reason.set('');
  }

  async openDispute() {
    await this.act(() =>
      this.api.client.POST('/admin/v1/bookings/{bookingId}/disputes', {
        params: { path: { bookingId: this.id() } },
        body: { note: this.note().trim() },
      }),
    );
    this.note.set('');
  }

  askCancel() {
    this.cancelReason.set('');
    this.cancelDialog()?.nativeElement.showModal();
  }

  async cancel() {
    this.cancelDialog()?.nativeElement.close();
    await this.act(() =>
      this.api.client.POST('/admin/v1/bookings/{bookingId}/cancellation', {
        params: { path: { bookingId: this.id() } },
        body: { reason: this.cancelReason().trim() },
      }),
    );
  }

  private async act(
    call: () => Promise<{
      data?: Booking;
      error?: unknown;
      response: Response;
    }>,
  ) {
    this.busy.set(true);
    this.problem.set(null);
    try {
      this.booking.set(orThrow(await call()));
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

  summary(b: Booking): string {
    const lang = this.lang();
    const window = `${dayAndTime(b.startsAt, lang)}–${clock(b.endsAt, lang)}`;
    return this.transloco.translate('admin.bookings.summary', {
      window,
      specialist: b.specialist.fullName,
      person: this.person(b.personRef),
      date: shortDate(b.createdAt, lang),
      source: this.transloco.translate(`admin.bookings.source.${b.source}`),
    });
  }

  person(ref: string): string {
    return ref.startsWith('G-')
      ? this.transloco.translate('admin.bookings.guest', { ref: `#${ref}` })
      : this.transloco.translate('admin.bookings.seeker', { ref });
  }

  statusLabel(b: Booking): string {
    if (b.disputed) return this.transloco.translate('admin.bookings.disputed');
    if (b.status === 'CANCELLED' && b.cancelledBy) {
      return this.transloco.translate(`admin.bookings.cancelledBy_${b.cancelledBy}`);
    }
    return this.transloco.translate(`admin.bookings.state.${b.status}`);
  }

  moneyState(b: Booking): string {
    if (!b.money) return this.transloco.translate('admin.bookings.notPaid');
    if (b.money.held > 0) {
      return this.transloco.translate(
        b.disputed ? 'admin.bookings.heldUntilResolved' : 'admin.bookings.heldAmount',
        { amount: this.money(b.money.held) },
      );
    }
    if (b.money.refunded > 0) {
      return this.transloco.translate('admin.bookings.refundedAmount', {
        amount: this.money(b.money.refunded),
      });
    }
    return this.transloco.translate('admin.bookings.released');
  }

  entry(e: Entry): string {
    const params = {
      role: e.role ? this.transloco.translate(`admin.bookings.role.${e.role}`) : '',
      amount: e.amount !== undefined ? this.money(e.amount) : '',
      detail: e.detail ?? '',
    };
    let key = `admin.bookings.event.${e.kind}`;
    if (e.kind === 'DISPUTE_RESOLVED' && e.detail) {
      params.detail = this.transloco.translate(`admin.bookings.outcome.${e.detail}`);
    }
    if (e.kind === 'REFUND' && e.detail) {
      params.detail = this.transloco.translate(`admin.payments.refundStatus.${e.detail}`);
    }
    if (e.kind === 'CANCELLED' && !e.role) key = 'admin.bookings.event.CANCELLED_SYSTEM';
    return this.transloco.translate(key, params);
  }

  at(iso: string): string {
    const lang = this.lang();
    return `${shortDate(iso, lang)} ${clock(iso, lang)}`;
  }

  money(amount: number): string {
    return fcfa(amount, this.lang());
  }

  lower(code: string): string {
    return languageName(code, this.lang());
  }
}
