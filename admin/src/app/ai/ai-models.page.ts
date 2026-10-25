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

import { AiApi, type AiSchemas } from '../api/ai-api';
import { ApiProblem, orThrow } from '../api/core-api';
import { StaffSession } from '../auth/staff-session';

type Model = AiSchemas['AiModel'];
type FeatureRoute = AiSchemas['AiRoute'];

/**
 * AI models, each feature's route and its monthly budget (D61), opened from AI controls. Operations
 * and Clinical reviewers look; a Super admin changes them with a reason. A changed route waits as
 * pending until the feature's evaluation passes on it, and only then goes live.
 */
@Component({
  selector: 'sl-ai-models',
  imports: [TranslocoPipe, FormsModule, RouterLink],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <a routerLink="/ai" class="sl-meta">{{ 'admin.models.back' | transloco }}</a>
    <h1 class="sl-title">{{ 'admin.models.title' | transloco }}</h1>
    <p class="sl-muted subtitle">{{ 'admin.models.subtitle' | transloco }}</p>

    @if (error()) {
      <div class="sl-card">
        <p>{{ 'common.checkConnection' | transloco }}</p>
        <button class="sl-button" type="button" (click)="load()">
          {{ 'common.tryAgain' | transloco }}
        </button>
      </div>
    } @else if (models(); as list) {
      <section class="sl-card" aria-labelledby="models-title">
        <div class="head">
          <h2 id="models-title" class="section">{{ 'admin.models.list' | transloco }}</h2>
          @if (canChange()) {
            <button type="button" class="sl-button" (click)="askModel(null)">
              {{ 'admin.models.add' | transloco }}
            </button>
          }
        </div>
        @if (!list.length) {
          <p class="sl-muted">{{ 'admin.models.empty' | transloco }}</p>
        }
        <ul class="rows">
          @for (m of list; track m.name) {
            <li class="row">
              <div>
                <p class="label">{{ m.name }} · {{ m.model }}</p>
                <p class="sl-meta">
                  {{ 'admin.models.kind.' + m.kind | transloco }} ·
                  {{ (m.local ? 'admin.models.local' : 'admin.models.remote') | transloco }} ·
                  {{ (m.keyFound ? 'admin.providers.keysFound' : 'admin.providers.keysMissing') | transloco }}
                </p>
              </div>
              <span class="sl-pill">{{ 'admin.senders.status.' + m.status | transloco }}</span>
              @if (canChange()) {
                <button type="button" class="sl-button" (click)="askModel(m)">
                  {{ 'admin.settings.change' | transloco }}
                </button>
              } @else {
                <span></span>
              }
            </li>
          }
        </ul>
      </section>

      <section class="sl-card" aria-labelledby="routes-title">
        <h2 id="routes-title" class="section">{{ 'admin.models.routes' | transloco }}</h2>
        <ul class="rows">
          @for (r of routes(); track r.feature) {
            <li class="row feature">
              <div>
                <p class="label">{{ 'admin.ai.feature.' + r.feature | transloco }}</p>
                <p class="sl-meta">
                  {{ 'admin.models.live' | transloco }}:
                  {{ r.live.join(' → ') || ('admin.settings.none' | transloco) }}
                  · {{ 'admin.models.budget' | transloco }}:
                  {{ r.monthlyBudget ?? ('admin.models.defaultBudget' | transloco) }}
                </p>
                @if (r.pending) {
                  <p class="sl-meta pending">
                    {{ 'admin.models.pending' | transloco }}:
                    {{ r.pending.join(' → ') || ('admin.settings.none' | transloco) }} ·
                    {{ r.proposedBy }} ·
                    {{ (r.pendingPassed ? 'admin.models.passed' : 'admin.models.notPassed') | transloco }}
                  </p>
                }
              </div>
              @if (canChange()) {
                <span class="buttons">
                  <button type="button" class="sl-button" (click)="askRoute(r)">
                    {{ 'admin.settings.change' | transloco }}
                  </button>
                  @if (r.pending) {
                    <button type="button" class="sl-button" (click)="evaluate(r)">
                      {{ 'admin.models.evaluate' | transloco }}
                    </button>
                    <button type="button" class="sl-button" (click)="askPromote(r)">
                      {{ 'admin.models.promote' | transloco }}
                    </button>
                    <button type="button" class="sl-button" (click)="discard(r)">
                      {{ 'admin.models.discard' | transloco }}
                    </button>
                  }
                  <button type="button" class="sl-button" (click)="askBudget(r)">
                    {{ 'admin.models.budget' | transloco }}
                  </button>
                </span>
              }
            </li>
          }
        </ul>
        @if (notice()) {
          <p class="sl-meta" role="status">{{ notice() }}</p>
        }
      </section>
    } @else {
      <p class="sl-card sl-muted">{{ 'common.loading' | transloco }}</p>
    }

    <dialog #modelDialog class="sl-dialog" aria-labelledby="model-title">
      <h2 id="model-title" class="section">
        {{ (editing() ? 'admin.models.change' : 'admin.models.add') | transloco }}
      </h2>
      @if (!editing()) {
        <label class="sl-field">
          <span>{{ 'admin.models.name' | transloco }}</span>
          <input class="sl-input" [(ngModel)]="name" maxlength="41" />
        </label>
        <label class="sl-field">
          <span>{{ 'admin.models.kindLabel' | transloco }}</span>
          <select class="sl-input" [(ngModel)]="kind">
            @for (k of kinds; track k) {
              <option [value]="k">{{ 'admin.models.kind.' + k | transloco }}</option>
            }
          </select>
        </label>
      } @else {
        <label class="sl-field">
          <span>{{ 'admin.providers.statusLabel' | transloco }}</span>
          <select class="sl-input" [(ngModel)]="status">
            @for (s of statuses; track s) {
              <option [value]="s">{{ 'admin.senders.status.' + s | transloco }}</option>
            }
          </select>
        </label>
      }
      <label class="sl-field">
        <span>{{ 'admin.models.url' | transloco }}</span>
        <input class="sl-input" [(ngModel)]="url" maxlength="200" />
      </label>
      <label class="sl-field">
        <span>{{ 'admin.models.modelName' | transloco }}</span>
        <input class="sl-input" [(ngModel)]="model" maxlength="120" />
      </label>
      <label class="check">
        <input type="checkbox" [checked]="local()" (change)="local.set(!local())" />
        {{ 'admin.models.localLabel' | transloco }}
      </label>
      <label class="sl-field">
        <span>{{ 'admin.providers.secretRef' | transloco }}</span>
        <input class="sl-input" [(ngModel)]="secretRef" maxlength="63" />
      </label>
      <label class="sl-field">
        <span>{{ 'admin.senders.priorityLabel' | transloco }}</span>
        <input class="sl-input" type="number" min="1" max="10" [(ngModel)]="priority" />
      </label>
      <label class="sl-field">
        <span>{{ 'admin.bookings.reasonRequired' | transloco }}</span>
        <textarea class="sl-textarea" rows="3" maxlength="500" [(ngModel)]="reason"></textarea>
      </label>
      @if (problem()) {
        <p class="sl-meta problem" role="alert">{{ problem() }}</p>
      }
      <div class="actions">
        <button type="button" class="sl-button" (click)="modelDialog.close()">
          {{ 'admin.common.cancel' | transloco }}
        </button>
        <button
          type="button"
          class="sl-button sl-button--primary"
          [disabled]="busy() || !reason().trim() || !url().trim() || !model().trim()"
          (click)="saveModel()"
        >
          {{ 'admin.common.save' | transloco }}
        </button>
      </div>
    </dialog>

    <dialog #routeDialog class="sl-dialog" aria-labelledby="route-title">
      @if (routing(); as r) {
        <h2 id="route-title" class="section">
          {{ dialogTitle() | transloco: { feature: ('admin.ai.feature.' + r.feature | transloco) } }}
        </h2>
        @if (mode() === 'route') {
          <p class="sl-muted">{{ 'admin.models.routeHelp' | transloco }}</p>
          <label class="sl-field">
            <span>{{ 'admin.models.order' | transloco }}</span>
            <input class="sl-input" [(ngModel)]="order" />
          </label>
        }
        @if (mode() === 'budget') {
          <label class="sl-field">
            <span>{{ 'admin.models.tokens' | transloco }}</span>
            <input class="sl-input" type="number" min="0" [(ngModel)]="tokens" />
          </label>
        }
        <label class="sl-field">
          <span>{{ 'admin.bookings.reasonRequired' | transloco }}</span>
          <textarea class="sl-textarea" rows="3" maxlength="500" [(ngModel)]="reason"></textarea>
        </label>
      }
      @if (problem()) {
        <p class="sl-meta problem" role="alert">{{ problem() }}</p>
      }
      <div class="actions">
        <button type="button" class="sl-button" (click)="routeDialog.close()">
          {{ 'admin.common.cancel' | transloco }}
        </button>
        <button
          type="button"
          class="sl-button sl-button--primary"
          [disabled]="busy() || !reason().trim()"
          (click)="saveRoute()"
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
      grid-template-columns: minmax(0, 1fr) 7rem 7rem;
      gap: var(--space-12);
      align-items: center;
      min-height: 44px;
      padding: var(--space-8) 0;
      border-top: 1px solid var(--line);
    }
    .row.feature {
      grid-template-columns: minmax(0, 1fr) auto;
    }
    .row p {
      margin: 0;
    }
    .label,
    .pending {
      font-weight: 600;
    }
    .buttons {
      display: flex;
      flex-wrap: wrap;
      gap: var(--space-8);
    }
    .check {
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
export class AiModelsPage {
  private readonly api = inject(AiApi);
  private readonly transloco = inject(TranslocoService);
  private readonly staff = inject(StaffSession);
  private readonly modelDialog = viewChild<ElementRef<HTMLDialogElement>>('modelDialog');
  private readonly routeDialog = viewChild<ElementRef<HTMLDialogElement>>('routeDialog');

  readonly kinds = ['CHAT', 'SPEECH', 'EMBEDDING'];
  readonly statuses = ['DRAFT', 'ACTIVE', 'DISABLED'];
  readonly models = signal<Model[] | null>(null);
  readonly routes = signal<FeatureRoute[]>([]);
  readonly error = signal(false);
  readonly busy = signal(false);
  readonly problem = signal<string | null>(null);
  readonly notice = signal<string | null>(null);
  readonly editing = signal<Model | null>(null);
  readonly routing = signal<FeatureRoute | null>(null);
  readonly mode = signal<'route' | 'promote' | 'budget'>('route');
  readonly name = signal('');
  readonly kind = signal('CHAT');
  readonly status = signal('DRAFT');
  readonly url = signal('');
  readonly model = signal('');
  readonly local = signal(true);
  readonly secretRef = signal('config');
  readonly priority = signal(1);
  readonly order = signal('');
  readonly tokens = signal(1000000);
  readonly reason = signal('');
  readonly canChange = computed(() => this.staff.accessTo('aiControls') === 'F');
  readonly dialogTitle = computed(
    () =>
      ({
        route: 'admin.models.routeTitle',
        promote: 'admin.models.promoteTitle',
        budget: 'admin.models.budgetTitle',
      })[this.mode()],
  );

  constructor() {
    void this.load();
  }

  async load() {
    this.error.set(false);
    try {
      const [models, routes] = await Promise.all([
        this.api.client.GET('/admin/v1/ai-models'),
        this.api.client.GET('/admin/v1/ai-routes'),
      ]);
      this.models.set(orThrow(models));
      this.routes.set(orThrow(routes));
    } catch {
      this.error.set(true);
    }
  }

  askModel(m: Model | null) {
    this.editing.set(m);
    this.name.set('');
    this.kind.set('CHAT');
    this.status.set(m?.status ?? 'DRAFT');
    this.url.set(m?.url ?? '');
    this.model.set(m?.model ?? '');
    this.local.set(m?.local ?? true);
    this.secretRef.set(m?.secretRef ?? 'config');
    this.priority.set(m?.priority ?? 1);
    this.reason.set('');
    this.problem.set(null);
    this.modelDialog()?.nativeElement.showModal();
  }

  async saveModel() {
    const m = this.editing();
    await this.act(this.modelDialog(), async () => {
      const common = {
        url: this.url().trim(),
        model: this.model().trim(),
        local: this.local(),
        secretRef: this.secretRef().trim(),
        priority: Number(this.priority()),
        reason: this.reason().trim(),
      };
      if (!m) {
        orThrow(
          await this.api.client.POST('/admin/v1/ai-models', {
            body: { ...common, name: this.name().trim(), kind: this.kind() },
          }),
        );
      } else {
        orThrow(
          await this.api.client.PUT('/admin/v1/ai-models/{name}', {
            params: { path: { name: m.name } },
            body: { ...common, version: m.version, status: this.status() },
          }),
        );
      }
    });
  }

  askRoute(r: FeatureRoute) {
    this.openRoute(r, 'route');
    this.order.set((r.pending ?? r.live).join(', '));
  }

  askPromote(r: FeatureRoute) {
    this.openRoute(r, 'promote');
  }

  askBudget(r: FeatureRoute) {
    this.openRoute(r, 'budget');
    this.tokens.set(r.monthlyBudget ?? 1000000);
  }

  private openRoute(r: FeatureRoute, mode: 'route' | 'promote' | 'budget') {
    this.routing.set(r);
    this.mode.set(mode);
    this.reason.set('');
    this.problem.set(null);
    this.routeDialog()?.nativeElement.showModal();
  }

  async saveRoute() {
    const r = this.routing();
    if (!r) return;
    const feature = { params: { path: { feature: r.feature } } };
    await this.act(this.routeDialog(), async () => {
      if (this.mode() === 'route') {
        const models = this.order()
          .split(',')
          .map((s) => s.trim())
          .filter(Boolean);
        orThrow(
          await this.api.client.PUT('/admin/v1/ai-routes/{feature}', {
            ...feature,
            body: { models, reason: this.reason().trim() },
          }),
        );
      } else if (this.mode() === 'promote') {
        orThrow(
          await this.api.client.POST('/admin/v1/ai-routes/{feature}/promotion', {
            ...feature,
            body: { reason: this.reason().trim() },
          }),
        );
      } else {
        orThrow(
          await this.api.client.PUT('/admin/v1/ai-features/{feature}/budget', {
            ...feature,
            body: { tokens: Number(this.tokens()), reason: this.reason().trim() },
          }),
        );
      }
    });
  }

  async evaluate(r: FeatureRoute) {
    this.notice.set(null);
    try {
      const run = orThrow(
        await this.api.client.POST('/admin/v1/ai-routes/{feature}/evaluation', {
          params: { path: { feature: r.feature } },
        }),
      ) as { passed?: boolean };
      this.notice.set(
        this.transloco.translate(run.passed ? 'admin.models.evalPassed' : 'admin.models.evalFailed'),
      );
      await this.load();
    } catch (e) {
      this.notice.set(
        e instanceof ApiProblem && e.title ? e.title : this.transloco.translate('common.checkConnection'),
      );
    }
  }

  async discard(r: FeatureRoute) {
    try {
      orThrow(
        await this.api.client.DELETE('/admin/v1/ai-routes/{feature}/pending', {
          params: { path: { feature: r.feature } },
        }),
      );
      await this.load();
    } catch (e) {
      this.notice.set(
        e instanceof ApiProblem && e.title ? e.title : this.transloco.translate('common.checkConnection'),
      );
    }
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
