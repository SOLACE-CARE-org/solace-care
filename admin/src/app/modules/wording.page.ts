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

import { ApiProblem, orThrow } from '../api/core-api';
import { TrackingApi, type TrackingSchemas } from '../api/tracking-api';
import { StaffSession } from '../auth/staff-session';
import { BackLink } from '../shared/back-link';
import { canDraft, canReview } from './module-shared';

type WordingRule = TrackingSchemas['WordingRule'];
type Setting = TrackingSchemas['TrackingSetting'];
type Lang = 'en' | 'fr';
type Kind = 'BLOCK' | 'ALLOW';

/** What the server accepts as a pattern once folded (WordList.valid). */
export const PATTERN = /^[a-z0-9][a-z0-9' -]*[a-z0-9]\*?$/;

export function fold(text: string): string {
  return text
    .normalize('NFD')
    .replace(/\p{M}/gu, '')
    .toLowerCase()
    .replace(/’/g, "'")
    .trim()
    .replace(/\s+/g, ' ');
}

/**
 * The diagnostic word list and Tracking settings (D61). No design of its own yet: the settings follow
 * Settings.dc.html (rows with a Change button, a reason in a dialog) and the list follows the
 * DataTable guidelines. Clinical reviewers keep the word list; Operations and
 * Super admins change settings with a reason.
 */
@Component({
  selector: 'sl-wording',
  imports: [TranslocoPipe, FormsModule, BackLink],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <sl-back-link to="/modules" [label]="'admin.modules.title' | transloco" />
    <h1 class="sl-title">{{ 'admin.wording.title' | transloco }}</h1>
    <p class="sl-muted subtitle">{{ 'admin.wording.subtitle' | transloco }}</p>

    @if (error()) {
      <div class="sl-card">
        <p>{{ 'common.checkConnection' | transloco }}</p>
        <button class="sl-button" type="button" (click)="load()">
          {{ 'common.tryAgain' | transloco }}
        </button>
      </div>
    } @else if (rules() === null || settings() === null) {
      <p class="sl-card sl-muted">{{ 'common.loading' | transloco }}</p>
    } @else {
      <div class="panels">
        <section class="sl-card" aria-labelledby="settings-title">
          <h2 id="settings-title" class="sl-section">
            {{ 'admin.wording.settings.title' | transloco }}
          </h2>
          <ul class="rows">
            @for (s of settings() ?? []; track s.key) {
              <li class="row">
                <div>
                  <p>{{ 'admin.wording.settings.name.' + s.key | transloco }}</p>
                  <p class="sl-meta">
                    @if (s.updatedBy) {
                      {{
                        'admin.wording.settings.changedBy'
                          | transloco: { who: s.updatedBy, when: date(s.updatedAt) }
                      }}
                    } @else {
                      {{
                        'admin.wording.settings.defaultIs'
                          | transloco
                            : {
                                value:
                                  ('admin.wording.settings.unit.' + s.unit
                                  | transloco: { n: s.defaultValue }),
                              }
                      }}
                    }
                  </p>
                </div>
                <span class="value">
                  {{ 'admin.wording.settings.unit.' + s.unit | transloco: { n: s.value } }}
                </span>
                @if (mayChangeSettings()) {
                  <button
                    type="button"
                    class="sl-button"
                    [attr.aria-label]="
                      'admin.wording.settings.edit'
                        | transloco: { name: ('admin.wording.settings.name.' + s.key | transloco) }
                    "
                    (click)="edit(s)"
                  >
                    {{ 'admin.wording.settings.change' | transloco }}
                  </button>
                } @else {
                  <span></span>
                }
              </li>
            }
          </ul>
          <p class="sl-meta foot">{{ 'admin.wording.settings.help' | transloco }}</p>
        </section>

        <section class="sl-card" aria-labelledby="try-title">
          <h2 id="try-title" class="sl-section">{{ 'admin.wording.words.try' | transloco }}</h2>
          <form class="inline" (ngSubmit)="check()">
            <label class="sl-field grow">
              <span>{{ 'admin.wording.words.tryText' | transloco }}</span>
              <input class="sl-input" name="tryText" maxlength="1000" [(ngModel)]="tryText" />
            </label>
            <label class="sl-field">
              <span>{{ 'admin.wording.words.col.language' | transloco }}</span>
              <select class="sl-select" name="tryLang" [(ngModel)]="tryLang">
                @for (l of langs; track l) {
                  <option [value]="l">{{ 'admin.wording.words.language.' + l | transloco }}</option>
                }
              </select>
            </label>
            <button type="submit" class="sl-button" [disabled]="busy() || !tryText.trim()">
              {{ 'admin.wording.words.check' | transloco }}
            </button>
          </form>
          @if (result(); as r) {
            <p
              role="status"
              class="sl-pill result"
              [class.sl-pill--brand]="r.allowed"
              [class.sl-pill--danger]="!r.allowed"
            >
              @if (r.allowed) {
                {{ 'admin.wording.words.passes' | transloco }}
              } @else {
                {{
                  'admin.wording.words.blocked' | transloco: { patterns: r.blockedBy.join(', ') }
                }}
              }
            </p>
          }
        </section>
      </div>

      <section class="sl-card list" aria-labelledby="words-title">
        <h2 id="words-title" class="sl-section">{{ 'admin.wording.words.title' | transloco }}</h2>
        <p class="sl-muted help">{{ 'admin.wording.words.help' | transloco }}</p>

        @if (mayEditWords()) {
          <form class="inline" (ngSubmit)="add()">
            <label class="sl-field grow">
              <span>{{ 'admin.wording.words.pattern' | transloco }}</span>
              <input
                class="sl-input"
                name="pattern"
                maxlength="60"
                autocomplete="off"
                [(ngModel)]="pattern"
              />
            </label>
            <label class="sl-field">
              <span>{{ 'admin.wording.words.col.language' | transloco }}</span>
              <select class="sl-select" name="language" [(ngModel)]="language">
                @for (l of langs; track l) {
                  <option [value]="l">{{ 'admin.wording.words.language.' + l | transloco }}</option>
                }
              </select>
            </label>
            <label class="sl-field">
              <span>{{ 'admin.wording.words.col.kind' | transloco }}</span>
              <select class="sl-select" name="kind" [(ngModel)]="kind">
                <option value="BLOCK">{{ 'admin.wording.words.kind.BLOCK' | transloco }}</option>
                <option value="ALLOW">{{ 'admin.wording.words.kind.ALLOW' | transloco }}</option>
              </select>
            </label>
            <button
              type="submit"
              class="sl-button sl-button--primary"
              [disabled]="busy() || !pattern.trim()"
            >
              {{ 'admin.wording.words.add' | transloco }}
            </button>
          </form>
        }
        @if (problem()) {
          <p class="sl-meta problem" role="alert">{{ problem()! | transloco }}</p>
        }

        <div class="table">
          <table class="sl-table">
            <thead>
              <tr>
                <th scope="col">{{ 'admin.wording.words.col.pattern' | transloco }}</th>
                <th scope="col">{{ 'admin.wording.words.col.language' | transloco }}</th>
                <th scope="col">{{ 'admin.wording.words.col.kind' | transloco }}</th>
                <th scope="col">{{ 'admin.wording.words.col.addedBy' | transloco }}</th>
                @if (mayEditWords()) {
                  <th scope="col">
                    <span class="sr-only">{{ 'admin.wording.words.removeCol' | transloco }}</span>
                  </th>
                }
              </tr>
            </thead>
            <tbody>
              @for (r of rules() ?? []; track r.id) {
                <tr>
                  <td class="strong">{{ r.pattern }}</td>
                  <td>{{ 'admin.wording.words.language.' + r.language | transloco }}</td>
                  <td>
                    <span class="sl-pill" [class.sl-pill--brand]="r.kind === 'ALLOW'">
                      {{ 'admin.wording.words.kind.' + r.kind | transloco }}
                    </span>
                  </td>
                  <td>{{ r.createdBy || ('admin.wording.words.launch' | transloco) }}</td>
                  @if (mayEditWords()) {
                    <td class="end">
                      <button
                        type="button"
                        class="sl-button sl-button--danger"
                        [disabled]="busy()"
                        [attr.aria-label]="
                          'admin.wording.words.remove' | transloco: { pattern: r.pattern }
                        "
                        (click)="remove(r)"
                      >
                        {{ 'admin.wording.words.removeCol' | transloco }}
                      </button>
                    </td>
                  }
                </tr>
              }
            </tbody>
          </table>
        </div>
      </section>
    }

    <dialog
      #changeDialog
      class="sl-dialog"
      aria-labelledby="change-title"
      (close)="editing.set(null)"
    >
      @if (editingSetting(); as s) {
        <h2 id="change-title" class="sl-section">
          {{ 'admin.wording.settings.name.' + s.key | transloco }}
        </h2>
        <p class="sl-muted">
          {{
            'admin.wording.settings.defaultIs'
              | transloco
                : {
                    value:
                      ('admin.wording.settings.unit.' + s.unit | transloco: { n: s.defaultValue }),
                  }
          }}
        </p>
        <form class="dialog-form" (ngSubmit)="saveSetting(s)">
          <label class="sl-field">
            <span>{{
              'admin.wording.settings.newValue' | transloco: { min: s.min, max: s.max }
            }}</span>
            <input
              class="sl-input"
              type="number"
              name="value"
              [min]="s.min"
              [max]="s.max"
              [(ngModel)]="value"
            />
          </label>
          <label class="sl-field">
            <span>{{ 'admin.wording.settings.reason' | transloco }}</span>
            <textarea
              class="sl-textarea"
              name="reason"
              rows="3"
              maxlength="500"
              [(ngModel)]="reason"
            ></textarea>
          </label>
          @if (problem()) {
            <p class="sl-meta problem" role="alert">{{ problem()! | transloco }}</p>
          }
          <div class="actions">
            <button type="button" class="sl-button" (click)="closeEdit()">
              {{ 'admin.wording.settings.cancel' | transloco }}
            </button>
            <button
              type="submit"
              class="sl-button sl-button--primary"
              [disabled]="
                busy() || !reason.trim() || value === null || value < s.min || value > s.max
              "
            >
              {{ 'admin.wording.settings.save' | transloco }}
            </button>
          </div>
        </form>
      }
    </dialog>
  `,
  styles: `
    .subtitle {
      margin: var(--space-4) 0 var(--space-20);
      max-width: 72ch;
    }
    .panels {
      display: grid;
      grid-template-columns: repeat(auto-fit, minmax(22rem, 1fr));
      gap: var(--space-16);
      align-items: start;
      margin-bottom: var(--space-16);
    }
    .rows {
      list-style: none;
      margin: 0;
      padding: 0;
    }
    .row {
      display: grid;
      grid-template-columns: minmax(0, 1fr) auto auto;
      gap: var(--space-12);
      align-items: center;
      min-height: 56px;
      padding: var(--space-8) 0;
      border-top: 1px solid var(--surface-sunken);
    }
    .row p {
      margin: 0;
    }
    .value,
    .strong {
      font-weight: 700;
      font-variant-numeric: tabular-nums;
    }
    .foot,
    .help {
      margin: var(--space-8) 0 0;
    }
    .help {
      margin: 0 0 var(--space-16);
      max-width: 80ch;
    }
    .inline {
      display: flex;
      flex-wrap: wrap;
      align-items: end;
      gap: var(--space-8);
      margin-bottom: var(--space-16);
    }
    .grow {
      flex: 1 1 16rem;
    }
    .result {
      margin: 0;
      white-space: normal;
    }
    .list {
      padding: var(--space-18) 0 0;
    }
    .list > h2,
    .list > p,
    .list > form {
      margin-left: var(--space-18);
      margin-right: var(--space-18);
    }
    .table {
      overflow-x: auto;
      border-top: 1px solid var(--line);
    }
    .end {
      text-align: right;
    }
    .problem {
      color: var(--danger);
      font-weight: 600;
    }
    .dialog-form {
      display: flex;
      flex-direction: column;
      gap: var(--space-12);
    }
    .actions {
      display: flex;
      justify-content: flex-end;
      gap: var(--space-8);
    }
    .sr-only {
      position: absolute;
      width: 1px;
      height: 1px;
      overflow: hidden;
      clip: rect(0 0 0 0);
    }
  `,
})
export class WordingPage {
  private readonly api = inject(TrackingApi);
  private readonly staff = inject(StaffSession);
  private readonly transloco = inject(TranslocoService);

  readonly langs: Lang[] = ['en', 'fr'];
  readonly rules = signal<WordingRule[] | null>(null);
  readonly settings = signal<Setting[] | null>(null);
  readonly error = signal(false);
  readonly busy = signal(false);
  readonly problem = signal<string | null>(null);
  readonly result = signal<{ allowed: boolean; blockedBy: string[] } | null>(null);
  readonly editing = signal<string | null>(null);
  readonly editingSetting = computed(
    () => this.settings()?.find((s) => s.key === this.editing()) ?? null,
  );
  private readonly changeDialog = viewChild<ElementRef<HTMLDialogElement>>('changeDialog');

  readonly mayEditWords = computed(() => canReview(this.staff.roles()));
  readonly mayChangeSettings = computed(() => canDraft(this.staff.roles()));

  pattern = '';
  language: Lang = 'en';
  kind: Kind = 'BLOCK';
  tryText = '';
  tryLang: Lang = 'en';
  value: number | null = null;
  reason = '';

  constructor() {
    void this.load();
  }

  async load() {
    this.error.set(false);
    try {
      const [rules, settings] = await Promise.all([
        this.api.client.GET('/admin/v1/tracking/wording-rules'),
        this.api.client.GET('/admin/v1/tracking/settings'),
      ]);
      this.rules.set(orThrow(rules));
      this.settings.set(orThrow(settings));
    } catch {
      this.error.set(true);
    }
  }

  date(at: string | undefined): string {
    if (!at) return '';
    return new Intl.DateTimeFormat(this.transloco.getActiveLang() === 'fr' ? 'fr-CM' : 'en-GB', {
      dateStyle: 'medium',
      timeStyle: 'short',
    }).format(new Date(at));
  }

  async add() {
    const pattern = fold(this.pattern);
    if (!PATTERN.test(pattern) || pattern.length > 60) {
      this.problem.set('admin.wording.words.invalid');
      return;
    }
    this.busy.set(true);
    this.problem.set(null);
    try {
      const added = orThrow(
        await this.api.client.POST('/admin/v1/tracking/wording-rules', {
          body: { language: this.language, pattern, kind: this.kind },
        }),
      );
      this.rules.update((rules) => [...(rules ?? []), added]);
      this.pattern = '';
    } catch (e) {
      this.problem.set(
        e instanceof ApiProblem && e.status === 409
          ? 'admin.wording.words.taken'
          : e instanceof ApiProblem && e.status === 400
            ? 'admin.wording.words.invalid'
            : 'common.checkConnection',
      );
    } finally {
      this.busy.set(false);
    }
  }

  async remove(rule: WordingRule) {
    this.busy.set(true);
    this.problem.set(null);
    try {
      const { error } = await this.api.client.DELETE(
        '/admin/v1/tracking/wording-rules/{wordingRuleId}',
        { params: { path: { wordingRuleId: rule.id } } },
      );
      if (error) throw error;
      this.rules.update((rules) => (rules ?? []).filter((r) => r.id !== rule.id));
    } catch {
      this.problem.set('common.checkConnection');
    } finally {
      this.busy.set(false);
    }
  }

  async check() {
    this.busy.set(true);
    try {
      this.result.set(
        orThrow(
          await this.api.client.POST('/admin/v1/tracking/wording-check', {
            body: { text: this.tryText, language: this.tryLang },
          }),
        ),
      );
    } catch {
      this.problem.set('common.checkConnection');
    } finally {
      this.busy.set(false);
    }
  }

  edit(s: Setting) {
    this.value = s.value;
    this.reason = '';
    this.problem.set(null);
    this.editing.set(s.key);
    this.changeDialog()?.nativeElement.showModal();
  }

  closeEdit() {
    this.changeDialog()?.nativeElement.close();
    this.editing.set(null);
  }

  async saveSetting(s: Setting) {
    if (this.value === null || !this.reason.trim()) return;
    this.busy.set(true);
    this.problem.set(null);
    try {
      const saved = orThrow(
        await this.api.client.PUT('/admin/v1/tracking/settings/{settingKey}', {
          params: { path: { settingKey: s.key } },
          body: { value: this.value, reason: this.reason.trim() },
        }),
      );
      this.settings.update((all) => (all ?? []).map((x) => (x.key === saved.key ? saved : x)));
      this.closeEdit();
    } catch {
      this.problem.set('common.checkConnection');
    } finally {
      this.busy.set(false);
    }
  }
}
