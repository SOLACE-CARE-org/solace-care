import {
  ChangeDetectionStrategy,
  Component,
  computed,
  effect,
  type ElementRef,
  inject,
  input,
  signal,
  viewChild,
} from '@angular/core';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { TranslocoPipe, TranslocoService } from '@jsverse/transloco';

import { ApiProblem, CoreApi, type CoreSchemas, orThrow } from '../api/core-api';
import { StaffSession } from '../auth/staff-session';
import { saveFile } from '../shared/download';
import { dateTime, fcfa, period } from '../shared/format';

type Run = CoreSchemas['PayoutRun'];
type Item = CoreSchemas['PayoutItem'];

/**
 * Admin screen 12: one payout run (PayoutRun.dc.html). Finance prepares and submits it; a second
 * Finance member or a Super admin approves and sends it. Core says through `canSubmit` and
 * `canApprove` who may do what (D14, D35).
 */
@Component({
  selector: 'sl-payout-run',
  imports: [TranslocoPipe, FormsModule, RouterLink],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <a class="sl-button sl-button--quiet back" routerLink="/payouts">
      {{ 'admin.payouts.back' | transloco }}
    </a>
    @if (error()) {
      <div class="sl-card">
        <p>{{ 'common.checkConnection' | transloco }}</p>
        <button class="sl-button" type="button" (click)="load()">
          {{ 'common.tryAgain' | transloco }}
        </button>
      </div>
    } @else if (run(); as r) {
      <div class="head">
        <div>
          <h1 class="sl-title">{{ 'admin.payouts.runTitle' | transloco: { period: span(r) } }}</h1>
          <p class="sl-muted">{{ statusLine(r) }}</p>
        </div>
        <div class="actions">
          <button type="button" class="sl-button" (click)="export(r)">
            {{ 'admin.payouts.export' | transloco }}
          </button>
          @if (r.canSubmit) {
            <button
              type="button"
              class="sl-button sl-button--primary"
              [disabled]="busy()"
              (click)="submit(r)"
            >
              {{ 'admin.payouts.submit' | transloco }}
            </button>
          }
          @if (r.status === 'SUBMITTED') {
            <button
              type="button"
              class="sl-button sl-button--primary"
              [disabled]="!r.canApprove || busy()"
              (click)="askApprove()"
            >
              {{ 'admin.payouts.approve' | transloco: { count: r.totals.ready } }}
            </button>
          }
        </div>
      </div>

      <div class="tiles">
        <div class="sl-card tile">
          <p class="sl-meta">{{ 'admin.payouts.specialists' | transloco }}</p>
          <p class="figure">{{ r.totals.specialists }}</p>
          <p class="sl-meta">
            {{
              'admin.payouts.readyHeld' | transloco: { ready: r.totals.ready, held: r.totals.held }
            }}
          </p>
        </div>
        <div class="sl-card tile">
          <p class="sl-meta">{{ 'admin.payouts.sessions' | transloco }}</p>
          <p class="figure">{{ r.totals.sessions }}</p>
        </div>
        <div class="sl-card tile">
          <p class="sl-meta">{{ 'admin.payouts.commission' | transloco }}</p>
          <p class="figure">{{ r.feePercent }}%</p>
          <p class="sl-meta">{{ 'admin.payouts.commissionNote' | transloco }}</p>
        </div>
        <div class="sl-card tile">
          <p class="sl-meta">{{ 'admin.payouts.net' | transloco }}</p>
          <p class="figure">{{ money(r.totals.net) }}</p>
        </div>
      </div>

      <section class="sl-card table-card">
        <table class="sl-table">
          <thead>
            <tr>
              <th scope="col">{{ 'admin.payouts.specialist' | transloco }}</th>
              <th scope="col">{{ 'admin.payouts.sessions' | transloco }}</th>
              <th scope="col">{{ 'admin.payouts.gross' | transloco }}</th>
              <th scope="col">{{ 'admin.payouts.commission' | transloco }}</th>
              <th scope="col">{{ 'admin.payouts.net' | transloco }}</th>
              <th scope="col">{{ 'admin.payouts.paysTo' | transloco }}</th>
              <th scope="col">{{ 'admin.payouts.status' | transloco }}</th>
              @if (canHold(r)) {
                <th scope="col">
                  <span class="visually-hidden">{{ 'admin.payouts.hold' | transloco }}</span>
                </th>
              }
            </tr>
          </thead>
          <tbody>
            @for (item of r.items ?? []; track item.id) {
              <tr>
                <td class="strong">{{ item.name }}</td>
                <td>{{ item.sessions }}</td>
                <td>{{ money(item.gross) }}</td>
                <td>{{ money(item.commission) }}</td>
                <td>{{ money(item.net) }}</td>
                <td>
                  {{ item.method ? ('admin.payments.methods.' + item.method | transloco) : '—' }}
                </td>
                <td>
                  <span
                    class="sl-pill"
                    [class.sl-pill--warning]="item.status === 'HELD'"
                    [class.sl-pill--danger]="item.status === 'FAILED'"
                    [class.sl-pill--brand]="item.status === 'READY' || item.status === 'PAID'"
                    >{{ itemStatus(item) }}</span
                  >
                </td>
                @if (canHold(r)) {
                  <td class="action">
                    @if (item.status === 'HELD') {
                      <button
                        type="button"
                        class="sl-button"
                        [disabled]="busy()"
                        (click)="release(r, item)"
                      >
                        {{ 'admin.payouts.release' | transloco }}
                      </button>
                    } @else {
                      <button
                        type="button"
                        class="sl-button"
                        [disabled]="busy()"
                        (click)="askHold(item)"
                      >
                        {{ 'admin.payouts.hold' | transloco }}
                      </button>
                    }
                  </td>
                }
              </tr>
            }
          </tbody>
        </table>
      </section>
      @if (r.status === 'SUBMITTED' && !r.canApprove && r.preparedBy) {
        <p class="sl-meta note">
          {{ 'admin.payouts.approverNote' | transloco: { name: r.preparedBy } }}
        </p>
      }
      @if (problem()) {
        <p class="sl-meta problem" role="alert">{{ problem() }}</p>
      }
    } @else {
      <p class="sl-muted">{{ 'common.loading' | transloco }}</p>
    }

    <dialog #holdDialog class="sl-dialog" aria-labelledby="hold-title">
      <h2 id="hold-title" class="dialog-title">
        {{ 'admin.payouts.holdTitle' | transloco: { name: holding()?.name ?? '' } }}
      </h2>
      <p class="sl-muted">{{ 'admin.payouts.holdNote' | transloco }}</p>
      <label class="sl-field">
        <span>{{ 'admin.common.reason' | transloco }}</span>
        <textarea class="sl-textarea" rows="3" maxlength="1000" [(ngModel)]="reason"></textarea>
      </label>
      <div class="dialog-actions">
        <button type="button" class="sl-button" (click)="holdDialog.close()">
          {{ 'admin.common.cancel' | transloco }}
        </button>
        <button
          type="button"
          class="sl-button sl-button--danger"
          [disabled]="!reason().trim()"
          (click)="hold()"
        >
          {{ 'admin.payouts.confirmHold' | transloco }}
        </button>
      </div>
    </dialog>

    <dialog #approveDialog class="sl-dialog">
      <p>{{ approveText() }}</p>
      <div class="dialog-actions">
        <button type="button" class="sl-button" (click)="approveDialog.close()">
          {{ 'admin.common.cancel' | transloco }}
        </button>
        <button type="button" class="sl-button sl-button--primary" (click)="approve()">
          {{ 'admin.payouts.approve' | transloco: { count: run()?.totals?.ready ?? 0 } }}
        </button>
      </div>
    </dialog>
  `,
  styles: `
    .back {
      margin-bottom: var(--space-12);
    }
    .head {
      display: flex;
      justify-content: space-between;
      align-items: flex-start;
      gap: var(--space-16);
      flex-wrap: wrap;
    }
    .head p {
      margin: var(--space-4) 0 0;
    }
    .actions,
    .dialog-actions {
      display: flex;
      gap: var(--space-8);
      justify-content: flex-end;
    }
    .tiles {
      display: grid;
      grid-template-columns: repeat(auto-fit, minmax(180px, 1fr));
      gap: var(--space-12);
      margin: var(--space-20) 0;
    }
    .tile p {
      margin: 0 0 var(--space-4);
    }
    .figure {
      font-family: var(--font-display);
      font-size: var(--type-title-md-size);
      font-weight: 700;
    }
    .table-card {
      padding: 0;
      overflow-x: auto;
    }
    .strong {
      font-weight: 700;
    }
    .action {
      text-align: right;
    }
    .note {
      margin-top: var(--space-12);
      max-width: 80ch;
    }
    .dialog-title {
      margin: 0 0 var(--space-8);
      font-size: var(--type-title-md-size);
    }
    .problem {
      color: var(--danger);
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
export class PayoutRunPage {
  private readonly api = inject(CoreApi);
  private readonly transloco = inject(TranslocoService);
  private readonly staff = inject(StaffSession);
  private readonly holdDialog = viewChild<ElementRef<HTMLDialogElement>>('holdDialog');
  private readonly approveDialog = viewChild<ElementRef<HTMLDialogElement>>('approveDialog');

  /** Bound from the route. */
  readonly id = input.required<string>();

  readonly run = signal<Run | null>(null);
  readonly error = signal(false);
  readonly busy = signal(false);
  readonly problem = signal<string | null>(null);
  readonly holding = signal<Item | null>(null);
  readonly reason = signal('');
  private readonly lang = signal(this.transloco.getActiveLang());

  readonly approveText = computed(() => {
    const r = this.run();
    return r
      ? this.transloco.translate('admin.payouts.confirmApprove', {
          count: r.totals.ready,
          amount: this.money(r.totals.net),
        })
      : '';
  });

  constructor() {
    this.transloco.langChanges$.subscribe((lang) => this.lang.set(lang));
    effect(() => {
      this.id();
      void this.load();
    });
  }

  async load() {
    this.error.set(false);
    try {
      this.run.set(
        orThrow(
          await this.api.client.GET('/admin/v1/payout-runs/{runId}', {
            params: { path: { runId: this.id() } },
          }),
        ),
      );
    } catch {
      this.error.set(true);
    }
  }

  /** Holds belong to a draft, and to staff who may change payments. */
  canHold(run: Run): boolean {
    return run.status === 'DRAFT' && this.staff.accessTo('payments') === 'F';
  }

  statusLine(run: Run): string {
    const lang = this.lang();
    if (run.status === 'DRAFT') return this.transloco.translate('admin.payouts.draftLine');
    if (run.approvedBy) {
      return this.transloco.translate('admin.payouts.approvedLine', {
        name: run.approvedBy,
        date: dateTime(run.approvedAt, lang),
      });
    }
    return this.transloco.translate('admin.payouts.preparedLine', {
      name: run.preparedBy ?? '',
      date: dateTime(run.preparedAt, lang),
    });
  }

  itemStatus(item: Item): string {
    if (item.status === 'HELD' && item.holdReason) {
      return this.transloco.translate('admin.payouts.held', { reason: item.holdReason });
    }
    if (item.status === 'FAILED' && item.failureReason) {
      return this.transloco.translate('admin.payouts.failed', { reason: item.failureReason });
    }
    return this.transloco.translate(`admin.payouts.itemStatus.${item.status}`);
  }

  async export(run: Run) {
    await this.act(async () => {
      const result = await this.api.client.GET('/admin/v1/payout-runs/{runId}/report', {
        params: { path: { runId: run.id } },
        parseAs: 'text',
      });
      const csv = orThrow(result) as unknown as string;
      saveFile(`payout-run-${run.periodStart}-${run.periodEnd}.csv`, csv);
    }, false);
  }

  async submit(run: Run) {
    await this.act(async () =>
      this.run.set(
        orThrow(
          await this.api.client.POST('/admin/v1/payout-runs/{runId}/submission', {
            params: { path: { runId: run.id } },
          }),
        ),
      ),
    );
  }

  askApprove() {
    this.approveDialog()?.nativeElement.showModal();
  }

  async approve() {
    this.approveDialog()?.nativeElement.close();
    const run = this.run();
    if (!run) return;
    await this.act(async () =>
      this.run.set(
        orThrow(
          await this.api.client.POST('/admin/v1/payout-runs/{runId}/approval', {
            params: { path: { runId: run.id } },
          }),
        ),
      ),
    );
  }

  askHold(item: Item) {
    this.holding.set(item);
    this.reason.set('');
    this.holdDialog()?.nativeElement.showModal();
  }

  async hold() {
    this.holdDialog()?.nativeElement.close();
    const run = this.run();
    const item = this.holding();
    if (!run || !item) return;
    await this.act(async () =>
      this.run.set(
        orThrow(
          await this.api.client.PUT('/admin/v1/payout-runs/{runId}/items/{itemId}/hold', {
            params: { path: { runId: run.id, itemId: item.id } },
            body: { reason: this.reason().trim() },
          }),
        ),
      ),
    );
  }

  async release(run: Run, item: Item) {
    await this.act(async () =>
      this.run.set(
        orThrow(
          await this.api.client.DELETE('/admin/v1/payout-runs/{runId}/items/{itemId}/hold', {
            params: { path: { runId: run.id, itemId: item.id } },
          }),
        ),
      ),
    );
  }

  private async act(work: () => Promise<unknown>, lock = true) {
    if (lock) this.busy.set(true);
    this.problem.set(null);
    try {
      await work();
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

  span(run: Run): string {
    return period(run.periodStart, run.periodEnd, this.lang());
  }

  money(amount: number): string {
    return fcfa(amount, this.lang());
  }
}
