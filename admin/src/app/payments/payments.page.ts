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
import { TranslocoPipe, TranslocoService } from '@jsverse/transloco';

import { ApiProblem, CoreApi, type CoreSchemas, orThrow } from '../api/core-api';
import { StaffSession } from '../auth/staff-session';
import { bookingRef, calendarDate, dateTime, fcfa } from '../shared/format';

type Tab = 'TRANSACTIONS' | 'REFUNDS' | 'FAILURES' | 'RECONCILIATION';
type Refund = CoreSchemas['Refund'];
type Pending = { refund: Refund; action: 'approve' | 'decline' };

const TABS: Tab[] = ['TRANSACTIONS', 'REFUNDS', 'FAILURES', 'RECONCILIATION'];
const POLICY_REASONS = ['POLICY_CANCELLATION', 'POLICY_NO_SHOW', 'PAID_AFTER_EXPIRY'];

/**
 * Admin screen 11: transactions, refunds waiting for their second approver, failures and the
 * daily reconciliation (Payments.dc.html). Two people for every refund (D14): Core says through
 * `canDecide` whether this person may approve; the proposer never can.
 */
@Component({
  selector: 'sl-payments',
  imports: [TranslocoPipe],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <h1 class="sl-title">{{ 'admin.payments.title' | transloco }}</h1>

    <div class="tiles">
      <div class="sl-card tile">
        <p class="sl-meta">{{ 'admin.payments.held' | transloco }}</p>
        <p class="figure">{{ money(summary()?.held) }}</p>
      </div>
      <div class="sl-card tile">
        <p class="sl-meta">{{ 'admin.payments.released' | transloco }}</p>
        <p class="figure">{{ money(summary()?.released) }}</p>
      </div>
      <div class="sl-card tile">
        <p class="sl-meta">{{ 'admin.payments.lastReconciliation' | transloco }}</p>
        <p class="figure" [class.alert]="mismatches() > 0">{{ mismatchLabel() }}</p>
        @if (mismatches() > 0) {
          <button type="button" class="sl-button" (click)="tab.set('RECONCILIATION')">
            {{ 'admin.payments.reviewMismatch' | transloco }}
          </button>
        }
      </div>
    </div>

    <div class="sl-tabs" role="tablist">
      @for (t of tabs; track t) {
        <button role="tab" type="button" [attr.aria-selected]="t === tab()" (click)="tab.set(t)">
          {{ 'admin.payments.tab_' + t | transloco: { count: count(t) } }}
        </button>
      }
    </div>

    @if (tab() === 'REFUNDS') {
      <p class="sl-meta note">{{ 'admin.payments.refundsNote' | transloco }}</p>
    }

    <section class="sl-card table-card" aria-live="polite" [attr.aria-busy]="loading()">
      @if (error()) {
        <div class="state">
          <p>{{ 'common.checkConnection' | transloco }}</p>
          <button class="sl-button" type="button" (click)="load()">
            {{ 'common.tryAgain' | transloco }}
          </button>
        </div>
      } @else if (loading() && !loaded()) {
        <p class="state sl-muted">{{ 'common.loading' | transloco }}</p>
      } @else {
        @switch (tab()) {
          @case ('TRANSACTIONS') {
            @if (transactions().length === 0) {
              <p class="state sl-muted">{{ 'admin.payments.emptyTransactions' | transloco }}</p>
            } @else {
              <table class="sl-table">
                <thead>
                  <tr>
                    <th scope="col">{{ 'admin.payments.booking' | transloco }}</th>
                    <th scope="col">{{ 'admin.payments.amount' | transloco }}</th>
                    <th scope="col">{{ 'admin.payments.to' | transloco }}</th>
                    <th scope="col">{{ 'admin.payments.status' | transloco }}</th>
                    <th scope="col">{{ 'admin.payments.date' | transloco }}</th>
                  </tr>
                </thead>
                <tbody>
                  @for (t of transactions(); track t.id) {
                    <tr>
                      <td class="strong">{{ ref(t.bookingId) }}</td>
                      <td>{{ money(t.amount) }}</td>
                      <td>{{ wallet(t.method, t.phone) }}</td>
                      <td>
                        <span
                          class="sl-pill"
                          [class.sl-pill--danger]="t.status === 'FAILED'"
                          [class.sl-pill--brand]="t.status === 'COMPLETE'"
                          >{{ 'admin.payments.paymentStatus.' + t.status | transloco }}</span
                        >
                      </td>
                      <td>{{ when(t.createdAt) }}</td>
                    </tr>
                  }
                </tbody>
              </table>
            }
          }
          @case ('RECONCILIATION') {
            @if (reconciliation(); as r) {
              <p class="state">
                @if (r.day) {
                  {{
                    'admin.payments.reconciliationDay'
                      | transloco: { date: day(r.day), checked: r.checked ?? 0 }
                  }}
                } @else {
                  {{ 'admin.payments.notRun' | transloco }}
                }
              </p>
              @if (r.mismatches.length === 0) {
                <p class="state sl-muted">{{ 'admin.payments.allMatched' | transloco }}</p>
              } @else {
                <table class="sl-table">
                  <thead>
                    <tr>
                      <th scope="col">{{ 'admin.payments.kind' | transloco }}</th>
                      <th scope="col">{{ 'admin.payments.reference' | transloco }}</th>
                      <th scope="col">{{ 'admin.payments.ours' | transloco }}</th>
                      <th scope="col">{{ 'admin.payments.theirs' | transloco }}</th>
                    </tr>
                  </thead>
                  <tbody>
                    @for (m of r.mismatches; track m.reference) {
                      <tr>
                        <td>{{ 'admin.payments.kinds.' + m.kind | transloco }}</td>
                        <td class="mono">{{ m.reference }}</td>
                        <td>{{ m.ours }}</td>
                        <td>{{ m.theirs }}</td>
                      </tr>
                    }
                  </tbody>
                </table>
              }
            }
          }
          @default {
            @if (refunds().length === 0) {
              <p class="state sl-muted">
                {{
                  (tab() === 'REFUNDS'
                    ? 'admin.payments.emptyRefunds'
                    : 'admin.payments.emptyFailures'
                  ) | transloco
                }}
              </p>
            } @else {
              <table class="sl-table">
                <thead>
                  <tr>
                    <th scope="col">{{ 'admin.payments.booking' | transloco }}</th>
                    <th scope="col">{{ 'admin.payments.refund' | transloco }}</th>
                    <th scope="col">{{ 'admin.payments.to' | transloco }}</th>
                    <th scope="col">{{ 'admin.payments.reason' | transloco }}</th>
                    <th scope="col">{{ 'admin.payments.proposedBy' | transloco }}</th>
                    <th scope="col">{{ 'admin.payments.actions' | transloco }}</th>
                  </tr>
                </thead>
                <tbody>
                  @for (r of refunds(); track r.id) {
                    <tr>
                      <td class="strong">{{ ref(r.bookingId) }}</td>
                      <td>{{ money(r.amount) }}</td>
                      <td>{{ wallet(r.method, r.phone) }}</td>
                      <td>
                        {{ reason(r) }}
                        @if (r.failureReason) {
                          <span class="sl-meta block">{{ r.failureReason }}</span>
                        }
                      </td>
                      <td>
                        {{
                          r.proposedBy.system
                            ? ('admin.payments.system' | transloco)
                            : r.proposedBy.name
                        }}
                      </td>
                      <td class="actions">
                        @if (r.status === 'FAILED' && canChange()) {
                          <button
                            type="button"
                            class="sl-button"
                            [disabled]="busy()"
                            (click)="retry(r)"
                          >
                            {{ 'admin.payments.retry' | transloco }}
                          </button>
                        } @else if (r.canDecide) {
                          <button
                            type="button"
                            class="sl-button"
                            [disabled]="busy()"
                            (click)="ask(r, 'decline')"
                          >
                            {{ 'admin.payments.decline' | transloco }}
                          </button>
                          <button
                            type="button"
                            class="sl-button sl-button--primary"
                            [disabled]="busy()"
                            (click)="ask(r, 'approve')"
                          >
                            {{ 'admin.payments.approve' | transloco }}
                          </button>
                        } @else if (r.status === 'PROPOSED' && mine(r)) {
                          <span class="sl-meta">{{
                            'admin.payments.yourProposal' | transloco
                          }}</span>
                        }
                      </td>
                    </tr>
                  }
                </tbody>
              </table>
            }
          }
        }
      }
    </section>
    @if (problem()) {
      <p class="sl-meta problem" role="alert">{{ problem() }}</p>
    }

    <dialog #confirmDialog class="sl-dialog">
      <p>{{ confirmText() }}</p>
      <div class="dialog-actions">
        <button type="button" class="sl-button" (click)="confirmDialog.close()">
          {{ 'admin.common.cancel' | transloco }}
        </button>
        <button
          type="button"
          class="sl-button"
          [class.sl-button--primary]="pending()?.action === 'approve'"
          [class.sl-button--danger]="pending()?.action === 'decline'"
          (click)="decide()"
        >
          {{
            (pending()?.action === 'decline' ? 'admin.payments.decline' : 'admin.payments.approve')
              | transloco
          }}
        </button>
      </div>
    </dialog>
  `,
  styles: `
    .tiles {
      display: grid;
      grid-template-columns: repeat(auto-fit, minmax(220px, 1fr));
      gap: var(--space-12);
      margin: var(--space-16) 0 var(--space-20);
    }
    .tile p {
      margin: 0 0 var(--space-4);
    }
    .figure {
      font-family: var(--font-display);
      font-size: var(--type-title-md-size);
      font-weight: 700;
    }
    .figure.alert {
      color: var(--danger);
    }
    .sl-tabs {
      margin-bottom: var(--space-12);
    }
    .note {
      margin: 0 0 var(--space-12);
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
    .mono {
      font-family: ui-monospace, monospace;
      font-size: var(--type-meta-size);
    }
    .block {
      display: block;
    }
    .actions {
      display: flex;
      gap: var(--space-8);
      justify-content: flex-end;
    }
    .dialog-actions {
      display: flex;
      justify-content: flex-end;
      gap: var(--space-8);
    }
    .problem {
      color: var(--danger);
    }
  `,
})
export class PaymentsPage {
  private readonly api = inject(CoreApi);
  private readonly transloco = inject(TranslocoService);
  private readonly staff = inject(StaffSession);
  private readonly confirmDialog = viewChild<ElementRef<HTMLDialogElement>>('confirmDialog');

  readonly tabs = TABS;
  readonly tab = signal<Tab>('TRANSACTIONS');
  readonly summary = signal<CoreSchemas['PaymentsSummary'] | null>(null);
  readonly transactions = signal<CoreSchemas['Transaction'][]>([]);
  readonly refunds = signal<Refund[]>([]);
  readonly reconciliation = signal<CoreSchemas['Reconciliation'] | null>(null);
  readonly loading = signal(false);
  readonly loaded = signal(false);
  readonly error = signal(false);
  readonly busy = signal(false);
  readonly problem = signal<string | null>(null);
  readonly pending = signal<Pending | null>(null);
  private readonly lang = signal(this.transloco.getActiveLang());
  readonly canChange = computed(() => this.staff.accessTo('payments') === 'F');
  readonly mismatches = computed(() => this.summary()?.lastReconciliation?.mismatches ?? 0);

  readonly mismatchLabel = computed(() => {
    this.lang();
    const head = this.summary()?.lastReconciliation;
    if (!head) return this.transloco.translate('admin.payments.notRun');
    if (head.mismatches === 0) return this.transloco.translate('admin.payments.noMismatch');
    if (head.mismatches === 1) return this.transloco.translate('admin.payments.mismatchOne');
    return this.transloco.translate('admin.payments.mismatches', { count: head.mismatches });
  });

  readonly confirmText = computed(() => {
    const p = this.pending();
    if (!p) return '';
    return p.action === 'approve'
      ? this.transloco.translate('admin.payments.confirmApprove', {
          amount: this.money(p.refund.amount),
          to: this.wallet(p.refund.method, p.refund.phone),
        })
      : this.transloco.translate('admin.payments.confirmDecline');
  });

  constructor() {
    this.transloco.langChanges$.subscribe((lang) => this.lang.set(lang));
    effect(() => {
      this.tab();
      void this.load();
    });
    void this.loadSummary();
  }

  private async loadSummary() {
    try {
      this.summary.set(orThrow(await this.api.client.GET('/admin/v1/payments/summary')));
    } catch {
      // The tiles stay empty; the tabs still work.
    }
  }

  async load() {
    this.loading.set(true);
    this.error.set(false);
    try {
      switch (this.tab()) {
        case 'TRANSACTIONS':
          this.transactions.set(orThrow(await this.api.client.GET('/admin/v1/payments')));
          break;
        case 'REFUNDS':
          this.refunds.set(
            orThrow(
              await this.api.client.GET('/admin/v1/refunds', {
                params: { query: { status: 'PROPOSED' } },
              }),
            ),
          );
          break;
        case 'FAILURES':
          this.refunds.set(
            orThrow(
              await this.api.client.GET('/admin/v1/refunds', {
                params: { query: { status: 'FAILED' } },
              }),
            ),
          );
          break;
        case 'RECONCILIATION':
          this.reconciliation.set(orThrow(await this.api.client.GET('/admin/v1/reconciliation')));
          break;
      }
      this.loaded.set(true);
    } catch {
      this.error.set(true);
    } finally {
      this.loading.set(false);
    }
  }

  count(tab: Tab): number {
    const s = this.summary();
    if (tab === 'REFUNDS') return s?.refundsToApprove ?? 0;
    if (tab === 'FAILURES') return s?.failures ?? 0;
    return 0;
  }

  ask(refund: Refund, action: 'approve' | 'decline') {
    this.pending.set({ refund, action });
    this.confirmDialog()?.nativeElement.showModal();
  }

  async decide() {
    const p = this.pending();
    this.confirmDialog()?.nativeElement.close();
    if (!p) return;
    const path = { params: { path: { refundId: p.refund.id } } };
    await this.act(async () =>
      orThrow(
        p.action === 'approve'
          ? await this.api.client.POST('/admin/v1/refunds/{refundId}/approval', path)
          : await this.api.client.POST('/admin/v1/refunds/{refundId}/decline', path),
      ),
    );
  }

  async retry(refund: Refund) {
    await this.act(async () =>
      orThrow(
        await this.api.client.POST('/admin/v1/refunds/{refundId}/retry', {
          params: { path: { refundId: refund.id } },
        }),
      ),
    );
  }

  private async act(work: () => Promise<unknown>) {
    this.busy.set(true);
    this.problem.set(null);
    try {
      await work();
      await Promise.all([this.load(), this.loadSummary()]);
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

  /** The proposer sees their own proposal without buttons (D14). */
  mine(refund: Refund): boolean {
    return !refund.proposedBy.system && refund.proposedBy.name === this.staff.name();
  }

  reason(refund: Refund): string {
    return POLICY_REASONS.includes(refund.reason)
      ? this.transloco.translate(`admin.payments.reasons.${refund.reason}`, {
          version: refund.policyVersion ?? 1,
        })
      : refund.reason;
  }

  wallet(method: string, phone: string): string {
    const name = this.transloco.translate(`admin.payments.methods.${method}`);
    return `${name} · ${phone}`;
  }

  money(amount: number | undefined): string {
    return amount === undefined ? '—' : fcfa(amount, this.lang());
  }

  ref(id: string): string {
    return bookingRef(id);
  }

  when(iso: string): string {
    return dateTime(iso, this.lang());
  }

  day(iso: string): string {
    return calendarDate(iso, this.lang());
  }
}
