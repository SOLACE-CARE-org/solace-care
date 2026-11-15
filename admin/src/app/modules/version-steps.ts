import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';
import { LucideCheck, LucideDynamicIcon } from '@lucide/angular';
import { TranslocoPipe } from '@jsverse/transloco';

const STEPS = ['DRAFT', 'REVIEW', 'PUBLISHED'] as const;

/** Where a version stands on its way to the phone: draft, then clinical review, then published. */
export function stepOf(status: string | undefined): number {
  if (status === 'IN_REVIEW') return 1;
  if (status === 'PUBLISHED' || status === 'RETIRED') return 2;
  return 0;
}

/**
 * The life of one version (D61), drawn as the module builder's step pills (ModuleElements.dc.html)
 * but not clickable: a version moves by its buttons, not by these.
 */
@Component({
  selector: 'sl-version-steps',
  imports: [TranslocoPipe, LucideDynamicIcon],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <ol class="sl-steps" [attr.aria-label]="'admin.versionSteps.label' | transloco">
      @for (s of steps; track s; let i = $index) {
        <li
          [attr.data-state]="i < current() ? 'done' : i === current() ? 'current' : 'next'"
          [attr.aria-current]="i === current() ? 'step' : null"
        >
          @if (i < current()) {
            <svg [lucideIcon]="check" [size]="16" [strokeWidth]="2" aria-hidden="true"></svg>
            <span class="sl-sr-only">{{ 'admin.versionSteps.done' | transloco }}</span>
          } @else {
            <span aria-hidden="true">{{ i + 1 }}</span>
          }
          {{ 'admin.versionSteps.' + s | transloco }}
        </li>
      }
    </ol>
  `,
})
export class VersionSteps {
  readonly status = input<string | undefined>(undefined);
  protected readonly steps = STEPS;
  protected readonly current = computed(() => stepOf(this.status()));
  protected readonly check = LucideCheck;
}
