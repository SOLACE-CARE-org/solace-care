import { ChangeDetectionStrategy, Component } from '@angular/core';
import { TranslocoPipe } from '@jsverse/transloco';

/**
 * Development stand-in for admin screens later epics build (admin-portal-spec.md, Screens). Every
 * route using it is replaced before the pilot.
 */
@Component({
  selector: 'sl-not-built',
  imports: [TranslocoPipe],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `<p class="type-body-admin">{{ 'dev.notBuilt' | transloco }}</p>`,
  styles: `
    p {
      color: var(--ink-muted);
    }
  `,
})
export class NotBuilt {}
