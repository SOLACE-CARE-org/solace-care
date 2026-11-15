import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';
import { LucideCircle, LucideCircleCheck, LucideDynamicIcon } from '@lucide/angular';
import { TranslocoPipe } from '@jsverse/transloco';

/**
 * "Before it can be submitted" (D61): every requirement of a version, ticked when met, so the
 * drafter sees what is done as well as what is left. The server's problem codes are the ones not met.
 */
@Component({
  selector: 'sl-requirements',
  imports: [TranslocoPipe, LucideDynamicIcon],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <section class="sl-card" aria-labelledby="requirements-title">
      <h2 id="requirements-title" class="sl-section">
        {{ 'admin.requirements.title' | transloco }}
      </h2>
      @if (problems() === null) {
        <p class="sl-hint">{{ 'admin.requirements.afterSave' | transloco }}</p>
      } @else {
        <p class="sl-hint count" role="status">
          @if (left() === 0) {
            {{ 'admin.requirements.ready' | transloco }}
          } @else {
            {{ 'admin.requirements.left' | transloco: { n: left(), total: codes().length } }}
          }
        </p>
        <ul class="sl-checklist">
          @for (c of codes(); track c) {
            <li [attr.data-met]="!problems()!.includes(c)">
              <svg
                [lucideIcon]="problems()!.includes(c) ? open : met"
                [size]="20"
                [strokeWidth]="1.8"
                aria-hidden="true"
              ></svg>
              <span>
                <span class="sl-sr-only">{{
                  (problems()!.includes(c) ? 'admin.requirements.notMet' : 'admin.requirements.met')
                    | transloco
                }}</span>
                {{ prefix() + c | transloco }}
              </span>
            </li>
          }
        </ul>
      }
    </section>
  `,
  styles: `
    .count {
      margin: calc(var(--space-8) * -1) 0 var(--space-12);
    }
  `,
})
export class Requirements {
  /** Every requirement code, in the order to show them. */
  readonly codes = input.required<readonly string[]>();
  /** The codes not met yet; null before the first save, when the server has not checked. */
  readonly problems = input<readonly string[] | null>(null);
  /** Translation key prefix, e.g. 'admin.elements.problems.'. */
  readonly prefix = input.required<string>();
  protected readonly left = computed(
    () => this.codes().filter((c) => this.problems()?.includes(c)).length,
  );
  protected readonly met = LucideCircleCheck;
  protected readonly open = LucideCircle;
}
