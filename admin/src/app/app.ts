import { ChangeDetectionStrategy, Component, inject } from '@angular/core';
import { RouterOutlet } from '@angular/router';
import { TranslocoService } from '@jsverse/transloco';

/** Root: keeps the document language in step with the admin language, for screen readers. */
@Component({
  selector: 'sl-root',
  imports: [RouterOutlet],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `<router-outlet />`,
})
export class App {
  private readonly transloco = inject(TranslocoService);

  constructor() {
    this.transloco.langChanges$.subscribe((language) => {
      document.documentElement.lang = language;
    });
  }
}
