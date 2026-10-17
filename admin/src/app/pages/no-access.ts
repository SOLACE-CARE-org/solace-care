import { ChangeDetectionStrategy, Component } from '@angular/core';
import { RouterLink } from '@angular/router';
import { TranslocoPipe } from '@jsverse/transloco';

/**
 * No access (screens/admin/source/StateAdminNoAccess.dc.html), shown for a direct link to an area
 * the signed-in roles cannot open. The per-area explanation arrives with each area's screen.
 */
@Component({
  selector: 'sl-no-access',
  imports: [RouterLink, TranslocoPipe],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <h1 class="type-title-lg">{{ 'admin.noAccess.title' | transloco }}</h1>
    <a routerLink="/overview" class="back">{{ 'admin.noAccess.back' | transloco }}</a>
  `,
  styles: `
    :host {
      display: flex;
      flex-direction: column;
      align-items: flex-start;
      gap: var(--space-16);
    }
    h1 {
      margin: 0;
      color: var(--ink);
    }
    .back {
      display: inline-flex;
      align-items: center;
      min-height: var(--size-control-admin);
      padding: 0 var(--space-16);
      border-radius: var(--radius-12);
      border: 1px solid var(--line-control);
      background: var(--surface-card);
      color: var(--ink);
      font-size: var(--type-label-size);
      font-weight: var(--type-label-weight);
      text-decoration: none;
    }
    .back:focus-visible {
      outline: none;
      box-shadow: var(--focus-ring);
    }
  `,
})
export class NoAccess {}
