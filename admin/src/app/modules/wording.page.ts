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
import { LucideCircleCheck, LucideDynamicIcon, LucideTriangleAlert } from '@lucide/angular';

import { ApiProblem, orThrow } from '../api/core-api';
import { TrackingApi, type TrackingSchemas } from '../api/tracking-api';
import { StaffSession } from '../auth/staff-session';
import { TrackingNav } from './tracking-nav';
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
 * Admin screen 35: the diagnostic word list and Tracking settings (D61; design/screens/admin/png/
 * TrackingWording.png). The settings follow
 * Settings.dc.html (rows with a Change button, a reason in a dialog); the list follows the
 * DataTable guidelines, with Segmented filters. Clinical reviewers keep the word list; Operations and
 * Super admins change settings with a reason.
 */
@Component({
  selector: 'sl-wording',
  imports: [TranslocoPipe, FormsModule, TrackingNav, LucideDynamicIcon],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <sl-tracking-nav />
    <div class="sl-head">
      <div>
        <h1 class="sl-title">{{ 'admin.wording.title' | transloco }}</h1>
        <p class="sl-head__intro">{{ 'admin.wording.subtitle' | transloco }}</p>
      </div>
    </div>

    @if (error()) {
      <div class="sl-notice sl-notice--danger" role="alert">
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
                <div class="row-text">
                  <span class="row-name">{{
                    'admin.wording.settings.name.' + s.key | transloco
                  }}</span>
                  <span class="sl-hint">{{
                    'admin.wording.settings.hint.' + s.key | transloco
                  }}</span>
                  <span class="sl-hint">
                    @if (s.updatedBy) {
                      {{
                        'admin.wording.settings.changedBy'
                          | transloco: { who: s.updatedBy, when: date(s.updatedAt) }
                      }}
                    } @else {
                      {{
                        'admin.wording.settings.defaultIs'
                          | transloco: { value: unit(s, s.defaultValue) }
                      }}
                    }
                  </span>
                </div>
                <span class="value">{{ unit(s, s.value) }}</span>
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
                }
              </li>
            }
          </ul>
          <p class="sl-hint foot">{{ 'admin.wording.settings.help' | transloco }}</p>
        </section>

        <section class="sl-card" aria-labelledby="try-title">
          <h2 id="try-title" class="sl-section">{{ 'admin.wording.words.try' | transloco }}</h2>
          <p class="sl-hint try-hint">{{ 'admin.wording.words.tryHint' | transloco }}</p>
          <form class="try" (ngSubmit)="check()">
            <label class="sl-field">
              <span>{{ 'admin.wording.words.tryText' | transloco }}</span>
              <textarea
                class="sl-textarea"
                name="tryText"
                rows="3"
                maxlength="1000"
                [attr.lang]="tryLang"
                [(ngModel)]="tryText"
              ></textarea>
            </label>
            <div class="try-row">
              <div
                class="sl-tabs"
                role="radiogroup"
                [attr.aria-label]="'admin.wording.words.col.language' | transloco"
              >
                @for (l of langs; track l) {
                  <button
                    type="button"
                    role="radio"
                    [attr.aria-checked]="tryLang === l"
                    [attr.aria-selected]="tryLang === l"
                    (click)="tryLang = l; result.set(null)"
                  >
                    {{ 'admin.wording.words.language.' + l | transloco }}
                  </button>
                }
              </div>
              <button type="submit" class="sl-button" [disabled]="busy() || !tryText.trim()">
                {{ 'admin.wording.words.check' | transloco }}
              </button>
            </div>
          </form>
          @if (result(); as r) {
            <div
              role="status"
              class="sl-notice result"
              [class.sl-notice--brand]="r.allowed"
              [class.sl-notice--danger]="!r.allowed"
            >
              <svg
                [lucideIcon]="r.allowed ? passIcon : blockIcon"
                [size]="20"
                [strokeWidth]="1.8"
                aria-hidden="true"
              ></svg>
              @if (r.allowed) {
                <p>{{ 'admin.wording.words.passes' | transloco }}</p>
              } @else {
                <p>
                  <strong>{{ 'admin.wording.words.blockedLead' | transloco }}</strong>
                  {{
                    'admin.wording.words.blocked' | transloco: { patterns: r.blockedBy.join(', ') }
                  }}
                </p>
              }
            </div>
          }
        </section>
      </div>

      <section class="sl-card list" aria-labelledby="words-title">
        <div class="list-head">
          <div>
            <h2 id="words-title" class="sl-section">
              {{ 'admin.wording.words.title' | transloco }}
              <span class="sl-pill count">{{ shown().length }}</span>
            </h2>
            <p class="sl-hint help">{{ 'admin.wording.words.help' | transloco }}</p>
          </div>
          <div class="filters">
            <div
              class="sl-tabs"
              role="radiogroup"
              [attr.aria-label]="'admin.wording.words.col.kind' | transloco"
            >
              @for (k of kindFilters; track k) {
                <button
                  type="button"
                  role="radio"
                  [attr.aria-checked]="kindFilter() === k"
                  [attr.aria-selected]="kindFilter() === k"
                  (click)="kindFilter.set(k)"
                >
                  {{
                    (k === 'ALL' ? 'admin.wording.words.all' : 'admin.wording.words.kind.' + k)
                      | transloco
                  }}
                </button>
              }
            </div>
            <div
              class="sl-tabs"
              role="radiogroup"
              [attr.aria-label]="'admin.wording.words.col.language' | transloco"
            >
              @for (l of langFilters; track l) {
                <button
                  type="button"
                  role="radio"
                  [attr.aria-checked]="langFilter() === l"
                  [attr.aria-selected]="langFilter() === l"
                  (click)="langFilter.set(l)"
                >
                  {{
                    (l === 'ALL' ? 'admin.wording.words.all' : 'admin.wording.words.language.' + l)
                      | transloco
                  }}
                </button>
              }
            </div>
          </div>
        </div>

        @if (mayEditWords()) {
          <form
            class="add"
            (ngSubmit)="add()"
            [attr.aria-label]="'admin.wording.words.addTitle' | transloco"
          >
            <label class="sl-field grow">
              <span>{{ 'admin.wording.words.addTitle' | transloco }}</span>
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
          <p class="sl-error problem" role="alert">{{ problem()! | transloco }}</p>
        }

        <div class="table">
          @if (shown().length === 0) {
            <p class="sl-hint empty">{{ 'admin.wording.words.none' | transloco }}</p>
          } @else {
            <table class="sl-table">
              <thead>
                <tr>
                  <th scope="col">{{ 'admin.wording.words.col.pattern' | transloco }}</th>
                  <th scope="col">{{ 'admin.wording.words.col.language' | transloco }}</th>
                  <th scope="col">{{ 'admin.wording.words.col.kind' | transloco }}</th>
                  <th scope="col">{{ 'admin.wording.words.col.addedBy' | transloco }}</th>
                  @if (mayEditWords()) {
                    <th scope="col">
                      <span class="sl-sr-only">{{
                        'admin.wording.words.removeCol' | transloco
                      }}</span>
                    </th>
                  }
                </tr>
              </thead>
              <tbody>
                @for (r of shown(); track r.id) {
                  <tr>
                    <td class="strong" [attr.lang]="r.language">{{ r.pattern }}</td>
                    <td>{{ 'admin.wording.words.language.' + r.language | transloco }}</td>
                    <td>
                      <span
                        class="sl-pill"
                        [class.sl-pill--danger]="r.kind === 'BLOCK'"
                        [class.sl-pill--brand]="r.kind === 'ALLOW'"
                      >
                        {{ 'admin.wording.words.kind.' + r.kind | transloco }}
                      </span>
                    </td>
                    <td class="sl-muted">
                      {{ r.createdBy || ('admin.wording.words.launch' | transloco) }}
                    </td>
                    @if (mayEditWords()) {
                      <td class="end">
                        <button
                          type="button"
                          class="sl-link-button sl-link-button--danger"
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
          }
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
          {{
            'admin.wording.settings.dialogTitle'
              | transloco: { name: ('admin.wording.settings.name.' + s.key | transloco) }
          }}
        </h2>
        <p class="sl-muted dialog-lead">
          {{
            'admin.wording.settings.current'
              | transloco: { value: unit(s, s.value), default: unit(s, s.defaultValue) }
          }}
        </p>
        <form class="dialog-form" (ngSubmit)="saveSetting(s)">
          <label class="sl-field">
            <span>{{ 'admin.wording.settings.newValue' | transloco }}</span>
            <span class="value-input">
              <input
                class="sl-input"
                type="number"
                name="value"
                [min]="s.min"
                [max]="s.max"
                [(ngModel)]="value"
              />
              <span class="sl-muted">{{
                'admin.wording.settings.unitName.' + s.unit | transloco
              }}</span>
            </span>
            <span class="sl-hint">{{
              'admin.wording.settings.range' | transloco: { min: s.min, max: s.max }
            }}</span>
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
            <span class="sl-hint">{{ 'admin.wording.settings.reasonHint' | transloco }}</span>
          </label>
          @if (problem()) {
            <p class="sl-error" role="alert">{{ problem()! | transloco }}</p>
          }
          <div class="sl-actions">
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
    .panels {
      display: grid;
      grid-template-columns: minmax(0, 3fr) minmax(18rem, 2fr);
      gap: var(--space-16);
      align-items: start;
      margin-bottom: var(--space-16);
    }
    @media (max-width: 1100px) {
      .panels {
        grid-template-columns: minmax(0, 1fr);
      }
    }
    .rows {
      list-style: none;
      margin: 0;
      padding: 0;
    }
    .row {
      display: grid;
      grid-template-columns: minmax(0, 1fr) auto auto;
      gap: var(--space-14);
      align-items: center;
      padding: var(--space-12) 0;
      border-top: 1px solid var(--line);
    }
    .row-text {
      display: flex;
      flex-direction: column;
      gap: var(--space-2);
    }
    .row-name {
      font-weight: 600;
    }
    .value,
    .strong {
      font-weight: 700;
      font-variant-numeric: tabular-nums;
    }
    .value {
      white-space: nowrap;
    }
    .foot {
      margin: var(--space-8) 0 0;
    }
    .try-hint {
      margin: calc(var(--space-6) * -1) 0 var(--space-12);
    }
    .try {
      display: flex;
      flex-direction: column;
      gap: var(--space-10);
    }
    .try-row {
      display: flex;
      justify-content: space-between;
      align-items: center;
      gap: var(--space-8);
      flex-wrap: wrap;
    }
    .result {
      margin-top: var(--space-12);
    }
    .list {
      padding: var(--space-18) 0 0;
    }
    .list-head {
      display: flex;
      justify-content: space-between;
      align-items: flex-start;
      flex-wrap: wrap;
      gap: var(--space-12);
      padding: 0 var(--space-18);
    }
    .list-head .sl-section {
      display: flex;
      align-items: center;
      gap: var(--space-8);
    }
    .help {
      margin: calc(var(--space-6) * -1) 0 0;
      max-width: 72ch;
    }
    .filters {
      display: flex;
      flex-wrap: wrap;
      gap: var(--space-8);
    }
    .add {
      display: flex;
      flex-wrap: wrap;
      align-items: end;
      gap: var(--space-8);
      margin: var(--space-16) var(--space-18) 0;
      padding: var(--space-12) var(--space-14);
      border-radius: var(--radius-14);
      background: var(--surface-sunken);
    }
    .grow {
      flex: 1 1 16rem;
    }
    .problem {
      margin: var(--space-8) var(--space-18) 0;
    }
    .table {
      overflow-x: auto;
      margin-top: var(--space-16);
      border-top: 1px solid var(--line);
    }
    .empty {
      margin: 0;
      padding: var(--space-20) var(--space-18);
    }
    .end {
      text-align: right;
    }
    .dialog-lead {
      margin: calc(var(--space-6) * -1) 0 var(--space-16);
    }
    .dialog-form {
      display: flex;
      flex-direction: column;
      gap: var(--space-14);
    }
    .value-input {
      display: flex;
      align-items: center;
      gap: var(--space-8);
    }
    .value-input .sl-input {
      width: 8rem;
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

  readonly kindFilters = ['ALL', 'BLOCK', 'ALLOW'] as const;
  readonly langFilters = ['ALL', 'en', 'fr'] as const;
  readonly kindFilter = signal<'ALL' | Kind>('ALL');
  readonly langFilter = signal<'ALL' | Lang>('ALL');
  /** The list as filtered: blocked words first, then allowed phrases, each by language. */
  readonly shown = computed(() =>
    (this.rules() ?? []).filter(
      (r) =>
        (this.kindFilter() === 'ALL' || r.kind === this.kindFilter()) &&
        (this.langFilter() === 'ALL' || r.language === this.langFilter()),
    ),
  );
  protected readonly passIcon = LucideCircleCheck;
  protected readonly blockIcon = LucideTriangleAlert;

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

  /** A setting's value with its unit, in the portal's language ("7 days", "7 jours"). */
  unit(s: Setting, n: number): string {
    return this.transloco.translate(`admin.wording.settings.unit.${s.unit}`, { n });
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
