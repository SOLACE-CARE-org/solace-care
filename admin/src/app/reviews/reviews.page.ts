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
import { TranslocoPipe, TranslocoService } from '@jsverse/transloco';

import { ApiProblem, CoreApi, type CoreSchemas, orThrow } from '../api/core-api';
import { StaffSession } from '../auth/staff-session';
import { capitalised, languageName, shortDate } from '../shared/format';

type Item = CoreSchemas['ModerationItem'];

/**
 * Admin screen 13: written reviews waiting for moderation, oldest first (Reviews.dc.html). The
 * safety monitor has already seen each one; crisis text never reaches this queue (D37, D39).
 */
@Component({
  selector: 'sl-reviews',
  imports: [TranslocoPipe, FormsModule],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <h1 class="sl-title">{{ 'admin.reviews.title' | transloco }}</h1>
    <p class="sl-muted subtitle">
      {{ 'admin.reviews.subtitle' | transloco: { count: items()?.length ?? 0 } }}
    </p>

    <section aria-live="polite" [attr.aria-busy]="loading()">
      @if (error()) {
        <div class="sl-card">
          <p>{{ 'common.checkConnection' | transloco }}</p>
          <button class="sl-button" type="button" (click)="load()">
            {{ 'common.tryAgain' | transloco }}
          </button>
        </div>
      } @else if (loading() && items() === null) {
        <p class="sl-card sl-muted">{{ 'common.loading' | transloco }}</p>
      } @else if (items()?.length === 0) {
        <p class="sl-card sl-muted">{{ 'admin.reviews.empty' | transloco }}</p>
      } @else {
        <ul class="reviews">
          @for (item of items() ?? []; track item.id) {
            <li class="sl-card review">
              <p class="strong">
                {{
                  'admin.reviews.stars'
                    | transloco: { stars: item.stars, name: item.specialist.fullName }
                }}
              </p>
              <p class="sl-meta">{{ about(item) }}</p>
              <p class="text" [attr.lang]="item.sessionLanguage">{{ item.text }}</p>
              @for (flag of item.flags; track flag) {
                <span class="sl-pill sl-pill--warning">{{
                  'admin.reviews.flag_' + flag | transloco
                }}</span>
              }
              @if (canChange()) {
                <div class="actions">
                  <button
                    type="button"
                    class="sl-button"
                    [disabled]="busy()"
                    (click)="askHide(item)"
                  >
                    {{ 'admin.reviews.hide' | transloco }}
                  </button>
                  <button
                    type="button"
                    class="sl-button sl-button--primary"
                    [disabled]="busy()"
                    (click)="publish(item)"
                  >
                    {{
                      (hasContacts(item) ? 'admin.reviews.publishMasked' : 'admin.reviews.publish')
                        | transloco
                    }}
                  </button>
                </div>
              }
            </li>
          }
        </ul>
      }
      @if (problem()) {
        <p class="sl-meta problem" role="alert">{{ problem() }}</p>
      }
    </section>

    <dialog #hideDialog class="sl-dialog" aria-labelledby="hide-title">
      <h2 id="hide-title" class="dialog-title">{{ 'admin.reviews.hideTitle' | transloco }}</h2>
      <p class="sl-muted">{{ 'admin.reviews.hideNote' | transloco }}</p>
      <label class="sl-field">
        <span>{{ 'admin.reviews.reasonLabel' | transloco }}</span>
        <textarea class="sl-textarea" rows="3" maxlength="1000" [(ngModel)]="reason"></textarea>
      </label>
      <div class="dialog-actions">
        <button type="button" class="sl-button" (click)="hideDialog.close()">
          {{ 'admin.common.cancel' | transloco }}
        </button>
        <button
          type="button"
          class="sl-button sl-button--danger"
          [disabled]="!reason().trim()"
          (click)="hide()"
        >
          {{ 'admin.reviews.confirmHide' | transloco }}
        </button>
      </div>
    </dialog>
  `,
  styles: `
    .subtitle {
      margin: var(--space-4) 0 var(--space-20);
      max-width: 72ch;
    }
    .reviews {
      list-style: none;
      margin: 0;
      padding: 0;
      display: grid;
      gap: var(--space-12);
    }
    .review p {
      margin: 0 0 var(--space-4);
    }
    .strong {
      font-weight: 700;
    }
    .text {
      margin: var(--space-8) 0 !important;
      max-width: 72ch;
    }
    .actions,
    .dialog-actions {
      display: flex;
      justify-content: flex-end;
      gap: var(--space-8);
      margin-top: var(--space-12);
    }
    .dialog-title {
      margin: 0 0 var(--space-8);
      font-size: 1.125rem;
    }
    .problem {
      color: var(--danger);
    }
  `,
})
export class ReviewsPage {
  private readonly api = inject(CoreApi);
  private readonly transloco = inject(TranslocoService);
  private readonly staff = inject(StaffSession);
  private readonly hideDialog = viewChild<ElementRef<HTMLDialogElement>>('hideDialog');

  readonly items = signal<Item[] | null>(null);
  readonly loading = signal(false);
  readonly error = signal(false);
  readonly busy = signal(false);
  readonly problem = signal<string | null>(null);
  readonly reason = signal('');
  private readonly hiding = signal<Item | null>(null);
  private readonly lang = signal(this.transloco.getActiveLang());
  readonly canChange = computed(() => this.staff.accessTo('reviews') === 'F');

  constructor() {
    this.transloco.langChanges$.subscribe((lang) => this.lang.set(lang));
    void this.load();
  }

  async load() {
    this.loading.set(true);
    this.error.set(false);
    try {
      this.items.set(orThrow(await this.api.client.GET('/admin/v1/reviews')));
    } catch {
      this.error.set(true);
    } finally {
      this.loading.set(false);
    }
  }

  hasContacts(item: Item): boolean {
    return item.flags.includes('CONTACT_DETAILS');
  }

  about(item: Item): string {
    const guest = item.seekerRef.startsWith('G-');
    const who = this.transloco.translate(guest ? 'admin.reviews.guest' : 'admin.reviews.seeker', {
      ref: guest ? `#${item.seekerRef}` : item.seekerRef,
    });
    const when = this.transloco.translate('admin.reviews.session', {
      date: shortDate(item.sessionAt, this.lang()),
    });
    return `${who} · ${when} · ${capitalised(languageName(item.sessionLanguage, this.lang()))}`;
  }

  async publish(item: Item) {
    await this.act(item, () =>
      this.api.client.POST('/admin/v1/reviews/{reviewId}/publication', {
        params: { path: { reviewId: item.id } },
        body: { maskContacts: this.hasContacts(item) },
      }),
    );
  }

  askHide(item: Item) {
    this.hiding.set(item);
    this.reason.set('');
    this.hideDialog()?.nativeElement.showModal();
  }

  async hide() {
    const item = this.hiding();
    this.hideDialog()?.nativeElement.close();
    if (!item) return;
    await this.act(item, () =>
      this.api.client.POST('/admin/v1/reviews/{reviewId}/hiding', {
        params: { path: { reviewId: item.id } },
        body: { reason: this.reason().trim() },
      }),
    );
  }

  /** A decided review leaves the queue. */
  private async act(
    item: Item,
    call: () => Promise<{ data?: Item; error?: unknown; response: Response }>,
  ) {
    this.busy.set(true);
    this.problem.set(null);
    try {
      orThrow(await call());
      this.items.update((items) => items?.filter((i) => i.id !== item.id) ?? null);
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
}
