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

type Item = CoreSchemas['ContentItem'];
type Draft = CoreSchemas['ContentDraft'];
type Dial = CoreSchemas['EmergencyNumber'];

const SERVICES: Dial['service'][] = ['police', 'ambulance', 'fire', 'helpline'];

/**
 * Admin screen 16: one item side by side in EN and FR (ContentEditor.dc.html; D44). Saving a
 * language clears its done mark; a crisis resource needs a clinical check after every edit, and
 * publishing stays blocked until then.
 */
@Component({
  selector: 'sl-content-editor',
  imports: [TranslocoPipe, FormsModule, RouterLink],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <a class="sl-meta back" routerLink="/content">{{ 'admin.content.title' | transloco }}</a>

    @if (error()) {
      <div class="sl-card">
        <p>{{ 'common.checkConnection' | transloco }}</p>
        <button class="sl-button" type="button" (click)="load()">
          {{ 'common.tryAgain' | transloco }}
        </button>
      </div>
    } @else if (item(); as i) {
      <div class="head">
        <div>
          <h1 class="sl-title">{{ draft().titleEn || i.slug }}</h1>
          <p class="sl-muted">
            {{ 'admin.content.types.' + i.type | transloco }} ·
            {{ 'admin.content.where.' + i.type | transloco }}
          </p>
        </div>
        <span class="sl-pill">{{
          'admin.content.state.' + i.status | transloco: { version: i.version }
        }}</span>
      </div>

      <div class="marks">
        <label class="option">
          <input
            type="checkbox"
            [checked]="i.enDone"
            [disabled]="!canChange() || dirty()"
            (change)="done('en', !i.enDone)"
          />
          <span>{{ 'admin.content.enDone' | transloco }}</span>
        </label>
        <label class="option">
          <input
            type="checkbox"
            [checked]="i.frDone"
            [disabled]="!canChange() || dirty()"
            (change)="done('fr', !i.frDone)"
          />
          <span>{{ 'admin.content.frDone' | transloco }}</span>
        </label>
        @if (i.clinical !== 'NOT_NEEDED') {
          <span class="sl-pill" [class.sl-pill--warning]="i.clinical !== 'APPROVED'">{{
            'admin.content.clinicalState.' + i.clinical | transloco: { name: i.clinicalBy ?? '' }
          }}</span>
        }
      </div>

      @if (aiNote(); as note) {
        <p class="sl-meta ai-note" role="status">{{ note | transloco }}</p>
      }
      <div class="columns">
        <section class="sl-card" aria-labelledby="en-title" lang="en">
          <h2 id="en-title" class="section">{{ 'admin.content.englishCaps' | transloco }}</h2>
          @if (canChange()) {
            <button
              type="button"
              class="sl-button sl-button--quiet"
              [disabled]="drafting()"
              (click)="aiDraft('fr')"
            >
              {{ 'admin.content.aiDraft_en' | transloco }}
            </button>
          }
          <label class="sl-field">
            <span>{{ 'admin.content.fieldTitle' | transloco }}</span>
            <input
              class="sl-input"
              maxlength="200"
              [disabled]="!canChange()"
              [ngModel]="draft().titleEn"
              (ngModelChange)="edit('titleEn', $event)"
            />
          </label>
          <label class="sl-field">
            <span>{{ 'admin.content.fieldBody' | transloco }}</span>
            <textarea
              class="sl-textarea body"
              rows="14"
              [disabled]="!canChange()"
              [ngModel]="draft().bodyEn"
              (ngModelChange)="edit('bodyEn', $event)"
            ></textarea>
          </label>
        </section>
        <section class="sl-card" aria-labelledby="fr-title" lang="fr">
          <h2 id="fr-title" class="section">{{ 'admin.content.frenchCaps' | transloco }}</h2>
          @if (canChange()) {
            <button
              type="button"
              class="sl-button sl-button--quiet"
              [disabled]="drafting()"
              (click)="aiDraft('en')"
            >
              {{ 'admin.content.aiDraft_fr' | transloco }}
            </button>
          }
          <label class="sl-field">
            <span>{{ 'admin.content.fieldTitle' | transloco }}</span>
            <input
              class="sl-input"
              maxlength="200"
              [disabled]="!canChange()"
              [ngModel]="draft().titleFr"
              (ngModelChange)="edit('titleFr', $event)"
            />
          </label>
          <label class="sl-field">
            <span>{{ 'admin.content.fieldBody' | transloco }}</span>
            <textarea
              class="sl-textarea body"
              rows="14"
              [disabled]="!canChange()"
              [ngModel]="draft().bodyFr"
              (ngModelChange)="edit('bodyFr', $event)"
            ></textarea>
          </label>
        </section>
      </div>

      @if (i.type === 'CRISIS_RESOURCE') {
        <section class="sl-card numbers" aria-labelledby="numbers-title">
          <h2 id="numbers-title" class="section">{{ 'admin.content.numbers' | transloco }}</h2>
          <p class="sl-meta">{{ 'admin.content.numbersNote' | transloco }}</p>
          @for (n of draft().numbers ?? []; track $index) {
            <div class="number">
              <label class="sl-field">
                <span>{{ 'admin.content.service' | transloco }}</span>
                <select
                  class="sl-select"
                  [disabled]="!canChange()"
                  [ngModel]="n.service"
                  (ngModelChange)="setNumber($index, 'service', $event)"
                >
                  @for (s of services; track s) {
                    <option [value]="s">{{ 'admin.content.services.' + s | transloco }}</option>
                  }
                </select>
              </label>
              <label class="sl-field">
                <span>{{ 'admin.content.number' | transloco }}</span>
                <input
                  class="sl-input"
                  inputmode="tel"
                  maxlength="20"
                  [disabled]="!canChange()"
                  [ngModel]="n.number"
                  (ngModelChange)="setNumber($index, 'number', $event)"
                />
              </label>
              @if (canChange()) {
                <button
                  type="button"
                  class="sl-button sl-button--quiet"
                  (click)="removeNumber($index)"
                >
                  {{ 'admin.team.remove' | transloco }}
                </button>
              }
            </div>
          }
          @if (canChange()) {
            <button type="button" class="sl-button" (click)="addNumber()">
              {{ 'admin.content.addNumber' | transloco }}
            </button>
          }
        </section>
      }

      @if (i.clinical !== 'NOT_NEEDED' && i.clinical !== 'APPROVED') {
        <p class="sl-meta">{{ 'admin.content.clinicalNote' | transloco }}</p>
      }

      <div class="actions">
        @if (canChange()) {
          <button type="button" class="sl-button" [disabled]="busy() || !dirty()" (click)="save()">
            {{ 'admin.common.save' | transloco }}
          </button>
          @if (i.clinical === 'NEEDED') {
            <button
              type="button"
              class="sl-button"
              [disabled]="busy() || dirty()"
              (click)="requestCheck()"
            >
              {{ 'admin.content.sendCheck' | transloco }}
            </button>
          }
        }
        @if (i.canApprove) {
          <button
            type="button"
            class="sl-button"
            [disabled]="busy() || dirty()"
            (click)="approve()"
          >
            {{ 'admin.content.approveCheck' | transloco }}
          </button>
        }
        @if (canChange()) {
          <button
            type="button"
            class="sl-button sl-button--primary"
            [disabled]="busy() || dirty() || !i.canPublish"
            (click)="askPublish()"
          >
            {{ 'admin.content.publish' | transloco }}
          </button>
        }
      </div>
      @if (problem()) {
        <p class="sl-meta problem" role="alert">{{ problem() }}</p>
      }
    } @else {
      <p class="sl-card sl-muted">{{ 'common.loading' | transloco }}</p>
    }

    <dialog #publishDialog class="sl-dialog" aria-labelledby="publish-title">
      <h2 id="publish-title" class="section">
        {{ 'admin.content.confirmPublish' | transloco: { version: (item()?.version ?? 0) + 1 } }}
      </h2>
      <label class="sl-field">
        <span>{{ 'admin.bookings.reasonRequired' | transloco }}</span>
        <textarea class="sl-textarea" rows="2" maxlength="1000" [(ngModel)]="reason"></textarea>
      </label>
      <div class="actions">
        <button type="button" class="sl-button" (click)="publishDialog.close()">
          {{ 'admin.common.cancel' | transloco }}
        </button>
        <button
          type="button"
          class="sl-button sl-button--primary"
          [disabled]="busy() || !reason().trim()"
          (click)="publish()"
        >
          {{ 'admin.content.publish' | transloco }}
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
    .marks {
      display: flex;
      flex-wrap: wrap;
      gap: var(--space-16);
      align-items: center;
      margin: var(--space-16) 0;
    }
    .option {
      display: flex;
      gap: var(--space-8);
      align-items: center;
      min-height: 44px;
    }
    .columns {
      display: grid;
      grid-template-columns: 1fr 1fr;
      gap: var(--space-16);
    }
    .section {
      margin: 0 0 var(--space-12);
      font-size: var(--type-title-md-size);
    }
    .body {
      min-height: 16rem;
    }
    .numbers {
      margin-top: var(--space-16);
    }
    .number {
      display: flex;
      gap: var(--space-12);
      align-items: end;
      margin-bottom: var(--space-8);
    }
    .actions {
      display: flex;
      justify-content: flex-end;
      gap: var(--space-8);
      margin-top: var(--space-16);
    }
    .problem {
      color: var(--danger);
    }
  `,
})
export class ContentEditorPage {
  private readonly api = inject(CoreApi);
  private readonly transloco = inject(TranslocoService);
  private readonly staff = inject(StaffSession);
  private readonly publishDialog = viewChild<ElementRef<HTMLDialogElement>>('publishDialog');

  readonly id = input.required<string>();
  readonly services = SERVICES;
  readonly item = signal<Item | null>(null);
  readonly draft = signal<Draft>({ titleEn: '', bodyEn: '', titleFr: '', bodyFr: '', numbers: [] });
  readonly dirty = signal(false);
  readonly error = signal(false);
  readonly busy = signal(false);
  readonly problem = signal<string | null>(null);
  readonly reason = signal('');
  readonly canChange = computed(() => this.staff.accessTo('content') === 'F');

  constructor() {
    effect(() => {
      this.id();
      void untracked(() => this.load());
    });
  }

  async load() {
    this.error.set(false);
    try {
      this.show(
        orThrow(
          await this.api.client.GET('/admin/v1/content/{contentId}', {
            params: { path: { contentId: this.id() } },
          }),
        ),
      );
    } catch {
      this.error.set(true);
    }
  }

  private show(item: Item) {
    this.item.set(item);
    this.draft.set({ ...item.draft, numbers: [...(item.draft.numbers ?? [])] });
    this.dirty.set(false);
  }

  readonly drafting = signal(false);
  readonly aiNote = signal<string | null>(null);

  /**
   * Fills the other language with an AI draft for the editor to check (Phase 3; D56). Nothing is
   * saved until the editor saves, and the translation is marked done by a person as before.
   */
  async aiDraft(from: 'en' | 'fr') {
    this.drafting.set(true);
    this.aiNote.set(null);
    try {
      const result = await this.api.client.POST('/admin/v1/content/{contentId}/translation-draft', {
        params: { path: { contentId: this.id() } },
        body: { from },
      });
      const draft = result.data;
      if (!result.response.ok || !draft) {
        this.aiNote.set('admin.content.aiDraftNone');
        return;
      }
      const to = from === 'en' ? 'Fr' : 'En';
      if (draft.title) this.edit(`title${to}` as 'titleFr' | 'titleEn', draft.title);
      if (draft.body) this.edit(`body${to}` as 'bodyFr' | 'bodyEn', draft.body);
      this.aiNote.set('admin.content.aiDraftNote');
    } catch {
      this.aiNote.set('admin.content.aiDraftNone');
    } finally {
      this.drafting.set(false);
    }
  }

  edit(field: 'titleEn' | 'bodyEn' | 'titleFr' | 'bodyFr', value: string) {
    this.draft.update((d) => ({ ...d, [field]: value }));
    this.dirty.set(true);
  }

  setNumber(index: number, field: keyof Dial, value: string) {
    this.draft.update((d) => ({
      ...d,
      numbers: (d.numbers ?? []).map((n, i) => (i === index ? { ...n, [field]: value } : n)),
    }));
    this.dirty.set(true);
  }

  addNumber() {
    this.draft.update((d) => ({
      ...d,
      numbers: [...(d.numbers ?? []), { service: 'helpline', number: '' }],
    }));
    this.dirty.set(true);
  }

  removeNumber(index: number) {
    this.draft.update((d) => ({ ...d, numbers: (d.numbers ?? []).filter((_, i) => i !== index) }));
    this.dirty.set(true);
  }

  async save() {
    await this.act(() =>
      this.api.client.PUT('/admin/v1/content/{contentId}', {
        params: { path: { contentId: this.id() } },
        body: this.draft(),
      }),
    );
  }

  async done(language: 'en' | 'fr', done: boolean) {
    await this.act(() =>
      this.api.client.PUT('/admin/v1/content/{contentId}/translation-status', {
        params: { path: { contentId: this.id() } },
        body: { language, done },
      }),
    );
  }

  async requestCheck() {
    await this.act(() =>
      this.api.client.POST('/admin/v1/content/{contentId}/clinical-check', {
        params: { path: { contentId: this.id() } },
      }),
    );
  }

  async approve() {
    await this.act(() =>
      this.api.client.POST('/admin/v1/content/{contentId}/clinical-approval', {
        params: { path: { contentId: this.id() } },
      }),
    );
  }

  askPublish() {
    this.reason.set('');
    this.publishDialog()?.nativeElement.showModal();
  }

  async publish() {
    this.publishDialog()?.nativeElement.close();
    await this.act(() =>
      this.api.client.POST('/admin/v1/content/{contentId}/publication', {
        params: { path: { contentId: this.id() } },
        body: { reason: this.reason().trim() },
      }),
    );
  }

  private async act(call: () => Promise<{ data?: Item; error?: unknown; response: Response }>) {
    this.busy.set(true);
    this.problem.set(null);
    try {
      this.show(orThrow(await call()));
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
