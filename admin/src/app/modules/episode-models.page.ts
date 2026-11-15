import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { TranslocoPipe, TranslocoService } from '@jsverse/transloco';
import { LucideDynamicIcon, LucideRepeat } from '@lucide/angular';

import { orThrow } from '../api/core-api';
import { TrackingApi, type TrackingSchemas } from '../api/tracking-api';
import { StaffSession } from '../auth/staff-session';
import { TrackingNav } from './tracking-nav';
import { canDraft, stateOf, textIn } from './module-shared';

type Version = TrackingSchemas['EpisodeModelVersion'];

interface Row {
  code: string;
  name: string;
  reads: string;
  published?: Version;
  working?: Version;
}

/**
 * Admin screen 33: the episode models (D61; design/screens/admin/png/EpisodeModels.png). Tracking
 * setup tabs on top; the table follows the modules list (Modules.dc.html).
 */
@Component({
  selector: 'sl-episode-models',
  imports: [TranslocoPipe, RouterLink, TrackingNav, LucideDynamicIcon],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <sl-tracking-nav />
    <div class="sl-head">
      <div>
        <h1 class="sl-title">{{ 'admin.episodes.title' | transloco }}</h1>
        <p class="sl-head__intro">{{ 'admin.episodes.subtitle' | transloco }}</p>
      </div>
      @if (mayDraft()) {
        <a class="sl-button sl-button--primary" routerLink="/modules/episode-models/new">
          {{ 'admin.episodes.new' | transloco }}
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
        <div class="sl-empty">
          <span class="sl-empty__icon" aria-hidden="true">
            <svg [lucideIcon]="emptyIcon" [size]="40" [strokeWidth]="1.6"></svg>
          </span>
          <h2 class="sl-empty__title">{{ 'admin.episodes.empty' | transloco }}</h2>
          <p class="sl-empty__body">{{ 'admin.episodes.emptyBody' | transloco }}</p>
          @if (mayDraft()) {
            <a class="sl-button sl-button--primary" routerLink="/modules/episode-models/new">
              {{ 'admin.episodes.new' | transloco }}
            </a>
          }
        </div>
      } @else {
        <table class="sl-table">
          <thead>
            <tr>
              <th scope="col">{{ 'admin.episodes.col.model' | transloco }}</th>
              <th scope="col">{{ 'admin.episodes.col.reads' | transloco }}</th>
              <th scope="col">
                {{ 'admin.episodes.col.published' | transloco }}
              </th>
              <th scope="col">
                {{ 'admin.episodes.col.workingOn' | transloco }}
              </th>
            </tr>
          </thead>
          <tbody>
            @for (r of rows(); track r.code) {
              <tr>
                <td>
                  <a
                    class="name"
                    [routerLink]="['/modules/episode-models', (r.working ?? r.published)?.id]"
                    >{{ r.name }}</a
                  >
                  <span class="sl-meta code">{{ r.code }}</span>
                </td>
                <td>{{ r.reads }}</td>
                <td>
                  @if (r.published; as p) {
                    <a [routerLink]="['/modules/episode-models', p.id]">v{{ p.version }}</a>
                  } @else {
                    {{ 'admin.modules.never' | transloco }}
                  }
                </td>
                <td>
                  @if (r.working; as w) {
                    <a
                      [routerLink]="['/modules/episode-models', w.id]"
                      class="sl-pill sl-pill--warning"
                    >
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
    .table-card {
      padding: 0;
      overflow-x: auto;
    }
    .state {
      padding: var(--space-20);
      margin: 0;
    }
    .name {
      font-weight: 700;
      color: var(--ink);
      text-decoration: none;
    }
    .name:hover {
      color: var(--brand);
      text-decoration: underline;
    }
    .code {
      display: block;
    }
  `,
})
export class EpisodeModelsPage {
  protected readonly emptyIcon = LucideRepeat;
  private readonly api = inject(TrackingApi);
  private readonly staff = inject(StaffSession);
  private readonly transloco = inject(TranslocoService);

  readonly versions = signal<Version[] | null>(null);
  readonly error = signal(false);
  private readonly lang = signal(this.transloco.getActiveLang());

  readonly mayDraft = computed(() => canDraft(this.staff.roles()));

  /** One row a code: the published version, and the one being worked on. */
  readonly rows = computed<Row[]>(() => {
    const byCode = new Map<string, Version[]>();
    for (const v of this.versions() ?? []) {
      byCode.set(v.code ?? '', [...(byCode.get(v.code ?? '') ?? []), v]);
    }
    return [...byCode.entries()].map(([code, versions]) => {
      const published = versions.find((v) => v.status === 'PUBLISHED');
      const working = versions.find((v) => v.status === 'DRAFT' || v.status === 'IN_REVIEW');
      const shown = working ?? published ?? versions[0]!;
      return {
        code,
        name: textIn(shown.labels, this.lang()) || code,
        reads: [shown.startElement, shown.endElement, shown.activityElement]
          .filter((e) => !!e)
          .join(', '),
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
      this.versions.set(
        orThrow(await this.api.client.GET('/admin/v1/tracking/episode-model-versions')),
      );
    } catch {
      this.error.set(true);
    }
  }

  state(v: Version): string {
    return stateOf(v.status, v.approvedBy);
  }
}
