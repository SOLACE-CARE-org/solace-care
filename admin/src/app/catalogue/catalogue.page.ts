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
import { forkJoin } from 'rxjs';

import { ApiProblem, CoreApi, type CoreSchemas, orThrow } from '../api/core-api';
import { StaffSession } from '../auth/staff-session';
import { dateTime, fcfa } from '../shared/format';

type Entry = CoreSchemas['CatalogueEntry'];
type Policy = CoreSchemas['CancellationPolicy'];
type Terms = CoreSchemas['CancellationPolicyTerms'];
type Tab = 'SPECIALTIES' | 'SESSION_TYPES' | 'PRICE_BANDS' | 'LANGUAGES' | 'TOPICS' | 'POLICY';
type Kind = 'specialties' | 'session-languages' | 'help-topics';

const TABS: Tab[] = [
  'SPECIALTIES',
  'SESSION_TYPES',
  'PRICE_BANDS',
  'LANGUAGES',
  'TOPICS',
  'POLICY',
];
const TERMS: (keyof Terms)[] = [
  'fullRefundHours',
  'partialRefundHours',
  'partialRefundPercent',
  'seekerNoShowRefundPercent',
  'specialistNoShowRefundPercent',
  'maxReschedules',
  'rescheduleMinHours',
];

/**
 * Admin screen 14: specialties, session types, price bands, session languages, help topics and the
 * cancellation policy (Catalogue.dc.html; D43). Every entry needs EN and FR; a policy version
 * applies to bookings made after it is published.
 */
