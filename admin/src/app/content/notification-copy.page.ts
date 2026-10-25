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
import { dateTime } from '../shared/format';

type Message = CoreSchemas['NotificationCopy'];

const CHANNELS = ['push', 'sms', 'email', 'word', 'category'];

/**
 * Every push, SMS and email in EN and FR (D61), opened from Content. Super admins and Content
 * editors change a message in both languages with a reason; the approved wording from
 * notification-copy.md stays beside it and can be restored. Core checks the copy rules on save.
 */
@Component({
  selector: 'sl-notification-copy',
  imports: [TranslocoPipe, FormsModule, RouterLink],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <a routerLink="/content" class="sl-meta">{{ 'admin.copy.back' | transloco }}</a>
    <h1 class="sl-title">{{ 'admin.copy.title' | transloco }}</h1>
    <p class="sl-muted subtitle">{{ 'admin.copy.subtitle' | transloco }}</p>

    @if (error()) {
      <div class="sl-card">
        <p>{{ 'common.checkConnection' | transloco }}</p>
        <button class="sl-button" type="button" (click)="load()">
          {{ 'common.tryAgain' | transloco }}
        </button>
      </div>
    } @else if (messages(); as list) {
      @for (channel of channels; track channel) {
        @if (inChannel(list, channel).length) {
          <section class="sl-card" [attr.aria-labelledby]="'copy-' + channel">
            <h2 [id]="'copy-' + channel" class="section">
              {{ 'admin.copy.channel.' + channel | transloco }}
            </h2>
            <ul class="rows">
              @for (m of inChannel(list, channel); track m.key) {
                <li class="row">
                  <div>
                    <p class="sl-meta">
                      {{ m.key }}
                      @if (m.version > 0) {
                        · {{ 'admin.settings.changed' | transloco: { version: m.version, name: m.updatedBy, when: when(m.updatedAt) } }}
                      }
                      @if (m.forStaff) {
                        · {{ 'admin.copy.forStaff' | transloco }}
                      }
                    </p>
                    <p lang="en">{{ m.en }}</p>
                    <p lang="fr">{{ m.fr }}</p>
                  </div>
                  @if (canChange()) {
                    <button type="button" class="sl-button" (click)="ask(m)">
                      {{ 'admin.settings.change' | transloco }}
                    </button>
                  } @else {
                    <span></span>
                  }
                </li>
              }
            </ul>
          </section>
        }
      }
    } @else {
      <p class="sl-card sl-muted">{{ 'common.loading' | transloco }}</p>
    }

    <dialog #copyDialog class="sl-dialog" aria-labelledby="copy-title">
      @if (editing(); as m) {
        <h2 id="copy-title" class="section">{{ m.key }}</h2>
        <p class="sl-muted">{{ 'admin.copy.rules' | transloco }}</p>
        <label class="sl-field">
          <span>{{ 'admin.copy.english' | transloco }}</span>
          <textarea class="sl-textarea" rows="3" maxlength="1000" lang="en" [(ngModel)]="en"></textarea>
        </label>
        <p class="sl-meta">{{ 'admin.copy.approved' | transloco }}: {{ m.approvedEn }}</p>
        <label class="sl-field">
          <span>{{ 'admin.copy.french' | transloco }}</span>
          <textarea class="sl-textarea" rows="3" maxlength="1000" lang="fr" [(ngModel)]="fr"></textarea>
        </label>
        <p class="sl-meta">{{ 'admin.copy.approved' | transloco }}: {{ m.approvedFr }}</p>
        <button type="button" class="sl-button" (click)="restore(m)">
          {{ 'admin.copy.restore' | transloco }}
        </button>
        <label class="sl-field">
          <span>{{ 'admin.bookings.reasonRequired' | transloco }}</span>
          <textarea class="sl-textarea" rows="3" maxlength="500" [(ngModel)]="reason"></textarea>
        </label>
      }
      @if (problem()) {
        <p class="sl-meta problem" role="alert">{{ problem() }}</p>
      }
      <div class="actions">
        <button type="button" class="sl-button" (click)="copyDialog.close()">
          {{ 'admin.common.cancel' | transloco }}
        </button>
        <button
          type="button"
          class="sl-button sl-button--primary"
          [disabled]="busy() || !reason().trim() || !en().trim() || !fr().trim()"
          (click)="save()"
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
      grid-template-columns: minmax(0, 1fr) 7rem;
      gap: var(--space-12);
      align-items: center;
      min-height: 44px;
      padding: var(--space-8) 0;
      border-top: 1px solid var(--line);
    }
    .row p {
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
export class NotificationCopyPage {
  private readonly api = inject(CoreApi);
  private readonly transloco = inject(TranslocoService);
  private readonly staff = inject(StaffSession);
  private readonly copyDialog = viewChild<ElementRef<HTMLDialogElement>>('copyDialog');

  readonly channels = CHANNELS;
  readonly messages = signal<Message[] | null>(null);
  readonly error = signal(false);
  readonly busy = signal(false);
  readonly problem = signal<string | null>(null);
  readonly editing = signal<Message | null>(null);
  readonly en = signal('');
  readonly fr = signal('');
  readonly reason = signal('');
  readonly canChange = computed(() => this.staff.accessTo('content') === 'F');
  private readonly lang = signal(this.transloco.getActiveLang());

  constructor() {
    this.transloco.langChanges$.subscribe((lang) => this.lang.set(lang));
    void this.load();
  }

  async load() {
    this.error.set(false);
    try {
      this.messages.set(orThrow(await this.api.client.GET('/admin/v1/notification-copy')));
    } catch {
      this.error.set(true);
    }
  }

  inChannel(list: Message[], channel: string): Message[] {
    return list.filter((m) => m.channel === channel);
  }

  when(iso: string | undefined): string {
    return dateTime(iso, this.lang());
  }

  ask(m: Message) {
    this.editing.set(m);
    this.en.set(m.en);
    this.fr.set(m.fr);
    this.reason.set('');
    this.problem.set(null);
    this.copyDialog()?.nativeElement.showModal();
  }

  restore(m: Message) {
    this.en.set(m.approvedEn);
    this.fr.set(m.approvedFr);
  }

  async save() {
    const m = this.editing();
    if (!m) return;
    this.busy.set(true);
    this.problem.set(null);
    try {
      orThrow(
        await this.api.client.PUT('/admin/v1/notification-copy/{key}', {
          params: { path: { key: m.key } },
          body: {
            version: m.version,
            en: this.en().trim(),
            fr: this.fr().trim(),
            reason: this.reason().trim(),
          },
        }),
      );
      this.copyDialog()?.nativeElement.close();
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
