import { ChangeDetectionStrategy, Component, inject } from '@angular/core';
import { RouterOutlet } from '@angular/router';
import Keycloak from 'keycloak-js';

import { Sidebar } from './sidebar';

/** Signed-in layout: the sidebar beside the current page. */
@Component({
  selector: 'sl-shell',
  imports: [RouterOutlet, Sidebar],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <sl-sidebar (signOut)="signOut()" />
    <main class="page"><router-outlet /></main>
  `,
  styles: `
    :host {
      display: flex;
      min-height: 100vh;
      background: var(--surface-page);
    }
    .page {
      flex: 1;
      min-width: 0;
      padding: var(--space-28) var(--space-32);
    }
  `,
})
export class Shell {
  private readonly keycloak = inject(Keycloak);

  signOut() {
    this.keycloak.logout({ redirectUri: window.location.origin });
  }
}
