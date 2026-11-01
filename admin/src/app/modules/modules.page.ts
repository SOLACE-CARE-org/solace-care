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
import { Router, RouterLink } from '@angular/router';
import { TranslocoPipe, TranslocoService } from '@jsverse/transloco';

import { ApiProblem, orThrow } from '../api/core-api';
import { TrackingApi, type TrackingSchemas } from '../api/tracking-api';
import { StaffSession } from '../auth/staff-session';
import { ACCENTS, type Accent, canDraft, stateOf, textIn } from './module-shared';

type Row = TrackingSchemas['ModuleRow'];
type Summary = NonNullable<Row['versions']>[number];

/**
 * Admin screen 21: every tracking module, what is published and what is being worked on
 * (Modules.dc.html; D51). Operations and Super admins create modules; anyone with the area can open
 * a version.
 */
@Component({
  selector: 'sl-modules',
  imports: [TranslocoPipe, FormsModule, RouterLink],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="head">
      <div>
        <h1 class="sl-title">{{ 'admin.modules.title' | transloco }}</h1>
        <a routerLink="/modules/lists" class="sl-meta">{{ 'admin.lists.title.tracking' | transloco }}</a>
        <p class="sl-muted subtitle">{{ 'admin.modules.subtitle' | transloco }}</p>
      </div>
      @if (mayDraft()) {
        <button type="button" class="sl-button sl-button--primary" (click)="openNew()">
          {{ 'admin.modules.new' | transloco }}
        </button>
      }
    </div>

    <section class="sl-card table-card" aria-live="polite">
      @if (error()) {
        <div class="state">
          <p>{{ 'common.checkConnection' | transloco }}</p>
          <button class="sl-button" type="button" (click)="load()">
            {{ 'common.tryAgain' | transloco }}
          </button>
        </div>
      } @else if (rows() === null) {
        <p class="state sl-muted">{{ 'common.loading' | transloco }}</p>
      } @else if (rows()?.length === 0) {
        <p class="state sl-muted">{{ 'admin.modules.empty' | transloco }}</p>
      } @else {
        <table class="sl-table">
          <thead>
            <tr>
              <th scope="col">{{ 'admin.modules.col.module' | transloco }}</th>
              <th scope="col">{{ 'admin.modules.col.elements' | transloco }}</th>
              <th scope="col">{{ 'admin.modules.col.rules' | transloco }}</th>
              <th scope="col">{{ 'admin.modules.col.enrolled' | transloco }}</th>
              <th scope="col">{{ 'admin.modules.col.published' | transloco }}</th>
              <th scope="col">{{ 'admin.modules.col.workingOn' | transloco }}</th>
            </tr>
          </thead>
          <tbody>
            @for (m of rows() ?? []; track m.id) {
              <tr>
                <td>
                  <span class="module">
                    <span class="tile" [attr.data-accent]="m.accentColour" aria-hidden="true"
                      ><span class="tile-mark"></span
                    ></span>
                    @if (current(m); as c) {
                      <a class="strong name" [routerLink]="['/modules/versions', c.id]">{{
                        name(m)
                      }}</a>
                    } @else {
                      <span class="strong">{{ name(m) }}</span>
                    }
                  </span>
                </td>
                <td>{{ current(m)?.elements ?? 0 }}</td>
                <td>{{ current(m)?.rules ?? 0 }}</td>
                <td>{{ m.enrolled ?? ('admin.modules.notApplicable' | transloco) }}</td>
                <td>
                  @if (published(m); as p) {
                    <a [routerLink]="['/modules/versions', p.id]">v{{ p.number }}</a>
                  } @else {
                    {{ 'admin.modules.never' | transloco }}
                  }
                </td>
                <td>
                  @if (working(m); as w) {
                    <a
                      [routerLink]="['/modules/versions', w.id]"
                      class="sl-pill"
                      [class.sl-pill--warning]="state(w) === 'IN_REVIEW'"
                      [class.sl-pill--brand]="state(w) === 'APPROVED'"
                    >
                      {{ 'admin.modules.state.' + state(w) | transloco: { n: w.number } }}
                    </a>
                  } @else if (mayDraft() && published(m)) {
                    <button
                      type="button"
                      class="sl-button"
                      [disabled]="busy()"
                      (click)="newDraft(m)"
                    >
                      {{ 'admin.modules.newDraft' | transloco }}
                    </button>
                  } @else {
                    {{ 'admin.modules.nothing' | transloco }}
                  }
                </td>
              </tr>
            }
          </tbody>
        </table>
      }
    </section>

    <dialog #dialog class="sl-dialog" aria-labelledby="new-title" (close)="creating.set(false)">
      <form (ngSubmit)="create()">
        <h2 id="new-title">{{ 'admin.modules.newTitle' | transloco }}</h2>
        <label class="sl-field">
          <span>{{ 'admin.modules.code' | transloco }}</span>
          <input
            class="sl-input"
            name="code"
            required
            maxlength="40"
            pattern="[a-z][a-z0-9_]*"
            autocomplete="off"
            [(ngModel)]="code"
          />
          <span class="sl-meta">{{ 'admin.modules.codeHint' | transloco }}</span>
        </label>
        <fieldset class="accents">
          <legend>{{ 'admin.modules.accent' | transloco }}</legend>
          @for (a of accents; track a) {
            <label class="accent">
              <input type="radio" name="accent" [value]="a" [(ngModel)]="accent" />
              <span class="swatch" [attr.data-accent]="a" aria-hidden="true"></span>
              {{ 'admin.modules.colour.' + a | transloco }}
            </label>
          }
        </fieldset>
        @if (createError()) {
          <p class="error" role="alert">{{ createError()! | transloco }}</p>
        }
        <div class="actions">
          <button type="button" class="sl-button" (click)="dialog.close()">
            {{ 'admin.common.cancel' | transloco }}
          </button>
          <button
            type="submit"
            class="sl-button sl-button--primary"
            [disabled]="busy() || !validCode()"
          >
            {{ 'admin.modules.create' | transloco }}
          </button>
        </div>
      </form>
    </dialog>
  `,
  styles: `
    .head {
      display: flex;
      justify-content: space-between;
      align-items: start;
      gap: var(--space-16);
      margin-bottom: var(--space-20);
    }
    .subtitle {
      margin: var(--space-4) 0 0;
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
    .module {
      display: inline-flex;
      align-items: center;
      gap: var(--space-12);
    }
    .strong {
      font-weight: 700;
    }
    .name {
      color: var(--ink);
      text-decoration: none;
    }
    .name:hover {
      text-decoration: underline;
    }
    /* Modules.dc.html: a tinted tile with the accent colour as a circle in the corner. */
    .tile {
      position: relative;
      flex-shrink: 0;
      width: 60px;
      height: 40px;
      overflow: hidden;
      border-radius: var(--radius-8);
      background: var(--brand-tint);
    }
    .tile-mark {
      position: absolute;
      top: -10px;
      right: -8px;
      width: 34px;
      height: 34px;
      border-radius: 50%;
      background: var(--brand);
    }
    .tile[data-accent='track-pink'] {
      background: var(--track-pink-tint);
    }
    .tile[data-accent='track-pink'] .tile-mark {
      background: var(--track-pink);
    }
    .tile[data-accent='track-gold'] {
      background: var(--warning-tint);
    }
    .tile[data-accent='track-gold'] .tile-mark {
      background: var(--track-gold);
    }
    .tile[data-accent='track-blue'] {
      background: var(--track-blue-tint);
    }
    .tile[data-accent='track-blue'] .tile-mark {
      background: var(--track-blue);
    }
    .swatch {
      display: inline-block;
      width: 1.25rem;
      height: 1.25rem;
      border-radius: var(--radius-sm, 4px);
      border: 1px solid var(--color-line, currentColor);
      background: var(--brand);
    }
    .swatch[data-accent='track-pink'] {
      background: var(--track-pink);
    }
    .swatch[data-accent='track-gold'] {
      background: var(--track-gold);
    }
    .swatch[data-accent='track-blue'] {
      background: var(--track-blue);
    }
    .accents {
      border: 0;
      padding: 0;
      margin: var(--space-16) 0;
      display: flex;
      flex-wrap: wrap;
      gap: var(--space-12);
    }
    .accent {
      display: inline-flex;
      align-items: center;
      gap: var(--space-8);
      min-height: 44px;
    }
    .actions {
      display: flex;
      justify-content: end;
      gap: var(--space-8);
      margin-top: var(--space-16);
    }
    .error {
      color: var(--danger);
    }
  `,
})
export class ModulesPage {
  private readonly api = inject(TrackingApi);
  private readonly staff = inject(StaffSession);
  private readonly router = inject(Router);
  private readonly transloco = inject(TranslocoService);

  readonly dialog = viewChild.required<ElementRef<HTMLDialogElement>>('dialog');
  readonly accents = ACCENTS;
  readonly rows = signal<Row[] | null>(null);
  readonly error = signal(false);
  readonly code = signal('');
  readonly accent = signal<Accent>('brand');
  readonly creating = signal(false);
  readonly busy = signal(false);
  readonly createError = signal<string | null>(null);
  private readonly lang = signal(this.transloco.getActiveLang());

  readonly mayDraft = computed(() => canDraft(this.staff.roles()));
  readonly validCode = computed(() => /^[a-z][a-z0-9_]*$/.test(this.code()));

  constructor() {
    this.transloco.langChanges$.subscribe((lang) => this.lang.set(lang));
    void this.load();
  }

  async load() {
    this.error.set(false);
    try {
      this.rows.set(orThrow(await this.api.client.GET('/admin/v1/tracking/modules')));
    } catch {
      this.error.set(true);
    }
  }

  name(m: Row): string {
    return textIn(m.name, this.lang()) || (m.code ?? '');
  }

  published(m: Row): Summary | undefined {
    return m.versions?.find((v) => v.status === 'PUBLISHED');
  }

  working(m: Row): Summary | undefined {
    return m.versions?.find((v) => v.status === 'DRAFT' || v.status === 'IN_REVIEW');
  }

  /** The version the counts describe: the one being worked on, else the published one. */
  current(m: Row): Summary | undefined {
    return this.working(m) ?? this.published(m);
  }

  state(v: Summary): string {
    return stateOf(v.status, v.approvedBy);
  }

  async newDraft(m: Row) {
    const moduleId = m.id;
    if (!moduleId) return;
    this.busy.set(true);
    try {
      const draft = orThrow(
        await this.api.client.POST('/admin/v1/tracking/modules/{moduleId}/versions', {
          params: { path: { moduleId } },
        }),
      );
      await this.router.navigate(['/modules/versions', draft.id]);
    } catch {
      this.error.set(true);
    } finally {
      this.busy.set(false);
    }
  }

  openNew() {
    this.code.set('');
    this.accent.set('brand');
    this.createError.set(null);
    this.creating.set(true);
    this.dialog().nativeElement.showModal();
  }

  async create() {
    if (!this.validCode()) return;
    this.busy.set(true);
    this.createError.set(null);
    try {
      const v = orThrow(
        await this.api.client.POST('/admin/v1/tracking/modules', {
          body: { code: this.code(), accentColour: this.accent() },
        }),
      );
      this.dialog().nativeElement.close();
      await this.router.navigate(['/modules/versions', v.id]);
    } catch (e) {
      this.createError.set(
        e instanceof ApiProblem && e.status === 409
          ? 'admin.modules.codeTaken'
          : 'common.checkConnection',
      );
    } finally {
      this.busy.set(false);
    }
  }
}
