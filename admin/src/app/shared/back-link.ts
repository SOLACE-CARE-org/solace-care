import { ChangeDetectionStrategy, Component, input } from '@angular/core';
import { RouterLink } from '@angular/router';
import { LucideChevronLeft, LucideDynamicIcon } from '@lucide/angular';

/**
 * The way back to the list a page was opened from, as on the admin screens (ModuleDetails,
 * ModuleReview .dc.html): a chevron and the list's name, in the brand colour.
 */
@Component({
  selector: 'sl-back-link',
  imports: [RouterLink, LucideDynamicIcon],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <a class="sl-back" [routerLink]="to()">
      <svg [lucideIcon]="icon" [size]="16" [strokeWidth]="2" aria-hidden="true"></svg>
      {{ label() }}
    </a>
  `,
})
export class BackLink {
  readonly to = input.required<string>();
  readonly label = input.required<string>();
  protected readonly icon = LucideChevronLeft;
}
