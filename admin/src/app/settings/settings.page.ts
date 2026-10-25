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
import { dateTime, fcfa } from '../shared/format';

type Setting = CoreSchemas['PlatformSetting'];
type Flag = CoreSchemas['FeatureFlag'];
type Version = CoreSchemas['SettingVersion'];

const GROUPS = [
  'MONEY',
  'SESSIONS',
  'REVIEWS',
  'NOTIFICATIONS',
  'PRIVACY',
  'SAFETY',
  'ACCESS',
  'INSIGHT',
  'TRACKING',
  'AI',
];

/** Transloco reads dots as nesting; setting keys have dots. */
export const labelKey = (key: string) => key.replaceAll('.', '_');

/**
 * Admin screen 20: platform settings and feature flags (Settings.dc.html; D42). Every change needs
 * a reason and creates a new version; fixed decisions show as Fixed. Super admins change,
 * Operations can look.
 */
@Component({
  selector: 'sl-settings',
  imports: [TranslocoPipe, FormsModule, RouterLink],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <h1 class="sl-title">{{ 'admin.settings.title' | transloco }}</h1>
    <p class="related">
      <a routerLink="/settings/jobs" class="sl-meta">{{ 'admin.jobs.title' | transloco }}</a>
      <a routerLink="/settings/senders" class="sl-meta">{{ 'admin.senders.title' | transloco }}</a>
    </p>
    <p class="sl-muted subtitle">{{ 'admin.settings.subtitle' | transloco }}</p>

    @if (error()) {
      <div class="sl-card">
        <p>{{ 'common.checkConnection' | transloco }}</p>
        <button class="sl-button" type="button" (click)="load()">
          {{ 'common.tryAgain' | transloco }}
        </button>
      </div>
    } @else if (data(); as d) {
      <section class="sl-card" aria-labelledby="platform-title">
        <h2 id="platform-title" class="section">{{ 'admin.settings.platform' | transloco }}</h2>
        @for (group of groups; track group) {
          @if (inGroup(d.settings, group).length) {
            <h3 class="group">{{ 'admin.settings.group.' + group | transloco }}</h3>
            <ul class="rows">
              @for (s of inGroup(d.settings, group); track s.key) {
                <li class="row">
                  <div>
                    <p class="label">{{ 'admin.settings.label.' + label(s.key) | transloco }}</p>
                    @if (s.version > 0) {
                      <p class="sl-meta">
                        {{
                          'admin.settings.changed'
                            | transloco
                              : { version: s.version, name: s.updatedBy, when: when(s.updatedAt) }
                        }}
                      </p>
                    }
                  </div>
                  <span class="value">{{ show(s, s.value) }}</span>
                  @if (!s.editable) {
                    <span class="sl-pill">{{ 'admin.settings.fixed' | transloco }}</span>
                  } @else if (canChange()) {
                    <button type="button" class="sl-button" (click)="ask(s)">
                      {{ 'admin.settings.change' | transloco }}
                    </button>
                  } @else {
                    <span></span>
                  }
                </li>
              }
            </ul>
          }
        }
      </section>

      <section class="sl-card" aria-labelledby="flags-title">
        <h2 id="flags-title" class="section">{{ 'admin.settings.flags' | transloco }}</h2>
        <ul class="rows">
          @for (f of d.flags; track f.key) {
            <li class="row">
              <div>
                <p class="label">{{ 'admin.settings.flag.' + f.key | transloco }}</p>
                <p class="sl-meta">
                  @if (f.locked) {
                    {{
                      (f.enabled ? 'admin.settings.lockedOn' : 'admin.settings.lockedOff')
                        | transloco
                    }}
                  } @else {
                    {{ 'admin.settings.phase' | transloco: { phase: f.phase } }}
                  }
                </p>
              </div>
              <span class="value">{{
                (f.enabled ? 'admin.settings.on' : 'admin.settings.off') | transloco
              }}</span>
              @if (canChange() && !f.locked) {
                <button
                  type="button"
                  class="sl-button"
                  role="switch"
                  [attr.aria-checked]="f.enabled"
                  [attr.aria-label]="'admin.settings.flag.' + f.key | transloco"
                  (click)="askFlag(f)"
                >
                  {{ (f.enabled ? 'admin.settings.turnOff' : 'admin.settings.turnOn') | transloco }}
                </button>
              } @else {
                <span></span>
              }
            </li>
          }
        </ul>
      </section>
    } @else {
      <p class="sl-card sl-muted">{{ 'common.loading' | transloco }}</p>
    }

    <dialog #changeDialog class="sl-dialog" aria-labelledby="change-title">
      @if (editing(); as s) {
        <h2 id="change-title" class="section">
          {{ 'admin.settings.label.' + label(s.key) | transloco }}
        </h2>
        <p class="sl-muted">
          {{ 'admin.settings.defaultIs' | transloco: { value: show(s, s.defaultValue) } }}
        </p>
        <label class="sl-field">
          <span>{{ 'admin.settings.unit.' + s.type | transloco }}</span>
          <input
            class="sl-input"
            [type]="s.type === 'TIME' ? 'time' : isText(s) ? 'text' : 'number'"
            [attr.min]="isText(s) ? null : (s.min ?? null)"
            [attr.max]="isText(s) ? null : (s.max ?? null)"
            [(ngModel)]="value"
          />
        </label>
        @if (s.allowed?.length) {
          <p class="sl-meta">
            {{ 'admin.settings.allowed' | transloco: { codes: s.allowed!.join(', ') } }}
          </p>
        }
        <label class="sl-field">
          <span>{{ 'admin.bookings.reasonRequired' | transloco }}</span>
          <textarea class="sl-textarea" rows="3" maxlength="500" [(ngModel)]="reason"></textarea>
        </label>
        @for (v of history(); track v.version) {
          <p class="sl-meta">
            v{{ v.version }} · {{ show(s, v.value) }} · {{ v.changedBy }} ·
            {{ when(v.changedAt) }} ·
            {{ v.reason }}
          </p>
        }
      }
      @if (problem()) {
        <p class="sl-meta problem" role="alert">{{ problem() }}</p>
      }
      <div class="actions">
        <button type="button" class="sl-button" (click)="close()">
          {{ 'admin.common.cancel' | transloco }}
        </button>
        <button
          type="button"
          class="sl-button sl-button--primary"
          [disabled]="busy() || !reason().trim() || !String(value()).trim()"
          (click)="save()"
        >
          {{ 'admin.common.save' | transloco }}
        </button>
      </div>
    </dialog>

    <dialog #flagDialog class="sl-dialog" aria-labelledby="flag-title">
      @if (flagging(); as f) {
        <h2 id="flag-title" class="section">
          {{
            (f.enabled ? 'admin.settings.confirmOff' : 'admin.settings.confirmOn')
              | transloco: { name: ('admin.settings.flag.' + f.key | transloco) }
          }}
        </h2>
        <label class="sl-field">
          <span>{{ 'admin.bookings.reasonRequired' | transloco }}</span>
          <textarea class="sl-textarea" rows="3" maxlength="500" [(ngModel)]="reason"></textarea>
        </label>
      }
      @if (problem()) {
        <p class="sl-meta problem" role="alert">{{ problem() }}</p>
      }
      <div class="actions">
        <button type="button" class="sl-button" (click)="closeFlag()">
          {{ 'admin.common.cancel' | transloco }}
        </button>
        <button
          type="button"
          class="sl-button sl-button--primary"
          [disabled]="busy() || !reason().trim()"
          (click)="saveFlag()"
        >
          {{ 'admin.common.confirm' | transloco }}
        </button>
      </div>
    </dialog>
  `,
  styles: `
    .subtitle {
      margin: var(--space-4) 0 var(--space-20);
      max-width: 72ch;
    }
    .related {
      display: flex;
      gap: var(--space-16);
      margin: var(--space-4) 0 0;
    }
    .related a {
      min-height: 44px;
      display: inline-flex;
      align-items: center;
    }
    .sl-card + .sl-card {
      margin-top: var(--space-16);
    }
    .section {
      margin: 0 0 var(--space-12);
      font-size: var(--type-title-md-size);
    }
    .group {
      margin: var(--space-16) 0 var(--space-8);
      font-size: var(--type-body-admin-size);
    }
    .rows {
      list-style: none;
      margin: 0;
      padding: 0;
    }
    .row {
      display: grid;
      grid-template-columns: minmax(0, 1fr) 10rem 8rem;
      gap: var(--space-12);
      align-items: center;
      min-height: 44px;
      padding: var(--space-8) 0;
      border-top: 1px solid var(--line);
    }
    .row p {
      margin: 0;
    }
    .label,
    .value {
      font-weight: 600;
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
export class SettingsPage {
  private readonly api = inject(CoreApi);
  private readonly transloco = inject(TranslocoService);
  private readonly staff = inject(StaffSession);
  private readonly changeDialog = viewChild<ElementRef<HTMLDialogElement>>('changeDialog');
  private readonly flagDialog = viewChild<ElementRef<HTMLDialogElement>>('flagDialog');

  readonly String = String;
  readonly groups = GROUPS;
  readonly label = labelKey;
  readonly data = signal<CoreSchemas['PlatformSettings'] | null>(null);
  readonly error = signal(false);
  readonly busy = signal(false);
  readonly problem = signal<string | null>(null);
  readonly editing = signal<Setting | null>(null);
  readonly flagging = signal<Flag | null>(null);
  readonly history = signal<Version[]>([]);
  readonly value = signal<string>('');
  readonly reason = signal('');
  readonly canChange = computed(() => this.staff.accessTo('settings') === 'F');
  private readonly lang = signal(this.transloco.getActiveLang());

  constructor() {
    this.transloco.langChanges$.subscribe((lang) => this.lang.set(lang));
    void this.load();
  }

  async load() {
    this.error.set(false);
    try {
      this.data.set(orThrow(await this.api.client.GET('/admin/v1/settings')));
    } catch {
      this.error.set(true);
    }
  }

  inGroup(settings: Setting[], group: string): Setting[] {
    return settings.filter((s) => s.group === group);
  }

  /** Lists, a phone number and codes are typed as text: "30,50,80", "+237…" (D61). */
  isText(s: Setting): boolean {
    return ['MINUTES_LIST', 'HOURS_LIST', 'PHONE', 'CODES'].includes(s.type);
  }

  /** "20 %", "15 min", "5 000 XAF", "21:00", "30, 50, 80 min". */
  show(s: Setting, value: string): string {
    if (s.type === 'FCFA') return fcfa(Number(value), this.lang());
    if (s.type === 'TIME') return value;
    if ((s.type === 'PHONE' || s.type === 'CODES') && !value) {
      return this.transloco.translate('admin.settings.none');
    }
    if (this.isText(s)) {
      value = value.split(',').join(', ');
    }
    return this.transloco.translate(`admin.settings.value.${s.type}`, { value });
  }

  when(iso: string | undefined): string {
    return dateTime(iso, this.lang());
  }

  async ask(s: Setting) {
    this.editing.set(s);
    this.value.set(s.value);
    this.reason.set('');
    this.problem.set(null);
    this.history.set([]);
    this.changeDialog()?.nativeElement.showModal();
    try {
      this.history.set(
        orThrow(
          await this.api.client.GET('/admin/v1/settings/{key}/history', {
            params: { path: { key: s.key } },
          }),
        ),
      );
    } catch {
      // The history is a convenience; the change still works without it.
    }
  }

  close() {
    this.changeDialog()?.nativeElement.close();
  }

  async save() {
    const s = this.editing();
    if (!s) return;
    await this.act(async () => {
      orThrow(
        await this.api.client.PUT('/admin/v1/settings/{key}', {
          params: { path: { key: s.key } },
          body: { value: String(this.value()).trim(), reason: this.reason().trim() },
        }),
      );
      this.close();
    });
  }

  askFlag(f: Flag) {
    this.flagging.set(f);
    this.reason.set('');
    this.problem.set(null);
    this.flagDialog()?.nativeElement.showModal();
  }

  closeFlag() {
    this.flagDialog()?.nativeElement.close();
  }

  async saveFlag() {
    const f = this.flagging();
    if (!f) return;
    await this.act(async () => {
      orThrow(
        await this.api.client.PUT('/admin/v1/flags/{key}', {
          params: { path: { key: f.key } },
          body: { enabled: !f.enabled, reason: this.reason().trim() },
        }),
      );
      this.closeFlag();
    });
  }

  private async act(change: () => Promise<void>) {
    this.busy.set(true);
    this.problem.set(null);
    try {
      await change();
      await this.load();
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
