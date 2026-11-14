import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { TranslocoPipe, TranslocoService } from '@jsverse/transloco';

import { ApiProblem, orThrow } from '../api/core-api';
import { TrackingApi, type TrackingSchemas } from '../api/tracking-api';
import { StaffSession } from '../auth/staff-session';
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
 * The diagnostic word list and Tracking settings (D61). No design yet, so layout and copy are
 * provisional and follow the modules list. Clinical reviewers keep the word list; Operations and
 * Super admins change settings with a reason.
 */
@Component({
  selector: 'sl-wording',
  imports: [TranslocoPipe, FormsModule, RouterLink],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <nav class="crumbs sl-meta" [attr.aria-label]="'admin.wording.title' | transloco">
      <a routerLink="/modules">{{ 'admin.modules.title' | transloco }}</a>
    </nav>
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
      <p class="sl-muted">{{ 'common.loading' | transloco }}</p>
    } @else {
      <section class="sl-card" aria-labelledby="settings-title">
        <h2 id="settings-title" class="sl-subtitle">
          {{ 'admin.wording.settings.title' | transloco }}
        </h2>
        <p class="sl-meta">{{ 'admin.wording.settings.help' | transloco }}</p>
        <table class="sl-table">
          <thead>
            <tr>
              <th scope="col">{{ 'admin.wording.settings.col.setting' | transloco }}</th>
              <th scope="col">{{ 'admin.wording.settings.col.value' | transloco }}</th>
              <th scope="col">{{ 'admin.wording.settings.col.default' | transloco }}</th>
              <th scope="col">{{ 'admin.wording.settings.col.changed' | transloco }}</th>
              <th scope="col">
                <span class="sr-only">{{ 'admin.wording.settings.save' | transloco }}</span>
              </th>
            </tr>
          </thead>
          <tbody>
            @for (s of settings() ?? []; track s.key) {
              <tr>
                <td>{{ 'admin.wording.settings.name.' + s.key | transloco }}</td>
                <td class="strong">
                  {{ 'admin.wording.settings.unit.' + s.unit | transloco: { n: s.value } }}
                </td>
                <td>
                  {{ 'admin.wording.settings.unit.' + s.unit | transloco: { n: s.defaultValue } }}
                </td>
                <td>
                  @if (s.updatedBy) {
                    {{
                      'admin.wording.settings.changedBy'
                        | transloco: { who: s.updatedBy, when: date(s.updatedAt) }
                    }}
                  } @else {
                    {{ 'admin.wording.settings.never' | transloco }}
                  }
                </td>
                <td>
                  @if (mayChangeSettings() && editing() !== s.key) {
                    <button
                      type="button"
                      class="sl-button"
                      [attr.aria-label]="
                        'admin.wording.settings.edit'
                          | transloco
                            : { name: ('admin.wording.settings.name.' + s.key | transloco) }
                      "
                      (click)="edit(s)"
                    >
                      {{ 'admin.wording.settings.change' | transloco }}
                    </button>
                  }
                </td>
              </tr>
              @if (editing() === s.key) {
                <tr>
                  <td colspan="5">
                    <form class="edit" (ngSubmit)="saveSetting(s)">
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
                      <label class="sl-field reason">
                        <span>{{ 'admin.wording.settings.reason' | transloco }}</span>
                        <input
                          class="sl-input"
                          name="reason"
                          maxlength="500"
                          [(ngModel)]="reason"
                        />
                      </label>
                      <button type="button" class="sl-button" (click)="editing.set(null)">
                        {{ 'admin.wording.settings.cancel' | transloco }}
                      </button>
                      <button
                        type="submit"
                        class="sl-button sl-button--primary"
                        [disabled]="
                          busy() ||
                          !reason.trim() ||
                          value === null ||
                          value < s.min ||
                          value > s.max
                        "
                      >
                        {{ 'admin.wording.settings.save' | transloco }}
                      </button>
                    </form>
                  </td>
                </tr>
              }
            }
          </tbody>
        </table>
      </section>

      <section class="sl-card" aria-labelledby="words-title">
        <h2 id="words-title" class="sl-subtitle">{{ 'admin.wording.words.title' | transloco }}</h2>
        <p class="sl-meta">{{ 'admin.wording.words.help' | transloco }}</p>

        <form class="try" (ngSubmit)="check()">
          <label class="sl-field grow">
            <span>{{ 'admin.wording.words.tryText' | transloco }}</span>
            <input class="sl-input" name="tryText" maxlength="1000" [(ngModel)]="tryText" />
          </label>
          <label class="sl-field">
            <span>{{ 'admin.wording.words.col.language' | transloco }}</span>
            <select class="sl-input" name="tryLang" [(ngModel)]="tryLang">
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
          <p role="status" [class.error]="!r.allowed">
            @if (r.allowed) {
              {{ 'admin.wording.words.passes' | transloco }}
            } @else {
              {{ 'admin.wording.words.blocked' | transloco: { patterns: r.blockedBy.join(', ') } }}
            }
          </p>
        }

        @if (mayEditWords()) {
          <form class="add" (ngSubmit)="add()">
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
              <select class="sl-input" name="language" [(ngModel)]="language">
                @for (l of langs; track l) {
                  <option [value]="l">{{ 'admin.wording.words.language.' + l | transloco }}</option>
                }
              </select>
            </label>
            <label class="sl-field">
              <span>{{ 'admin.wording.words.col.kind' | transloco }}</span>
              <select class="sl-input" name="kind" [(ngModel)]="kind">
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
          <p class="error" role="alert">{{ problem()! | transloco }}</p>
        }

        <table class="sl-table">
          <thead>
            <tr>
              <th scope="col">{{ 'admin.wording.words.col.pattern' | transloco }}</th>
              <th scope="col">{{ 'admin.wording.words.col.language' | transloco }}</th>
              <th scope="col">{{ 'admin.wording.words.col.kind' | transloco }}</th>
              <th scope="col">{{ 'admin.wording.words.col.addedBy' | transloco }}</th>
              <th scope="col">
                <span class="sr-only">{{ 'admin.wording.words.add' | transloco }}</span>
              </th>
            </tr>
          </thead>
          <tbody>
            @for (r of rules() ?? []; track r.id) {
              <tr>
                <td class="strong">{{ r.pattern }}</td>
                <td>{{ 'admin.wording.words.language.' + r.language | transloco }}</td>
                <td>{{ 'admin.wording.words.kind.' + r.kind | transloco }}</td>
                <td>{{ r.createdBy || ('admin.wording.words.launch' | transloco) }}</td>
                <td>
                  @if (mayEditWords()) {
                    <button
                      type="button"
                      class="sl-button"
                      [disabled]="busy()"
                      [attr.aria-label]="
                        'admin.wording.words.remove' | transloco: { pattern: r.pattern }
                      "
                      (click)="remove(r)"
                    >
                      ×
                    </button>
                  }
                </td>
              </tr>
            }
          </tbody>
        </table>
      </section>
    }
  `,
  styles: `
    .crumbs {
      margin-bottom: var(--space-8);
    }
    .subtitle {
      margin: var(--space-4) 0 var(--space-20);
      max-width: 80ch;
    }
    section {
      margin-bottom: var(--space-20);
      overflow-x: auto;
    }
    .strong {
      font-weight: 700;
    }
    .try,
    .add,
    .edit {
      display: flex;
      flex-wrap: wrap;
      align-items: end;
      gap: var(--space-8);
      margin: var(--space-12) 0;
    }
    .grow,
    .reason {
      flex: 1 1 16rem;
    }
    .error {
      color: var(--danger);
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
    this.editing.set(s.key);
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
      this.editing.set(null);
    } catch {
      this.problem.set('common.checkConnection');
    } finally {
      this.busy.set(false);
    }
  }
}
