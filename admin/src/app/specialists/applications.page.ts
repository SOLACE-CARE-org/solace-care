import {
  ChangeDetectionStrategy,
  Component,
  computed,
  effect,
  inject,
  signal,
} from '@angular/core';
import { Router, RouterLink } from '@angular/router';
import { TranslocoPipe, TranslocoService } from '@jsverse/transloco';

import { ApiProblem, CoreApi, type CoreSchemas, orThrow } from '../api/core-api';
import { StaffSession } from '../auth/staff-session';
import { daysSince, languageName, shortDate } from '../shared/format';

type Tab = 'SUBMITTED' | 'UNDER_REVIEW' | 'CHANGES_REQUESTED' | 'DECIDED';
type Queue = CoreSchemas['ApplicationQueue'];
type Item = Queue['items'][number];

const TABS: Tab[] = ['SUBMITTED', 'UNDER_REVIEW', 'CHANGES_REQUESTED', 'DECIDED'];

/** Admin screen 4: specialist applications waiting for review (Applications.dc.html). */
@Component({
  selector: 'sl-applications',
  imports: [TranslocoPipe, RouterLink],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <h1 class="sl-title">
      {{ 'admin.specialists.applications.title' | transloco }}
    </h1>
    <p class="sl-muted subtitle">
      {{ 'admin.specialists.applications.subtitle' | transloco }}
      <a routerLink="/practice-requests">{{
        'admin.specialists.applications.practiceRequests' | transloco
      }}</a>
    </p>

    <div class="filters">
      <div class="sl-tabs" role="tablist">
        @for (t of tabs; track t) {
          <button role="tab" type="button" [attr.aria-selected]="t === tab()" (click)="tab.set(t)">
            {{ 'admin.specialists.applications.tab_' + t | transloco: { count: count(t) } }}
          </button>
        }
      </div>
      <label class="filter">
        <span class="sl-muted">{{ 'admin.specialists.applications.specialty' | transloco }}</span>
        <select class="sl-select" [value]="profession()" (change)="profession.set(value($event))">
          <option value="">
            {{ 'admin.specialists.applications.all' | transloco }}
          </option>
          @for (p of professions(); track p.code) {
            <option [value]="p.code">{{ p.label }}</option>
          }
        </select>
      </label>
      <label class="filter">
        <span class="sl-muted">{{ 'admin.specialists.applications.language' | transloco }}</span>
        <select class="sl-select" [value]="language()" (change)="language.set(value($event))">
          <option value="">
            {{ 'admin.specialists.applications.all' | transloco }}
          </option>
          @for (code of languages(); track code) {
            <option [value]="code">{{ name(code) }}</option>
          }
        </select>
      </label>
    </div>

    <section class="sl-card table-card" aria-live="polite" [attr.aria-busy]="loading()">
      @if (error()) {
        <p class="state">{{ 'common.checkConnection' | transloco }}</p>
        <button class="sl-button" type="button" (click)="load()">
          {{ 'common.tryAgain' | transloco }}
        </button>
      } @else if (loading() && !queue()) {
        <p class="state sl-muted">{{ 'common.loading' | transloco }}</p>
      } @else if (queue()?.items?.length === 0) {
        <p class="state sl-muted">
          {{ 'admin.specialists.applications.empty' | transloco }}
        </p>
      } @else {
        <table class="sl-table">
          <thead>
            <tr>
              <th scope="col">
                {{ 'admin.specialists.applications.applicant' | transloco }}
              </th>
              <th scope="col">
                {{ 'admin.specialists.applications.specialty' | transloco }}
              </th>
              <th scope="col">
                {{ 'admin.specialists.applications.languages' | transloco }}
              </th>
              <th scope="col">
                {{ 'admin.specialists.applications.submitted' | transloco }}
              </th>
              <th scope="col">
                {{ 'admin.specialists.applications.waiting' | transloco }}
              </th>
              <th scope="col">
                {{ 'admin.specialists.applications.assignedTo' | transloco }}
              </th>
              <th scope="col">
                <span class="visually-hidden">{{
                  'admin.specialists.applications.action' | transloco
                }}</span>
              </th>
            </tr>
          </thead>
          <tbody>
            @for (item of queue()?.items ?? []; track item.id) {
              <tr>
                <td class="strong">{{ item.fullName }}</td>
                <td>{{ item.profession }}</td>
                <td>{{ declared(item) }}</td>
                <td>{{ date(item.submittedAt) }}</td>
                <td>
                  @if (item.submittedAt) {
                    <span class="sl-pill" [class.sl-pill--warning]="waiting(item) >= 7">
                      {{ waitingLabel(item) }}
                    </span>
                  }
                </td>
                <td>
                  {{
                    item.assignedTo?.name ??
                      ('admin.specialists.applications.unassigned' | transloco)
                  }}
                </td>
                <td class="action">
                  <button
                    type="button"
                    class="sl-button"
                    [class.sl-button--primary]="item.assignedTo !== undefined"
                    [attr.aria-label]="
                      ('admin.specialists.applications.review' | transloco) + ': ' + item.fullName
                    "
                    (click)="review(item)"
                  >
                    {{ 'admin.specialists.applications.review' | transloco }}
                  </button>
                </td>
              </tr>
            }
          </tbody>
        </table>
      }
    </section>
    <p class="sl-meta note">
      {{ 'admin.specialists.applications.note' | transloco }}
    </p>
  `,
  styles: `
    .subtitle {
      margin: var(--space-4) 0 var(--space-20);
    }
    .filters {
      display: flex;
      flex-wrap: wrap;
      align-items: center;
      gap: var(--space-12);
      margin-bottom: var(--space-20);
    }
    .filter {
      display: inline-flex;
      align-items: center;
      gap: var(--space-8);
    }
    .table-card {
      padding: 0;
      overflow-x: auto;
    }
    .state {
      padding: var(--space-18);
      margin: 0;
    }
    .strong {
      font-weight: 700;
    }
    .action {
      text-align: right;
    }
    .note {
      margin-top: var(--space-16);
    }
    .visually-hidden {
      position: absolute;
      width: 1px;
      height: 1px;
      overflow: hidden;
      clip: rect(0 0 0 0);
    }
  `,
})
export class ApplicationsPage {
  private readonly api = inject(CoreApi);
  private readonly router = inject(Router);
  private readonly transloco = inject(TranslocoService);
  private readonly staff = inject(StaffSession);

  readonly tabs = TABS;
  readonly tab = signal<Tab>('SUBMITTED');
  readonly profession = signal('');
  readonly language = signal('');
  readonly queue = signal<Queue | null>(null);
  readonly loading = signal(false);
  readonly error = signal(false);
  readonly professions = signal<CoreSchemas['Profession'][]>([]);
  readonly languages = signal<string[]>([]);
  private readonly lang = signal(this.transloco.getActiveLang());
  readonly canChange = computed(() => this.staff.accessTo('specialists') === 'F');

  constructor() {
    this.transloco.langChanges$.subscribe((lang) => this.lang.set(lang));
    effect(() => {
      // Reload whenever the tab, a filter or the portal language changes.
      this.tab();
      this.profession();
      this.language();
      this.lang();
      void this.load();
    });
    void this.loadFilters();
  }

  async load() {
    this.loading.set(true);
    this.error.set(false);
    try {
      this.queue.set(
        orThrow(
          await this.api.client.GET('/admin/v1/specialist-applications', {
            params: {
              query: {
                tab: this.tab(),
                profession: this.profession() || undefined,
                language: this.language() || undefined,
              },
            },
          }),
        ),
      );
    } catch {
      this.error.set(true);
    } finally {
      this.loading.set(false);
    }
  }

  private async loadFilters() {
    try {
      this.professions.set(orThrow(await this.api.client.GET('/v1/catalogue/professions')));
      this.languages.set(
        orThrow(await this.api.client.GET('/v1/catalogue/session-languages')).map((l) => l.code),
      );
    } catch {
      // The filters stay at "All"; the list itself still works.
    }
  }

  count(tab: Tab): number {
    const counts = this.queue()?.counts;
    if (!counts) return 0;
    if (tab === 'SUBMITTED') return counts.submitted;
    if (tab === 'UNDER_REVIEW') return counts.underReview;
    if (tab === 'CHANGES_REQUESTED') return counts.changesRequested;
    return 0;
  }

  declared(item: Item): string {
    const lang = this.lang();
    return item.languages
      .map((l, i) => {
        const name = languageName(l.code, lang);
        const level = this.transloco.translate(`admin.specialists.levels.${l.proficiency}`);
        return `${i === 0 ? name.charAt(0).toUpperCase() + name.slice(1) : name} ${level}`;
      })
      .join(' · ');
  }

  waiting(item: Item): number {
    return daysSince(item.submittedAt);
  }

  waitingLabel(item: Item): string {
    const days = this.waiting(item);
    return days === 1
      ? this.transloco.translate('admin.specialists.applications.dayOne')
      : this.transloco.translate('admin.specialists.applications.days', {
          count: days,
        });
  }

  date(iso: string | undefined): string {
    return shortDate(iso, this.lang());
  }

  name(code: string): string {
    const name = languageName(code, this.lang());
    return name.charAt(0).toUpperCase() + name.slice(1);
  }

  value(event: Event): string {
    return (event.target as HTMLSelectElement).value;
  }

  /** Opening a submitted application takes it for review; viewers only look. */
  async review(item: Item) {
    if (item.status === 'SUBMITTED' && this.canChange()) {
      try {
        orThrow(
          await this.api.client.POST('/admin/v1/specialist-applications/{id}/review', {
            params: { path: { id: item.id } },
          }),
        );
      } catch (e) {
        if (!(e instanceof ApiProblem)) throw e;
      }
    }
    await this.router.navigate(['/applications', item.id]);
  }
}
