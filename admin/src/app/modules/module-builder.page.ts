import {
  ChangeDetectionStrategy,
  Component,
  computed,
  effect,
  inject,
  input,
  signal,
  untracked,
} from '@angular/core';
import { Router, RouterLink } from '@angular/router';
import { TranslocoPipe, TranslocoService } from '@jsverse/transloco';

import { CoreApi, orThrow } from '../api/core-api';
import { TrackingApi, type TrackingSchemas } from '../api/tracking-api';
import { StaffSession } from '../auth/staff-session';
import { DetailsStep } from './details-step';
import { ElementsStep } from './elements-step';
import { canDraft, canReview, stateOf, textIn } from './module-shared';
import { RulesStep } from './rules-step';

type Version = TrackingSchemas['ModuleVersion'];
type Element = TrackingSchemas['Element'];
type Step = 'details' | 'elements' | 'rules';

export interface Specialty {
  code: string;
  labelEn: string;
  labelFr: string;
}

const STEPS: Step[] = ['details', 'elements', 'rules'];

/**
 * Admin screens 22 to 24: the module builder, in three steps (ModuleDetails, ModuleElements and
 * ModuleRules .dc.html; D51). Only a draft can change; anything else opens read-only.
 */
@Component({
  selector: 'sl-module-builder',
  imports: [TranslocoPipe, RouterLink, DetailsStep, ElementsStep, RulesStep],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <nav class="crumbs sl-meta" [attr.aria-label]="'admin.modules.builder' | transloco">
      <a routerLink="/modules">{{ 'admin.modules.title' | transloco }}</a>
    </nav>

    @if (error()) {
      <div class="sl-card">
        <p>{{ 'common.checkConnection' | transloco }}</p>
        <button class="sl-button" type="button" (click)="load()">
          {{ 'common.tryAgain' | transloco }}
        </button>
      </div>
    } @else if (version(); as v) {
      <div class="head">
        <h1 class="sl-title">{{ name() }}</h1>
        <span class="sl-pill sl-pill--warning">
          {{ 'admin.modules.state.' + state() | transloco: { n: v.number } }}
        </span>
        @if (reviewable()) {
          <a
            class="sl-button sl-button--primary review"
            [routerLink]="['/modules/versions', v.id, 'review']"
          >
            {{ 'admin.modules.review.title' | transloco }}
          </a>
        }
      </div>

      <ol class="steps" role="tablist">
        @for (s of steps; track s; let i = $index) {
          <li>
            <button
              type="button"
              role="tab"
              [attr.aria-selected]="s === step()"
              [attr.aria-current]="s === step() ? 'step' : null"
              (click)="go(s)"
            >
              {{ i + 1 }} {{ 'admin.modules.step.' + s | transloco }}
            </button>
          </li>
        }
      </ol>

      @if (returned(); as r) {
        <p class="sl-card notice" role="status">
          {{ 'admin.modules.returned' | transloco: { who: r.reviewer, comments: r.comments } }}
        </p>
      }
      @if (!editable()) {
        <div class="sl-card notice">
          <p>{{ 'admin.modules.readOnly' | transloco }}</p>
          @if (mayDraft() && v.status === 'PUBLISHED') {
            <button type="button" class="sl-button" [disabled]="busy()" (click)="newDraft()">
              {{ 'admin.modules.newDraft' | transloco }}
            </button>
          }
        </div>
      }

      @switch (step()) {
        @case ('details') {
          <sl-details-step
            [version]="v"
            [editable]="editable()"
            (saved)="replace($event)"
            (next)="go('elements')"
          />
        }
        @case ('elements') {
          <sl-elements-step
            [version]="v"
            [library]="library()"
            [editable]="editable()"
            (saved)="replace($event)"
            (back)="go('details')"
            (next)="go('rules')"
          />
        }
        @case ('rules') {
          <sl-rules-step
            [version]="v"
            [library]="library()"
            [specialties]="specialties()"
            [editable]="editable()"
            (changed)="load()"
            (submitted)="replace($event)"
            (back)="go('elements')"
          />
        }
      }
    } @else {
      <p class="sl-muted">{{ 'common.loading' | transloco }}</p>
    }
  `,
  styles: `
    .crumbs {
      margin-bottom: var(--space-8);
    }
    .head {
      display: flex;
      align-items: center;
      flex-wrap: wrap;
      gap: var(--space-12);
    }
    .review {
      margin-left: auto;
    }
    .steps {
      list-style: none;
      display: flex;
      gap: var(--space-8);
      padding: 0;
      margin: var(--space-16) 0 var(--space-20);
    }
    .steps button {
      min-height: 44px;
      padding: 0 var(--space-16);
      border: 1px solid var(--color-line, #dde3dc);
      border-radius: 999px;
      background: transparent;
      font: inherit;
      cursor: pointer;
    }
    .steps button[aria-selected='true'] {
      background: var(--brand);
      border-color: var(--brand);
      color: var(--white, #fff);
      font-weight: 600;
    }
    .notice {
      margin: 0 0 var(--space-16);
    }
  `,
})
export class ModuleBuilderPage {
  private readonly tracking = inject(TrackingApi);
  private readonly core = inject(CoreApi);
  private readonly staff = inject(StaffSession);
  private readonly router = inject(Router);
  private readonly transloco = inject(TranslocoService);

  readonly id = input.required<string>();
  readonly steps = STEPS;
  readonly step = signal<Step>('details');
  readonly version = signal<Version | null>(null);
  readonly library = signal<Element[]>([]);
  readonly specialties = signal<Specialty[]>([]);
  readonly error = signal(false);
  readonly busy = signal(false);
  private readonly lang = signal(this.transloco.getActiveLang());

  readonly mayDraft = computed(() => canDraft(this.staff.roles()));
  readonly editable = computed(() => this.mayDraft() && this.version()?.status === 'DRAFT');
  readonly state = computed(() => stateOf(this.version()?.status, this.version()?.approvedBy));
  readonly name = computed(() => {
    const v = this.version();
    return textIn(v?.name, this.lang()) || v?.moduleCode || '';
  });
  readonly reviewable = computed(() => {
    const v = this.version();
    if (v?.status !== 'IN_REVIEW') return false;
    return canReview(this.staff.roles()) || (this.mayDraft() && !!v.approvedBy);
  });
  readonly returned = computed(() => {
    const v = this.version();
    const last = v?.reviews?.at(-1);
    return v?.status === 'DRAFT' && last?.decision === 'RETURNED' ? last : null;
  });

  constructor() {
    this.transloco.langChanges$.subscribe((lang) => this.lang.set(lang));
    effect(() => {
      this.id();
      untracked(() => void this.load());
    });
    void this.loadLibrary();
  }

  async load() {
    this.error.set(false);
    try {
      this.version.set(
        orThrow(
          await this.tracking.client.GET('/admin/v1/tracking/versions/{versionId}', {
            params: { path: { versionId: this.id() } },
          }),
        ),
      );
    } catch {
      this.error.set(true);
    }
  }

  private async loadLibrary() {
    try {
      this.library.set(orThrow(await this.tracking.client.GET('/admin/v1/tracking/elements')));
    } catch {
      this.error.set(true);
    }
    // Specialty names come from Core's catalogue; without access to it the codes are shown.
    try {
      const catalogue = orThrow(await this.core.client.GET('/admin/v1/catalogue'));
      this.specialties.set(catalogue.specialties.filter((s) => s.enabled !== false));
    } catch {
      this.specialties.set([]);
    }
  }

  replace(v: Version) {
    this.version.set(v);
  }

  go(step: Step) {
    this.step.set(step);
  }

  async newDraft() {
    const moduleId = this.version()?.moduleId;
    if (!moduleId) return;
    this.busy.set(true);
    try {
      const draft = orThrow(
        await this.tracking.client.POST('/admin/v1/tracking/modules/{moduleId}/versions', {
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
}
