import { ChangeDetectionStrategy, Component } from '@angular/core';
import { RouterLink, RouterLinkActive } from '@angular/router';
import { TranslocoPipe } from '@jsverse/transloco';

/** The five lists of tracking setup (D61), in the order work flows: modules use the rest. */
export const TRACKING_PAGES = [
  { path: '/modules', key: 'modules', exact: true },
  { path: '/modules/elements', key: 'elements', exact: false },
  { path: '/modules/episode-models', key: 'episodes', exact: false },
  { path: '/modules/context-questions', key: 'questions', exact: false },
  { path: '/modules/wording', key: 'wording', exact: false },
] as const;

/**
 * Tracking setup's own navigation (admin screens 21 and 32 to 35): a Segmented row of links at the
 * top of each list, so the element library, episode models, context questions and the word list
 * sit beside the modules that use them.
 */
@Component({
  selector: 'sl-tracking-nav',
  imports: [RouterLink, RouterLinkActive, TranslocoPipe],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <nav class="sl-tabs tracking-nav" [attr.aria-label]="'admin.trackingNav.label' | transloco">
      @for (p of pages; track p.path) {
        <a
          [routerLink]="p.path"
          routerLinkActive="active"
          ariaCurrentWhenActive="page"
          [routerLinkActiveOptions]="{ exact: p.exact }"
        >
          {{ 'admin.trackingNav.' + p.key | transloco }}
        </a>
      }
    </nav>
  `,
  styles: `
    :host {
      display: block;
      margin-bottom: var(--space-20);
    }
    .tracking-nav {
      flex-wrap: wrap;
    }
    a {
      display: inline-flex;
      align-items: center;
      min-height: var(--size-tap);
      padding: 0 var(--space-14);
      border-radius: var(--radius-10);
      color: var(--ink);
      font-weight: 600;
      text-decoration: none;
    }
    a.active {
      background: var(--brand);
      color: var(--on-brand);
    }
  `,
})
export class TrackingNav {
  protected readonly pages = TRACKING_PAGES;
}
