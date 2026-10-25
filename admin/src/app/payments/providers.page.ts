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

import { ApiProblem, CoreApi, type CoreSchemas, orThrow } from '../api/core-api';
import { StaffSession } from '../auth/staff-session';
import { fcfa } from '../shared/format';

type Provider = CoreSchemas['PaymentProviderAccount'];
type Route = CoreSchemas['PaymentRoute'];

const PURPOSES = ['COLLECT', 'REFUND', 'PAYOUT'] as const;
const METHODS = ['MTN_MOMO', 'ORANGE_MONEY'] as const;

/**
 * Payment providers and their routes (D61), opened from Payments. Finance and Super admins look;
 * only a Super admin adds, changes or routes a provider, each time with a reason. A LIVE provider
 * becomes active only with its keys found, a passing connection test and four confirmed checks.
 */
@Component({
  selector: 'sl-payment-providers',
  imports: [TranslocoPipe, FormsModule, RouterLink],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <a routerLink="/payments" class="sl-meta">{{ 'admin.providers.back' | transloco }}</a>
    <h1 class="sl-title">{{ 'admin.providers.title' | transloco }}</h1>
    <p class="sl-muted subtitle">{{ 'admin.providers.subtitle' | transloco }}</p>

    @if (error()) {
      <div class="sl-card">
        <p>{{ 'common.checkConnection' | transloco }}</p>
        <button class="sl-button" type="button" (click)="load()">
          {{ 'common.tryAgain' | transloco }}
        </button>
      </div>
    } @else if (providers(); as list) {
      <section class="sl-card" aria-labelledby="providers-title">
        <div class="head">
          <h2 id="providers-title" class="section">{{ 'admin.providers.list' | transloco }}</h2>
          @if (canChange()) {
            <button type="button" class="sl-button" (click)="askNew()">
              {{ 'admin.providers.add' | transloco }}
            </button>
          }
        </div>
        <ul class="rows">
          @for (p of list; track p.code) {
            <li class="row">
              <div>
                <p class="label">{{ p.displayName }} · {{ p.code }}</p>
                <p class="sl-meta">
                  {{ 'admin.providers.adapter.' + p.adapter | transloco }} ·
                  {{ 'admin.providers.mode.' + p.mode | transloco }} ·
                  {{ (p.keysFound ? 'admin.providers.keysFound' : 'admin.providers.keysMissing') | transloco }}
                  @if (p.circuit) {
                    · {{ 'admin.providers.circuit.' + p.circuit | transloco }}
                  }
                </p>
                <p class="sl-meta">
                  {{ 'admin.providers.holds' | transloco: { n: p.inFlight, amount: money(p.clearing) } }}
                  · {{ p.webhookPath }}
                </p>
              </div>
              <span class="sl-pill">{{ 'admin.providers.status.' + p.status | transloco }}</span>
              @if (canChange()) {
                <span class="buttons">
                  <button type="button" class="sl-button" (click)="test(p)">
                    {{ 'admin.providers.test' | transloco }}
                  </button>
                  <button type="button" class="sl-button" (click)="askChange(p)">
                    {{ 'admin.settings.change' | transloco }}
                  </button>
                </span>
              } @else {
                <span></span>
              }
            </li>
          }
        </ul>
        @if (tested(); as t) {
          <p class="sl-meta" role="status">
            {{ (t.ok ? 'admin.providers.testOk' : 'admin.providers.testFailed') | transloco }}
            @if (t.problem) {
              · {{ t.problem }}
            }
          </p>
        }
      </section>

      <section class="sl-card" aria-labelledby="routes-title">
        <div class="head">
          <h2 id="routes-title" class="section">{{ 'admin.providers.routes' | transloco }}</h2>
          @if (canChange()) {
            <button type="button" class="sl-button" (click)="askRoutes()">
              {{ 'admin.settings.change' | transloco }}
            </button>
          }
        </div>
        <p class="sl-muted">{{ 'admin.providers.routesHelp' | transloco }}</p>
        <ul class="rows">
          @for (purpose of purposes; track purpose) {
            @for (method of methods; track method) {
              <li class="row routes">
                <p class="label">
                  {{ 'admin.providers.purpose.' + purpose | transloco }} ·
                  {{ 'admin.payments.methods.' + method | transloco }}
                </p>
                <span class="value">{{ chain(purpose, method) || ('admin.settings.none' | transloco) }}</span>
              </li>
            }
          }
        </ul>
      </section>
    } @else {
      <p class="sl-card sl-muted">{{ 'common.loading' | transloco }}</p>
    }

    <dialog #providerDialog class="sl-dialog" aria-labelledby="provider-title">
      <h2 id="provider-title" class="section">
        {{ (editing() ? 'admin.providers.change' : 'admin.providers.add') | transloco }}
      </h2>
      @if (!editing()) {
        <label class="sl-field">
          <span>{{ 'admin.providers.code' | transloco }}</span>
          <input class="sl-input" [(ngModel)]="code" maxlength="31" />
        </label>
        <label class="sl-field">
          <span>{{ 'admin.providers.adapterLabel' | transloco }}</span>
          <select class="sl-input" [(ngModel)]="adapter">
            <option value="NOTCHPAY">{{ 'admin.providers.adapter.NOTCHPAY' | transloco }}</option>
            <option value="SANDBOX">{{ 'admin.providers.adapter.SANDBOX' | transloco }}</option>
          </select>
        </label>
      }
      <label class="sl-field">
        <span>{{ 'admin.providers.name' | transloco }}</span>
        <input class="sl-input" [(ngModel)]="displayName" maxlength="80" />
      </label>
      <label class="sl-field">
        <span>{{ 'admin.providers.modeLabel' | transloco }}</span>
        <select class="sl-input" [(ngModel)]="mode">
          <option value="TEST">{{ 'admin.providers.mode.TEST' | transloco }}</option>
          <option value="LIVE">{{ 'admin.providers.mode.LIVE' | transloco }}</option>
        </select>
      </label>
      @if (editing()) {
        <label class="sl-field">
          <span>{{ 'admin.providers.statusLabel' | transloco }}</span>
          <select class="sl-input" [(ngModel)]="status">
            @for (s of statuses; track s) {
              <option [value]="s">{{ 'admin.providers.status.' + s | transloco }}</option>
            }
          </select>
        </label>
      }
      <label class="sl-field">
        <span>{{ 'admin.providers.baseUrl' | transloco }}</span>
        <input class="sl-input" [(ngModel)]="baseUrl" maxlength="200" placeholder="https://" />
      </label>
      <label class="sl-field">
        <span>{{ 'admin.providers.secretRef' | transloco }}</span>
        <input class="sl-input" [(ngModel)]="secretRef" maxlength="63" />
      </label>
      @if (editing() && status() === 'ACTIVE' && mode() === 'LIVE') {
        <fieldset class="checks">
          <legend>{{ 'admin.providers.checks' | transloco }}</legend>
          @for (c of checkNames; track c) {
            <label>
              <input type="checkbox" [checked]="checks()[c]" (change)="toggle(c)" />
              {{ 'admin.providers.check.' + c | transloco }}
            </label>
          }
        </fieldset>
      }
      <label class="sl-field">
        <span>{{ 'admin.bookings.reasonRequired' | transloco }}</span>
        <textarea class="sl-textarea" rows="3" maxlength="500" [(ngModel)]="reason"></textarea>
      </label>
      @if (problem()) {
        <p class="sl-meta problem" role="alert">{{ problem() }}</p>
      }
      <div class="actions">
        <button type="button" class="sl-button" (click)="close(providerDialog)">
          {{ 'admin.common.cancel' | transloco }}
        </button>
        <button
          type="button"
          class="sl-button sl-button--primary"
          [disabled]="busy() || !reason().trim() || !displayName().trim() || !secretRef().trim()"
          (click)="saveProvider()"
        >
          {{ 'admin.common.save' | transloco }}
        </button>
      </div>
    </dialog>

    <dialog #routesDialog class="sl-dialog" aria-labelledby="routes-edit-title">
      <h2 id="routes-edit-title" class="section">{{ 'admin.providers.routes' | transloco }}</h2>
      <p class="sl-muted">{{ 'admin.providers.routesEdit' | transloco }}</p>
      @for (purpose of purposes; track purpose) {
        @for (method of methods; track method) {
          <label class="sl-field">
            <span>
              {{ 'admin.providers.purpose.' + purpose | transloco }} ·
              {{ 'admin.payments.methods.' + method | transloco }}
            </span>
            <input
              class="sl-input"
              [ngModel]="draftRoutes()[purpose + ':' + method]"
              (ngModelChange)="setRoute(purpose + ':' + method, $event)"
            />
          </label>
        }
      }
      <label class="sl-field">
        <span>{{ 'admin.bookings.reasonRequired' | transloco }}</span>
        <textarea class="sl-textarea" rows="3" maxlength="500" [(ngModel)]="reason"></textarea>
      </label>
      @if (problem()) {
        <p class="sl-meta problem" role="alert">{{ problem() }}</p>
      }
      <div class="actions">
        <button type="button" class="sl-button" (click)="close(routesDialog)">
          {{ 'admin.common.cancel' | transloco }}
        </button>
        <button
          type="button"
          class="sl-button sl-button--primary"
          [disabled]="busy() || !reason().trim()"
          (click)="saveRoutes()"
        >
          {{ 'admin.common.save' | transloco }}
        </button>
      </div>
    </dialog>
  `,
  styles: `
    .subtitle {
      margin: var(--space-4) 0 var(--space-20);
      max-width: 72ch;
    }
    .sl-card + .sl-card {
      margin-top: var(--space-16);
    }
    .head {
      display: flex;
      justify-content: space-between;
      align-items: center;
      gap: var(--space-12);
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
      grid-template-columns: minmax(0, 1fr) 8rem auto;
      gap: var(--space-12);
      align-items: center;
      min-height: 44px;
      padding: var(--space-8) 0;
      border-top: 1px solid var(--line);
    }
    .row.routes {
      grid-template-columns: minmax(0, 1fr) minmax(0, 1fr);
    }
    .row p {
      margin: 0;
    }
    .label,
    .value {
      font-weight: 600;
    }
    .buttons {
      display: flex;
      gap: var(--space-8);
    }
    .checks {
      display: grid;
      gap: var(--space-8);
      border: 1px solid var(--line);
      padding: var(--space-12);
      margin: var(--space-12) 0;
    }
    .checks label {
      display: flex;
      gap: var(--space-8);
      align-items: center;
      min-height: 44px;
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
export class PaymentProvidersPage {
  private readonly api = inject(CoreApi);
  private readonly transloco = inject(TranslocoService);
  private readonly staff = inject(StaffSession);
  private readonly providerDialog = viewChild<ElementRef<HTMLDialogElement>>('providerDialog');
  private readonly routesDialog = viewChild<ElementRef<HTMLDialogElement>>('routesDialog');

  readonly purposes = PURPOSES;
  readonly methods = METHODS;
  readonly statuses = ['DRAFT', 'ACTIVE', 'DRAINING', 'DISABLED'];
  readonly checkNames = ['recordsOfProcessing', 'threatModel', 'contract', 'testTransactions'] as const;
  readonly providers = signal<Provider[] | null>(null);
  readonly routes = signal<CoreSchemas['PaymentRoutes'] | null>(null);
  readonly error = signal(false);
  readonly busy = signal(false);
  readonly problem = signal<string | null>(null);
  readonly tested = signal<CoreSchemas['PaymentProviderTest'] | null>(null);
  readonly editing = signal<Provider | null>(null);
  readonly code = signal('');
  readonly adapter = signal<'NOTCHPAY' | 'SANDBOX'>('NOTCHPAY');
  readonly displayName = signal('');
  readonly mode = signal<'TEST' | 'LIVE'>('TEST');
  readonly status = signal('DRAFT');
  readonly baseUrl = signal('');
  readonly secretRef = signal('');
  readonly reason = signal('');
  readonly checks = signal<Record<string, boolean>>({});
  readonly draftRoutes = signal<Record<string, string>>({});
  /** Only a Super admin changes providers and routes (D61). */
  readonly canChange = computed(() => this.staff.roles().includes('SUPER_ADMIN'));
  private readonly lang = signal(this.transloco.getActiveLang());

  constructor() {
    this.transloco.langChanges$.subscribe((lang) => this.lang.set(lang));
    void this.load();
  }

  async load() {
    this.error.set(false);
    try {
      const [providers, routes] = await Promise.all([
        this.api.client.GET('/admin/v1/payment-providers'),
        this.api.client.GET('/admin/v1/payment-routes'),
      ]);
      this.providers.set(orThrow(providers));
      this.routes.set(orThrow(routes));
    } catch {
      this.error.set(true);
    }
  }

  money(amount: number): string {
    return fcfa(amount, this.lang());
  }

  /** "notchpay → sandbox" for one purpose and method, in order. */
  chain(purpose: string, method: string): string {
    return this.ordered(purpose, method).join(' → ');
  }

  private ordered(purpose: string, method: string): string[] {
    return (this.routes()?.routes ?? [])
      .filter((r) => r.purpose === purpose && r.method === method)
      .sort((a, b) => a.priority - b.priority)
      .map((r) => r.providerCode);
  }

  askNew() {
    this.editing.set(null);
    this.code.set('');
    this.adapter.set('NOTCHPAY');
    this.displayName.set('');
    this.mode.set('TEST');
    this.baseUrl.set('');
    this.secretRef.set('');
    this.open(this.providerDialog());
  }

  askChange(p: Provider) {
    this.editing.set(p);
    this.displayName.set(p.displayName);
    this.mode.set(p.mode as 'TEST' | 'LIVE');
    this.status.set(p.status);
    this.baseUrl.set(p.baseUrl ?? '');
    this.secretRef.set(p.secretRef);
    this.checks.set({});
    this.open(this.providerDialog());
  }

  toggle(name: string) {
    this.checks.update((c) => ({ ...c, [name]: !c[name] }));
  }

  async test(p: Provider) {
    this.tested.set(null);
    try {
      this.tested.set(
        orThrow(
          await this.api.client.POST('/admin/v1/payment-providers/{code}/test', {
            params: { path: { code: p.code } },
          }),
        ),
      );
    } catch {
      this.tested.set({ ok: false, keysFound: p.keysFound });
    }
  }

  async saveProvider() {
    const p = this.editing();
    const baseUrl = this.baseUrl().trim() || undefined;
    await this.act(this.providerDialog(), async () => {
      if (!p) {
        orThrow(
          await this.api.client.POST('/admin/v1/payment-providers', {
            body: {
              code: this.code().trim(),
              adapter: this.adapter(),
              displayName: this.displayName().trim(),
              mode: this.mode(),
              baseUrl,
              secretRef: this.secretRef().trim(),
              reason: this.reason().trim(),
            },
          }),
        );
        return;
      }
      const c = this.checks();
      orThrow(
        await this.api.client.PUT('/admin/v1/payment-providers/{code}', {
          params: { path: { code: p.code } },
          body: {
            version: p.version,
            displayName: this.displayName().trim(),
            mode: this.mode(),
            status: this.status(),
            baseUrl,
            secretRef: this.secretRef().trim(),
            checks: {
              recordsOfProcessing: !!c['recordsOfProcessing'],
              threatModel: !!c['threatModel'],
              contract: !!c['contract'],
              testTransactions: !!c['testTransactions'],
            },
            reason: this.reason().trim(),
          },
        }),
      );
    });
  }

  askRoutes() {
    const draft: Record<string, string> = {};
    for (const purpose of PURPOSES) {
      for (const method of METHODS) {
        draft[`${purpose}:${method}`] = this.ordered(purpose, method).join(', ');
      }
    }
    this.draftRoutes.set(draft);
    this.open(this.routesDialog());
  }

  setRoute(key: string, value: string) {
    this.draftRoutes.update((d) => ({ ...d, [key]: value }));
  }

  async saveRoutes() {
    const routes: Route[] = [];
    for (const [key, value] of Object.entries(this.draftRoutes())) {
      const [purpose, method] = key.split(':') as [Route['purpose'], Route['method']];
      value
        .split(',')
        .map((c) => c.trim())
        .filter(Boolean)
        .forEach((providerCode, i) => routes.push({ purpose, method, priority: i + 1, providerCode }));
    }
    await this.act(this.routesDialog(), async () => {
      orThrow(
        await this.api.client.PUT('/admin/v1/payment-routes', {
          body: { version: this.routes()?.version ?? 0, routes, reason: this.reason().trim() },
        }),
      );
    });
  }

  close(dialog: HTMLDialogElement) {
    dialog.close();
  }

  private open(dialog: ElementRef<HTMLDialogElement> | undefined) {
    this.reason.set('');
    this.problem.set(null);
    dialog?.nativeElement.showModal();
  }

  private async act(dialog: ElementRef<HTMLDialogElement> | undefined, change: () => Promise<void>) {
    this.busy.set(true);
    this.problem.set(null);
    try {
      await change();
      dialog?.nativeElement.close();
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
