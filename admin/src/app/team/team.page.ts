import {
  ChangeDetectionStrategy,
  Component,
  type ElementRef,
  inject,
  signal,
  viewChild,
} from '@angular/core';
import { FormsModule } from '@angular/forms';
import { TranslocoPipe, TranslocoService } from '@jsverse/transloco';

import { ApiProblem, CoreApi, type CoreSchemas, orThrow } from '../api/core-api';
import { dateTime, dayAndTime, shortDate } from '../shared/format';

type Member = CoreSchemas['StaffMember'];
type Role = CoreSchemas['StaffRole'];
type Shift = CoreSchemas['RotaShift'];

const ROLES: Role[] = [
  'SUPER_ADMIN',
  'OPERATIONS',
  'SUPPORT',
  'FINANCE',
  'CLINICAL_REVIEWER',
  'CRISIS_RESPONDER',
  'CONTENT_EDITOR',
];

/**
 * Admin screen 19: staff accounts, their roles and the crisis on-call rota (Team.dc.html; D7, D39,
 * D42). Super admins only. A temporary password is shown once, to hand over in person.
 */
@Component({
  selector: 'sl-team',
  imports: [TranslocoPipe, FormsModule],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="head">
      <h1 class="sl-title">{{ 'admin.team.title' | transloco }}</h1>
      <button type="button" class="sl-button sl-button--primary" (click)="askAdd()">
        {{ 'admin.team.add' | transloco }}
      </button>
    </div>
    <p class="sl-muted subtitle">{{ 'admin.team.subtitle' | transloco }}</p>

    <section class="sl-card table-card" aria-live="polite">
      @if (error()) {
        <div class="state">
          <p>{{ 'common.checkConnection' | transloco }}</p>
          <button class="sl-button" type="button" (click)="load()">
            {{ 'common.tryAgain' | transloco }}
          </button>
        </div>
      } @else if (members() === null) {
        <p class="state sl-muted">{{ 'common.loading' | transloco }}</p>
      } @else {
        <table class="sl-table">
          <thead>
            <tr>
              <th scope="col">{{ 'admin.team.name' | transloco }}</th>
              <th scope="col">{{ 'admin.team.roles' | transloco }}</th>
              <th scope="col">{{ 'admin.team.secondFactor' | transloco }}</th>
              <th scope="col">{{ 'admin.team.lastActive' | transloco }}</th>
              <th scope="col">{{ 'admin.team.actions' | transloco }}</th>
            </tr>
          </thead>
          <tbody>
            @for (m of members() ?? []; track m.id) {
              <tr [class.inactive]="m.status === 'DEACTIVATED'">
                <td class="strong">{{ m.name }}</td>
                <td>
                  @for (r of m.roles; track r) {
                    <span class="sl-pill">{{ 'admin.roles.' + r | transloco }}</span>
                  }
                </td>
                <td>
                  {{ (m.secondFactor ? 'admin.team.on' : 'admin.team.notSetUp') | transloco }}
                </td>
                <td>{{ lastActive(m) }}</td>
                <td class="actions">
                  @if (m.status !== 'DEACTIVATED') {
                    <button type="button" class="sl-button" (click)="askRoles(m)">
                      {{ 'admin.team.editRoles' | transloco }}
                    </button>
                    <button type="button" class="sl-button" (click)="askPassword(m)">
                      {{ 'admin.team.newPassword' | transloco }}
                    </button>
                    <button
                      type="button"
                      class="sl-button sl-button--danger"
                      (click)="askDeactivate(m)"
                    >
                      {{ 'admin.team.deactivate' | transloco }}
                    </button>
                  } @else {
                    {{ 'admin.team.deactivated' | transloco }}
                  }
                </td>
              </tr>
            }
          </tbody>
        </table>
      }
    </section>

    <section class="sl-card rota" aria-labelledby="rota-title">
      <h2 id="rota-title" class="section">{{ 'admin.team.rota' | transloco }}</h2>
      <p class="sl-meta">{{ 'admin.team.rotaNote' | transloco }}</p>
      @if (shifts().length === 0) {
        <p class="sl-muted">{{ 'admin.crisis.noRota' | transloco }}</p>
      }
      <ul class="shifts">
        @for (s of shifts(); track $index) {
          <li>
            <span class="strong">{{ window(s) }}</span>
            <span
              >{{ s.name }} · {{ 'admin.team.shiftRole.' + s.role | transloco }} ·
              {{ s.phone }}</span
            >
            <button
              type="button"
              class="sl-button sl-button--quiet"
              [attr.aria-label]="'admin.team.removeShift' | transloco"
              (click)="removeShift($index)"
            >
              {{ 'admin.team.remove' | transloco }}
            </button>
          </li>
        }
      </ul>
      <form class="shift-form" (submit)="$event.preventDefault(); addShift()">
        <label class="sl-field">
          <span>{{ 'admin.team.responder' | transloco }}</span>
          <select class="sl-select" name="who" [(ngModel)]="shiftWho">
            @for (m of responders(); track m.id) {
              <option [value]="m.id">{{ m.name }}</option>
            }
          </select>
        </label>
        <label class="sl-field">
          <span>{{ 'admin.team.shiftRoleLabel' | transloco }}</span>
          <select class="sl-select" name="role" [(ngModel)]="shiftRole">
            <option value="PRIMARY">{{ 'admin.team.shiftRole.PRIMARY' | transloco }}</option>
            <option value="BACKUP">{{ 'admin.team.shiftRole.BACKUP' | transloco }}</option>
            <option value="ESCALATION">{{ 'admin.team.shiftRole.ESCALATION' | transloco }}</option>
          </select>
        </label>
        <label class="sl-field">
          <span>{{ 'admin.team.workPhone' | transloco }}</span>
          <input
            class="sl-input"
            name="phone"
            type="tel"
            placeholder="+237"
            [(ngModel)]="shiftPhone"
          />
        </label>
        <label class="sl-field">
          <span>{{ 'admin.team.smsLanguage' | transloco }}</span>
          <select class="sl-select" name="lang" [(ngModel)]="shiftLang">
            <option value="en">{{ 'common.english' | transloco }}</option>
            <option value="fr">{{ 'common.french' | transloco }}</option>
          </select>
        </label>
        <label class="sl-field">
          <span>{{ 'admin.team.starts' | transloco }}</span>
          <input class="sl-input" name="start" type="datetime-local" [(ngModel)]="shiftStart" />
        </label>
        <label class="sl-field">
          <span>{{ 'admin.team.ends' | transloco }}</span>
          <input class="sl-input" name="end" type="datetime-local" [(ngModel)]="shiftEnd" />
        </label>
        <button
          type="submit"
          class="sl-button"
          [disabled]="!shiftWho() || !shiftPhone() || !shiftStart() || !shiftEnd()"
        >
          {{ 'admin.team.addShift' | transloco }}
        </button>
      </form>
      <div class="dialog-actions">
        <button
          type="button"
          class="sl-button sl-button--primary"
          [disabled]="busy() || !rotaChanged()"
          (click)="saveRota()"
        >
          {{ 'admin.team.saveRota' | transloco }}
        </button>
      </div>
    </section>
    @if (problem()) {
      <p class="sl-meta problem" role="alert">{{ problem() }}</p>
    }

    <dialog #addDialog class="sl-dialog" aria-labelledby="add-title">
      <h2 id="add-title" class="section">{{ 'admin.team.add' | transloco }}</h2>
      <label class="sl-field">
        <span>{{ 'admin.team.name' | transloco }}</span>
        <input class="sl-input" maxlength="120" [(ngModel)]="name" />
      </label>
      <label class="sl-field">
        <span>{{ 'admin.team.email' | transloco }}</span>
        <input class="sl-input" type="email" maxlength="254" [(ngModel)]="email" />
      </label>
      <fieldset class="roles">
        <legend>{{ 'admin.team.roles' | transloco }}</legend>
        @for (r of roles; track r) {
          <label class="option">
            <input type="checkbox" [checked]="picked().includes(r)" (change)="toggle(r)" />
            <span>{{ 'admin.roles.' + r | transloco }}</span>
          </label>
        }
      </fieldset>
      <div class="dialog-actions">
        <button type="button" class="sl-button" (click)="addDialog.close()">
          {{ 'admin.common.cancel' | transloco }}
        </button>
        <button
          type="button"
          class="sl-button sl-button--primary"
          [disabled]="busy() || !name().trim() || !email().trim() || picked().length === 0"
          (click)="add()"
        >
          {{ 'admin.team.add' | transloco }}
        </button>
      </div>
    </dialog>

    <dialog #rolesDialog class="sl-dialog" aria-labelledby="roles-title">
      <h2 id="roles-title" class="section">
        {{ 'admin.team.rolesFor' | transloco: { name: target()?.name ?? '' } }}
      </h2>
      <p class="sl-muted">{{ 'admin.team.rolesNote' | transloco }}</p>
      <fieldset class="roles">
        <legend>{{ 'admin.team.roles' | transloco }}</legend>
        @for (r of roles; track r) {
          <label class="option">
            <input type="checkbox" [checked]="picked().includes(r)" (change)="toggle(r)" />
            <span>{{ 'admin.roles.' + r | transloco }}</span>
          </label>
        }
      </fieldset>
      <label class="sl-field">
        <span>{{ 'admin.bookings.reasonRequired' | transloco }}</span>
        <textarea class="sl-textarea" rows="2" maxlength="500" [(ngModel)]="reason"></textarea>
      </label>
      <div class="dialog-actions">
        <button type="button" class="sl-button" (click)="rolesDialog.close()">
          {{ 'admin.common.cancel' | transloco }}
        </button>
        <button
          type="button"
          class="sl-button sl-button--primary"
          [disabled]="busy() || picked().length === 0 || !reason().trim()"
          (click)="saveRoles()"
        >
          {{ 'admin.common.save' | transloco }}
        </button>
      </div>
    </dialog>

    <dialog #confirmDialog class="sl-dialog" aria-labelledby="confirm-title">
      <h2 id="confirm-title" class="section">
        {{
          (confirming() === 'DEACTIVATE'
            ? 'admin.team.confirmDeactivate'
            : 'admin.team.confirmPassword'
          ) | transloco: { name: target()?.name ?? '' }
        }}
      </h2>
      <label class="sl-field">
        <span>{{ 'admin.bookings.reasonRequired' | transloco }}</span>
        <textarea class="sl-textarea" rows="2" maxlength="1000" [(ngModel)]="reason"></textarea>
      </label>
      <div class="dialog-actions">
        <button type="button" class="sl-button" (click)="confirmDialog.close()">
          {{ 'admin.common.cancel' | transloco }}
        </button>
        <button
          type="button"
          class="sl-button sl-button--danger"
          [disabled]="busy() || !reason().trim()"
          (click)="confirm()"
        >
          {{ 'admin.common.confirm' | transloco }}
        </button>
      </div>
    </dialog>

    <dialog #passwordDialog class="sl-dialog" aria-labelledby="password-title">
      <h2 id="password-title" class="section">{{ 'admin.team.passwordTitle' | transloco }}</h2>
      <p class="sl-muted">
        {{
          'admin.team.passwordNote'
            | transloco: { name: password()?.member?.name ?? '', when: expires() }
        }}
      </p>
      <p class="password" aria-live="polite">{{ password()?.temporaryPassword }}</p>
      <div class="dialog-actions">
        <button type="button" class="sl-button sl-button--primary" (click)="donePassword()">
          {{ 'admin.team.handedOver' | transloco }}
        </button>
      </div>
    </dialog>
  `,
  styles: `
    .head {
      display: flex;
      justify-content: space-between;
      align-items: center;
    }
    .subtitle {
      margin: var(--space-4) 0 var(--space-20);
      max-width: 80ch;
    }
    .table-card {
      padding: 0;
      overflow-x: auto;
    }
    .state {
      padding: var(--space-20);
      margin: 0;
    }
    .strong {
      font-weight: 700;
    }
    .actions {
      display: flex;
      flex-wrap: wrap;
      gap: var(--space-8);
    }
    .inactive {
      color: var(--ink-muted);
    }
    .rota {
      margin-top: var(--space-16);
    }
    .section {
      margin: 0 0 var(--space-8);
      font-size: var(--type-title-md-size);
    }
    .shifts {
      list-style: none;
      margin: var(--space-12) 0;
      padding: 0;
      display: grid;
      gap: var(--space-8);
    }
    .shifts li {
      display: grid;
      grid-template-columns: 16rem 1fr auto;
      gap: var(--space-12);
      align-items: center;
    }
    .shift-form {
      display: flex;
      flex-wrap: wrap;
      gap: var(--space-12);
      align-items: end;
    }
    .roles {
      border: 0;
      padding: 0;
      margin: var(--space-12) 0;
      display: grid;
      gap: var(--space-4);
    }
    .option {
      display: flex;
      gap: var(--space-8);
      align-items: center;
      min-height: 44px;
    }
    .password {
      font-family: ui-monospace, monospace;
      font-size: var(--type-title-md-size);
      letter-spacing: 0.08em;
      user-select: all;
    }
    .dialog-actions {
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
export class TeamPage {
  private readonly api = inject(CoreApi);
  private readonly transloco = inject(TranslocoService);
  private readonly addDialog = viewChild<ElementRef<HTMLDialogElement>>('addDialog');
  private readonly rolesDialog = viewChild<ElementRef<HTMLDialogElement>>('rolesDialog');
  private readonly confirmDialog = viewChild<ElementRef<HTMLDialogElement>>('confirmDialog');
  private readonly passwordDialog = viewChild<ElementRef<HTMLDialogElement>>('passwordDialog');

  readonly roles = ROLES;
  readonly members = signal<Member[] | null>(null);
  readonly shifts = signal<Shift[]>([]);
  readonly rotaChanged = signal(false);
  readonly error = signal(false);
  readonly busy = signal(false);
  readonly problem = signal<string | null>(null);
  readonly target = signal<Member | null>(null);
  readonly confirming = signal<'DEACTIVATE' | 'PASSWORD' | null>(null);
  readonly password = signal<CoreSchemas['StaffPassword'] | null>(null);
  readonly name = signal('');
  readonly email = signal('');
  readonly picked = signal<Role[]>([]);
  readonly reason = signal('');
  readonly shiftWho = signal('');
  readonly shiftRole = signal<Shift['role']>('PRIMARY');
  readonly shiftPhone = signal('');
  readonly shiftLang = signal<'en' | 'fr'>('en');
  readonly shiftStart = signal('');
  readonly shiftEnd = signal('');
  private readonly lang = signal(this.transloco.getActiveLang());

  constructor() {
    this.transloco.langChanges$.subscribe((lang) => this.lang.set(lang));
    void this.load();
  }

  async load() {
    this.error.set(false);
    try {
      this.members.set(orThrow(await this.api.client.GET('/admin/v1/staff')));
      this.shifts.set(orThrow(await this.api.client.GET('/admin/v1/crisis/rota')));
      this.rotaChanged.set(false);
    } catch {
      this.error.set(true);
    }
  }

  /** Crisis responders and Super admins can be on call (D39). */
  responders(): Member[] {
    return (this.members() ?? []).filter(
      (m) =>
        m.status !== 'DEACTIVATED' &&
        (m.roles.includes('CRISIS_RESPONDER') || m.roles.includes('SUPER_ADMIN')),
    );
  }

  lastActive(m: Member): string {
    if (m.status === 'INVITED' || !m.lastActiveAt) {
      return this.transloco.translate('admin.team.neverSignedIn', {
        date: shortDate(m.addedAt, this.lang()),
      });
    }
    return dateTime(m.lastActiveAt, this.lang());
  }

  window(s: Shift): string {
    return `${dayAndTime(s.startsAt, this.lang())} – ${dayAndTime(s.endsAt, this.lang())}`;
  }

  expires(): string {
    return dateTime(this.password()?.expiresAt, this.lang());
  }

  toggle(r: Role) {
    this.picked.update((p) => (p.includes(r) ? p.filter((x) => x !== r) : [...p, r]));
  }

  askAdd() {
    this.name.set('');
    this.email.set('');
    this.picked.set([]);
    this.addDialog()?.nativeElement.showModal();
  }

  async add() {
    this.addDialog()?.nativeElement.close();
    await this.act(async () => {
      this.showPassword(
        orThrow(
          await this.api.client.POST('/admin/v1/staff', {
            body: { name: this.name().trim(), email: this.email().trim(), roles: this.picked() },
          }),
        ),
      );
    });
  }

  askRoles(m: Member) {
    this.target.set(m);
    this.picked.set([...m.roles]);
    this.reason.set('');
    this.rolesDialog()?.nativeElement.showModal();
  }

  async saveRoles() {
    const m = this.target();
    this.rolesDialog()?.nativeElement.close();
    if (!m) return;
    await this.act(async () => {
      orThrow(
        await this.api.client.PUT('/admin/v1/staff/{staffId}/roles', {
          params: { path: { staffId: m.id } },
          body: { roles: this.picked(), reason: this.reason().trim() },
        }),
      );
    });
  }

  askPassword(m: Member) {
    this.ask(m, 'PASSWORD');
  }

  askDeactivate(m: Member) {
    this.ask(m, 'DEACTIVATE');
  }

  private ask(m: Member, what: 'DEACTIVATE' | 'PASSWORD') {
    this.target.set(m);
    this.confirming.set(what);
    this.reason.set('');
    this.confirmDialog()?.nativeElement.showModal();
  }

  async confirm() {
    const m = this.target();
    const what = this.confirming();
    this.confirmDialog()?.nativeElement.close();
    if (!m) return;
    const params = { path: { staffId: m.id } };
    const body = { reason: this.reason().trim() };
    await this.act(async () => {
      if (what === 'DEACTIVATE') {
        orThrow(
          await this.api.client.POST('/admin/v1/staff/{staffId}/deactivation', { params, body }),
        );
      } else {
        this.showPassword(
          orThrow(
            await this.api.client.POST('/admin/v1/staff/{staffId}/temporary-password', {
              params,
              body,
            }),
          ),
        );
      }
    });
  }

  private showPassword(p: CoreSchemas['StaffPassword']) {
    this.password.set(p);
    this.passwordDialog()?.nativeElement.showModal();
  }

  /** The password leaves memory once it is handed over. */
  donePassword() {
    this.password.set(null);
    this.passwordDialog()?.nativeElement.close();
  }

  addShift() {
    const who = this.members()?.find((m) => m.id === this.shiftWho());
    if (!who) return;
    this.shifts.update((s) => [
      ...s,
      {
        staffId: who.id,
        name: who.name,
        phone: this.shiftPhone().replace(/\s+/g, ''),
        language: this.shiftLang(),
        role: this.shiftRole(),
        startsAt: new Date(this.shiftStart()).toISOString(),
        endsAt: new Date(this.shiftEnd()).toISOString(),
      },
    ]);
    this.rotaChanged.set(true);
  }

  removeShift(index: number) {
    this.shifts.update((s) => s.filter((_, i) => i !== index));
    this.rotaChanged.set(true);
  }

  async saveRota() {
    await this.act(async () => {
      this.shifts.set(
        orThrow(await this.api.client.PUT('/admin/v1/crisis/rota', { body: this.shifts() })),
      );
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