@Component({
  selector: 'sl-catalogue',
  imports: [TranslocoPipe, FormsModule],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <h1 class="sl-title">{{ 'admin.catalogue.title' | transloco }}</h1>

    <div class="sl-tabs" role="tablist">
      @for (t of tabs; track t) {
        <button role="tab" type="button" [attr.aria-selected]="t === tab()" (click)="tab.set(t)">
          {{ 'admin.catalogue.tab.' + t | transloco }}
        </button>
      }
    </div>

    @if (error()) {
      <div class="sl-card">
        <p>{{ 'common.checkConnection' | transloco }}</p>
        <button class="sl-button" type="button" (click)="load()">
          {{ 'common.tryAgain' | transloco }}
        </button>
      </div>
    } @else if (catalogue(); as c) {
      @switch (tab()) {
        @case ('SESSION_TYPES') {
          <section class="sl-card">
            <p>{{ 'admin.catalogue.durationsNote' | transloco }}</p>
            <ul class="chips">
              @for (d of c.durations; track d) {
                <li class="sl-pill">{{ 'admin.catalogue.minutes' | transloco: { n: d } }}</li>
              }
            </ul>
            <p class="sl-meta">{{ 'admin.catalogue.modalities' | transloco }}</p>
          </section>
        }
        @case ('PRICE_BANDS') {
          <section class="sl-card">
            <p>
              {{
                'admin.catalogue.priceBand'
                  | transloco: { min: money(price().min), max: money(price().max) }
              }}
            </p>
            <p class="sl-meta">{{ 'admin.catalogue.priceBandNote' | transloco }}</p>
          </section>
        }
        @case ('POLICY') {
          <div class="columns">
            <section class="sl-card" aria-labelledby="draft-title">
              <h2 id="draft-title" class="section">
                {{ 'admin.catalogue.draft' | transloco: { version: nextVersion() } }}
              </h2>
              <p class="sl-meta">{{ 'admin.catalogue.draftNote' | transloco }}</p>
              @for (term of terms; track term) {
                <label class="sl-field term">
                  <span>{{ 'admin.catalogue.term.' + term | transloco }}</span>
                  <input
                    class="sl-input"
                    type="number"
                    min="0"
                    [disabled]="!canChange()"
                    [ngModel]="draft()[term]"
                    (ngModelChange)="set(term, $event)"
                  />
                </label>
              }
              <h3 class="sub">{{ 'admin.catalogue.seekersReadEn' | transloco }}</h3>
              <p lang="en">{{ seekerText('en') }}</p>
              <h3 class="sub">{{ 'admin.catalogue.seekersReadFr' | transloco }}</h3>
              <p lang="fr">{{ seekerText('fr') }}</p>
              @if (canChange()) {
                <label class="sl-field">
                  <span>{{ 'admin.bookings.reasonRequired' | transloco }}</span>
                  <textarea
                    class="sl-textarea"
                    rows="2"
                    maxlength="500"
                    [(ngModel)]="reason"
                  ></textarea>
                </label>
                <div class="actions">
                  <button type="button" class="sl-button" (click)="discard()">
                    {{ 'admin.catalogue.discard' | transloco }}
                  </button>
                  <button
                    type="button"
                    class="sl-button sl-button--primary"
                    [disabled]="busy() || !reason().trim() || !changed()"
                    (click)="publish()"
                  >
                    {{ 'admin.catalogue.publish' | transloco: { version: nextVersion() } }}
                  </button>
                </div>
              }
            </section>
            <section class="sl-card" aria-labelledby="versions-title">
              <h2 id="versions-title" class="section">
                {{ 'admin.catalogue.versions' | transloco }}
              </h2>
              <ul class="versions">
                @for (p of policies(); track p.version) {
                  <li>
                    <span class="strong">v{{ p.version }}</span>
                    @if (p.inForce) {
                      · {{ 'admin.catalogue.inForce' | transloco }}
                    }
                    <span class="sl-meta">{{ p.publishedBy }}, {{ when(p.publishedAt) }}</span>
                    @if (p.reason) {
                      <span class="sl-meta">{{ p.reason }}</span>
                    }
                  </li>
                }
              </ul>
              <p class="sl-meta">{{ 'admin.catalogue.versionsNote' | transloco }}</p>
            </section>
          </div>
        }
        @default {
          <section class="sl-card table-card">
            <table class="sl-table">
              <thead>
                <tr>
                  <th scope="col">{{ 'admin.catalogue.code' | transloco }}</th>
                  <th scope="col">{{ 'admin.catalogue.english' | transloco }}</th>
                  <th scope="col">{{ 'admin.catalogue.french' | transloco }}</th>
                  <th scope="col">{{ 'admin.catalogue.shown' | transloco }}</th>
                  <th scope="col">
                    <span class="sr-only">{{ 'admin.team.actions' | transloco }}</span>
                  </th>
                </tr>
              </thead>
              <tbody>
                @for (e of entries(); track e.code) {
                  <tr>
                    <td>{{ e.code }}</td>
                    <td lang="en">{{ e.labelEn }}</td>
                    <td lang="fr">{{ e.labelFr }}</td>
                    <td>
                      {{
                        (e.enabled ? 'admin.catalogue.yes' : 'admin.catalogue.hidden') | transloco
                      }}
                    </td>
                    <td>
                      @if (canChange()) {
                        <button type="button" class="sl-button" (click)="askEdit(e)">
                          {{ 'admin.settings.change' | transloco }}
                        </button>
                      }
                    </td>
                  </tr>
                }
              </tbody>
            </table>
            @if (canChange()) {
              <div class="state">
                <button type="button" class="sl-button" (click)="askEdit(null)">
                  {{ 'admin.catalogue.add' | transloco }}
                </button>
              </div>
            }
          </section>
        }
      }
    } @else {
      <p class="sl-card sl-muted">{{ 'common.loading' | transloco }}</p>
    }
    @if (problem()) {
      <p class="sl-meta problem" role="alert">{{ problem() }}</p>
    }

    <dialog #editDialog class="sl-dialog" aria-labelledby="edit-title">
      <h2 id="edit-title" class="section">
        {{ (editingNew() ? 'admin.catalogue.add' : 'admin.settings.change') | transloco }}
      </h2>
      <label class="sl-field">
        <span>{{ 'admin.catalogue.code' | transloco }}</span>
        <input class="sl-input" maxlength="40" [disabled]="!editingNew()" [(ngModel)]="code" />
      </label>
      <label class="sl-field">
        <span>{{ 'admin.catalogue.english' | transloco }}</span>
        <input class="sl-input" lang="en" maxlength="80" [(ngModel)]="labelEn" />
      </label>
      <label class="sl-field">
        <span>{{ 'admin.catalogue.french' | transloco }}</span>
        <input class="sl-input" lang="fr" maxlength="80" [(ngModel)]="labelFr" />
      </label>
      <label class="option">
        <input type="checkbox" [(ngModel)]="enabled" />
        <span>{{ 'admin.catalogue.showToSeekers' | transloco }}</span>
      </label>
      <label class="sl-field">
        <span>{{ 'admin.bookings.reasonRequired' | transloco }}</span>
        <textarea class="sl-textarea" rows="2" maxlength="500" [(ngModel)]="reason"></textarea>
      </label>
      <div class="actions">
        <button type="button" class="sl-button" (click)="editDialog.close()">
          {{ 'admin.common.cancel' | transloco }}
        </button>
        <button
          type="button"
          class="sl-button sl-button--primary"
          [disabled]="
            busy() || !code().trim() || !labelEn().trim() || !labelFr().trim() || !reason().trim()
          "
          (click)="saveEntry()"
        >
          {{ 'admin.common.save' | transloco }}
        </button>
      </div>
    </dialog>
  `,
  styles: `
    .sl-tabs {
      margin: var(--space-16) 0;
    }
    .table-card {
      padding: 0;
      overflow-x: auto;
    }
    .state {
      padding: var(--space-16);
    }
    .chips {
      list-style: none;
      display: flex;
      gap: var(--space-8);
      padding: 0;
    }
    .columns {
      display: grid;
      grid-template-columns: minmax(0, 3fr) minmax(0, 2fr);
      gap: var(--space-16);
      align-items: start;
    }
    .section {
      margin: 0 0 var(--space-8);
      font-size: var(--type-title-md-size);
    }
    .sub {
      margin: var(--space-16) 0 var(--space-4);
      font-size: var(--type-body-admin-size);
    }
    .term {
      max-width: 28rem;
    }
    .versions {
      list-style: none;
      padding: 0;
      display: grid;
      gap: var(--space-8);
    }
    .versions li {
      display: grid;
    }
    .strong {
      font-weight: 700;
    }
    .option {
      display: flex;
      gap: var(--space-8);
      align-items: center;
      min-height: 44px;
    }
    .actions {
      display: flex;
      justify-content: flex-end;
      gap: var(--space-8);
      margin-top: var(--space-12);
    }
    .sr-only {
      position: absolute;
      width: 1px;
      height: 1px;
      overflow: hidden;
      clip: rect(0 0 0 0);
    }
    .problem {
      color: var(--danger);
    }
  `,
})
export class CataloguePage {
  private readonly api = inject(CoreApi);
  private readonly transloco = inject(TranslocoService);
  private readonly staff = inject(StaffSession);
  private readonly editDialog = viewChild<ElementRef<HTMLDialogElement>>('editDialog');

  readonly tabs = TABS;
  readonly terms = TERMS;
  readonly tab = signal<Tab>('SPECIALTIES');
  readonly catalogue = signal<CoreSchemas['AdminCatalogue'] | null>(null);
  readonly policies = signal<Policy[]>([]);
  readonly settings = signal<CoreSchemas['PlatformSetting'][]>([]);
  readonly draft = signal<Terms>({} as Terms);
  readonly error = signal(false);
  readonly busy = signal(false);
  readonly problem = signal<string | null>(null);
  readonly editingNew = signal(false);
  readonly code = signal('');
  readonly labelEn = signal('');
  readonly labelFr = signal('');
  readonly enabled = signal(true);
  readonly reason = signal('');
  readonly canChange = computed(() => this.staff.accessTo('catalogue') === 'F');
  private readonly bothLanguages = signal(false);
  private readonly lang = signal(this.transloco.getActiveLang());

  readonly entries = computed<Entry[]>(() => {
    const c = this.catalogue();
    if (!c) return [];
    return this.tab() === 'LANGUAGES'
      ? c.sessionLanguages
      : this.tab() === 'TOPICS'
        ? c.helpTopics
        : c.specialties;
  });

  readonly nextVersion = computed(() => (this.policies()[0]?.version ?? 0) + 1);

  readonly changed = computed(() => {
    const current = this.policies()[0];
    return !!current && TERMS.some((t) => Number(this.draft()[t]) !== current[t]);
  });

  constructor() {
    this.transloco.langChanges$.subscribe((lang) => this.lang.set(lang));
    // The preview shows both languages, whichever the portal is in.
    forkJoin([this.transloco.load('en'), this.transloco.load('fr')]).subscribe(() =>
      this.bothLanguages.set(true),
    );
    void this.load();
  }

  async load() {
    this.error.set(false);
    try {
      this.catalogue.set(orThrow(await this.api.client.GET('/admin/v1/catalogue')));
      this.policies.set(orThrow(await this.api.client.GET('/admin/v1/cancellation-policies')));
      this.discard();
    } catch {
      this.error.set(true);
      return;
    }
    try {
      // Price bands are platform settings; staff without Settings access see the defaults.
      this.settings.set(orThrow(await this.api.client.GET('/admin/v1/settings')).settings);
    } catch {
      this.settings.set([]);
    }
  }

  price(): { min: number; max: number } {
    const value = (key: string, fallback: number) =>
      Number(this.settings().find((s) => s.key === key)?.value ?? fallback);
    return {
      min: value('solace.specialists.pricing.min-price', 5000),
      max: value('solace.specialists.pricing.max-price', 20000),
    };
  }

  set(term: keyof Terms, value: number) {
    this.draft.update((d) => ({ ...d, [term]: Number(value) }));
  }

  discard() {
    const current = this.policies()[0];
    if (!current) return;
    const terms = {} as Terms;
    for (const t of TERMS) terms[t] = current[t];
    this.draft.set(terms);
    this.reason.set('');
  }

  /** The policy in the words seekers read, in each language (Catalogue.dc.html). */
  seekerText(lang: 'en' | 'fr'): string {
    if (!this.bothLanguages()) return '';
    const d = this.draft();
    return this.transloco.translate(
      'admin.catalogue.seekerText',
      { hours: d.fullRefundHours, percent: d.partialRefundPercent },
      lang,
    );
  }

  async publish() {
    await this.act(async () => {
      orThrow(
        await this.api.client.POST('/admin/v1/cancellation-policies', {
          body: { ...this.draft(), reason: this.reason().trim() },
        }),
      );
    });
  }

  private kind(): Kind {
    return this.tab() === 'LANGUAGES'
      ? 'session-languages'
      : this.tab() === 'TOPICS'
        ? 'help-topics'
        : 'specialties';
  }

  askEdit(e: Entry | null) {
    this.editingNew.set(e === null);
    this.code.set(e?.code ?? '');
    this.labelEn.set(e?.labelEn ?? '');
    this.labelFr.set(e?.labelFr ?? '');
    this.enabled.set(e?.enabled ?? false);
    this.reason.set('');
    this.editDialog()?.nativeElement.showModal();
  }

  async saveEntry() {
    this.editDialog()?.nativeElement.close();
    await this.act(async () => {
      orThrow(
        await this.api.client.PUT('/admin/v1/catalogue/{kind}/{code}', {
          params: { path: { kind: this.kind(), code: this.code().trim().toLowerCase() } },
          body: {
            labelEn: this.labelEn().trim(),
            labelFr: this.labelFr().trim(),
            enabled: this.enabled(),
            reason: this.reason().trim(),
          },
        }),
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

  when(iso: string): string {
    return dateTime(iso, this.lang());
  }

  money(n: number): string {
    return fcfa(n, this.lang());
  }
}
