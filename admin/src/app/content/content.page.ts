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

import { ApiProblem, CoreApi, type CoreSchemas, orThrow } from '../api/core-api';
import { StaffSession } from '../auth/staff-session';
import { shortDate } from '../shared/format';

type Row = CoreSchemas['ContentRow'];
type ContentType = CoreSchemas['ContentType'];

const TYPES: ContentType[] = ['CRISIS_RESOURCE', 'ARTICLE', 'FAQ', 'LEGAL'];

/**
 * Admin screen 15: articles, FAQs, crisis resources and legal text (Content.dc.html; D44). Nothing
 * publishes until both English and French are done.
 */
@Component({
  selector: 'sl-content',
  imports: [TranslocoPipe, FormsModule, RouterLink],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="head">
      <h1 class="sl-title">{{ 'admin.content.title' | transloco }}</h1>
      @if (canChange()) {
        <button type="button" class="sl-button sl-button--primary" (click)="askNew()">
          {{ 'admin.content.new' | transloco }}
        </button>
      }
    </div>
    <p class="sl-muted subtitle">{{ 'admin.content.subtitle' | transloco }}</p>

    <div class="sl-tabs" role="tablist">
      <button role="tab" type="button" [attr.aria-selected]="type() === null" (click)="pick(null)">
        {{ 'admin.common.all' | transloco }}
      </button>
      @for (t of types; track t) {
        <button role="tab" type="button" [attr.aria-selected]="type() === t" (click)="pick(t)">
          {{ 'admin.content.types.' + t | transloco }}
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
        <p class="state sl-muted">{{ 'admin.common.noResults' | transloco }}</p>
      } @else {
        <table class="sl-table">
          <thead>
            <tr>
              <th scope="col">{{ 'admin.content.itemTitle' | transloco }}</th>
              <th scope="col">{{ 'admin.content.type' | transloco }}</th>
              <th scope="col">{{ 'admin.content.english' | transloco }}</th>
              <th scope="col">{{ 'admin.content.french' | transloco }}</th>
              <th scope="col">{{ 'admin.content.clinicalCheck' | transloco }}</th>
              <th scope="col">{{ 'admin.content.status' | transloco }}</th>
              <th scope="col">{{ 'admin.content.updated' | transloco }}</th>
            </tr>
          </thead>
          <tbody>
            @for (r of rows() ?? []; track r.id) {
              <tr>
                <td class="strong">
                  <a [routerLink]="['/content', r.id]">{{ r.title }}</a>
                </td>
                <td>{{ 'admin.content.types.' + r.type | transloco }}</td>
                <td>
                  {{ (r.enDone ? 'admin.content.done' : 'admin.content.missing') | transloco }}
                </td>
                <td>
                  {{ (r.frDone ? 'admin.content.done' : 'admin.content.missing') | transloco }}
                </td>
                <td>{{ 'admin.content.clinical.' + r.clinical | transloco }}</td>
                <td>
                  <span class="sl-pill" [class.sl-pill--warning]="r.status === 'EDITED'">{{
                    'admin.content.state.' + r.status | transloco: { version: r.version }
                  }}</span>
                </td>
                <td>{{ day(r.updatedAt) }}</td>
              </tr>
            }
          </tbody>
        </table>
      }
    </section>
    <p class="sl-meta note">{{ 'admin.content.note' | transloco }}</p>

    <dialog #newDialog class="sl-dialog" aria-labelledby="new-title">
      <h2 id="new-title" class="section">{{ 'admin.content.new' | transloco }}</h2>
      <label class="sl-field">
        <span>{{ 'admin.content.type' | transloco }}</span>
        <select class="sl-select" [(ngModel)]="newType">
          @for (t of types; track t) {
            <option [value]="t">{{ 'admin.content.types.' + t | transloco }}</option>
          }
        </select>
      </label>
      <label class="sl-field">
        <span>{{ 'admin.content.slug' | transloco }}</span>
        <input class="sl-input" maxlength="60" [(ngModel)]="slug" />
      </label>
      @if (newType() === 'CRISIS_RESOURCE') {
        <label class="sl-field">
          <span>{{ 'admin.content.country' | transloco }}</span>
          <input class="sl-input" maxlength="2" [(ngModel)]="country" />
        </label>
      }
      @if (problem()) {
        <p class="sl-meta problem" role="alert">{{ problem() }}</p>
      }
      <div class="actions">
        <button type="button" class="sl-button" (click)="newDialog.close()">
          {{ 'admin.common.cancel' | transloco }}
        </button>
        <button
          type="button"
          class="sl-button sl-button--primary"
          [disabled]="!validSlug()"
          (click)="create()"
        >
          {{ 'admin.content.create' | transloco }}
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
      margin: var(--space-4) 0 var(--space-16);
    }
    .sl-tabs {
      margin-bottom: var(--space-16);
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
    .note {
      margin-top: var(--space-12);
      max-width: 80ch;
    }
    .section {
      margin: 0 0 var(--space-8);
      font-size: var(--type-title-md-size);
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
export class ContentPage {
  private readonly api = inject(CoreApi);
  private readonly transloco = inject(TranslocoService);
  private readonly staff = inject(StaffSession);
  private readonly router = inject(Router);
  private readonly newDialog = viewChild<ElementRef<HTMLDialogElement>>('newDialog');

  readonly types = TYPES;
  readonly type = signal<ContentType | null>(null);
  readonly rows = signal<Row[] | null>(null);
  readonly error = signal(false);
  readonly problem = signal<string | null>(null);
  readonly newType = signal<ContentType>('ARTICLE');
  readonly slug = signal('');
  readonly country = signal('CM');
  readonly canChange = computed(() => this.staff.accessTo('content') === 'F');
  readonly validSlug = computed(() => /^[a-z][a-z0-9-]*$/.test(this.slug().trim()));
  private readonly lang = signal(this.transloco.getActiveLang());

  constructor() {
    this.transloco.langChanges$.subscribe((lang) => {
      this.lang.set(lang);
      void this.load();
    });
    void this.load();
  }

  pick(t: ContentType | null) {
    this.type.set(t);
    void this.load();
  }

  async load() {
    this.error.set(false);
    try {
      const type = this.type();
      this.rows.set(
        orThrow(
          await this.api.client.GET('/admin/v1/content', {
            params: { query: type ? { type } : {} },
          }),
        ),
      );
    } catch {
      this.error.set(true);
    }
  }

  askNew() {
    this.slug.set('');
    this.problem.set(null);
    this.newDialog()?.nativeElement.showModal();
  }

  async create() {
    this.problem.set(null);
    try {
      const body: { type: ContentType; slug: string; country?: string } = {
        type: this.newType(),
        slug: this.slug().trim(),
      };
      if (this.newType() === 'CRISIS_RESOURCE') body.country = this.country().trim().toUpperCase();
      const item = orThrow(await this.api.client.POST('/admin/v1/content', { body }));
      this.newDialog()?.nativeElement.close();
      await this.router.navigate(['/content', item.id]);
    } catch (e) {
      this.problem.set(
        e instanceof ApiProblem && e.title
          ? e.title
          : this.transloco.translate('common.checkConnection'),
      );
    }
  }

  day(iso: string): string {
    return shortDate(iso, this.lang());
  }
}
