import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { TranslocoPipe, TranslocoService } from '@jsverse/transloco';

import { orThrow } from '../api/core-api';
import { TrackingApi, type TrackingSchemas } from '../api/tracking-api';
import { StaffSession } from '../auth/staff-session';
import { BackLink } from '../shared/back-link';
import { canDraft, stateOf } from './module-shared';

type Version = TrackingSchemas['ElementVersion'];

interface Row {
  code: string;
  name: string;
  view: string;
  published?: Version;
  working?: Version;
}

/**
 * Admin screen 32: the element library (D61). No design of its own yet, so copy is provisional;
 * the layout is the modules list's (Modules.dc.html). Every version of every element: what is published
 * and what is being worked on.
 */
@Component({
  selector: 'sl-elements',
  imports: [TranslocoPipe, RouterLink, BackLink],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <sl-back-link to="/modules" [label]="'admin.modules.title' | transloco" />
    <div class="head">
      <div>
        <h1 class="sl-title">{{ 'admin.elements.title' | transloco }}</h1>
        <p class="sl-muted subtitle">{{ 'admin.elements.subtitle' | transloco }}</p>
      </div>
      @if (mayDraft()) {
        <a class="sl-button sl-button--primary" routerLink="/modules/elements/new">
          {{ 'admin.elements.new' | transloco }}
        </a>
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
      } @else if (versions() === null) {
        <p class="state sl-muted">{{ 'common.loading' | transloco }}</p>
      } @else if (rows().length === 0) {
        <p class="state sl-muted">{{ 'admin.elements.empty' | transloco }}</p>
      } @else {
        <table class="sl-table">
          <thead>
            <tr>
              <th scope="col">{{ 'admin.elements.col.element' | transloco }}</th>
              <th scope="col">{{ 'admin.elements.col.view' | transloco }}</th>
              <th scope="col">{{ 'admin.elements.col.published' | transloco }}</th>
              <th scope="col">{{ 'admin.elements.col.workingOn' | transloco }}</th>
            </tr>
          </thead>
          <tbody>
            @for (r of rows(); track r.code) {
              <tr>
                <td>
                  <span class="strong">{{ r.name }}</span>
                  <span class="sl-meta code">{{ r.code }}</span>
                </td>
                <td>{{ 'admin.elements.views.' + r.view | transloco }}</td>
                <td>
                  @if (r.published; as p) {
                    <a [routerLink]="['/modules/elements', p.id]">v{{ p.version }}</a>
                  } @else {
                    {{ 'admin.modules.never' | transloco }}
                  }
                </td>
                <td>
                  @if (r.working; as w) {
                    <a [routerLink]="['/modules/elements', w.id]" class="sl-pill sl-pill--warning">
                      {{ 'admin.modules.state.' + state(w) | transloco: { n: w.version } }}
                    </a>
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
    .strong {
      font-weight: 700;
    }
    .code {
      display: block;
    }
  `,
})
export class ElementsPage {
  private readonly api = inject(TrackingApi);
  private readonly staff = inject(StaffSession);
  private readonly transloco = inject(TranslocoService);

  readonly versions = signal<Version[] | null>(null);
  readonly error = signal(false);
  private readonly lang = signal(this.transloco.getActiveLang());

  readonly mayDraft = computed(() => canDraft(this.staff.roles()));

  /** One row a code: the newest published version, and the one being worked on. */
  readonly rows = computed<Row[]>(() => {
    const byCode = new Map<string, Version[]>();
    for (const v of this.versions() ?? []) {
      byCode.set(v.code ?? '', [...(byCode.get(v.code ?? '') ?? []), v]);
    }
    return [...byCode.entries()].map(([code, versions]) => {
      const published = versions.find((v) => v.status === 'PUBLISHED');
      const working = versions.find((v) => v.status === 'DRAFT' || v.status === 'IN_REVIEW');
      const shown = working ?? published ?? versions[0]!;
      const labels = this.lang() === 'fr' ? shown.labels?.fr : shown.labels?.en;
      return {
        code,
        name: labels?.name || code,
        view: shown.inputView ?? '',
        published,
        working,
      };
    });
  });

  constructor() {
    this.transloco.langChanges$.subscribe((lang) => this.lang.set(lang));
    void this.load();
  }

  async load() {
    this.error.set(false);
    try {
      this.versions.set(orThrow(await this.api.client.GET('/admin/v1/tracking/element-versions')));
    } catch {
      this.error.set(true);
    }
  }

  state(v: Version): string {
    return stateOf(v.status, v.approvedBy);
  }
}
