import {
  ChangeDetectionStrategy,
  Component,
  computed,
  effect,
  inject,
  input,
  output,
  signal,
  untracked,
} from '@angular/core';
import { TranslocoPipe, TranslocoService } from '@jsverse/transloco';

import { orThrow } from '../api/core-api';
import { TrackingApi, type TrackingSchemas } from '../api/tracking-api';

type Version = TrackingSchemas['ModuleVersion'];
type Element = TrackingSchemas['Element'];
type Binding = TrackingSchemas['Binding'];

/** More than this many elements is a warning, not a stop (D51). */
const ELEMENT_WARNING = 6;

/**
 * Builder step 2 (admin screen 23): elements from the engineer-seeded library, in order, each
 * required or not. Reordering uses buttons, so it works without a mouse.
 */
@Component({
  selector: 'sl-elements-step',
  imports: [TranslocoPipe],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="layout">
      <section class="sl-card" aria-labelledby="library-title">
        <h2 id="library-title" class="section">
          {{ 'admin.modules.elements.library' | transloco }}
        </h2>
        <p class="sl-meta">{{ 'admin.modules.elements.libraryNote' | transloco }}</p>
        <ul class="list">
          @for (e of library(); track e.code + e.version) {
            <li class="row">
              <div>
                <p class="name">{{ label(e) }}</p>
                <p class="sl-meta">
                  {{ e.code }} v{{ e.version }} · {{ e.inputView }}
                  @if (e.valueType === 'TEXT') {
                    · {{ 'admin.modules.elements.neverRead' | transloco }}
                  }
                </p>
              </div>
              @if (has(e)) {
                <span class="sl-pill sl-pill--brand">{{
                  'admin.modules.elements.added' | transloco
                }}</span>
              } @else if (editable()) {
                <button type="button" class="sl-button" (click)="add(e)">
                  {{ 'admin.modules.elements.add' | transloco }}
                </button>
              }
            </li>
          }
        </ul>
      </section>

      <section class="sl-card" aria-labelledby="chosen-title">
        <h2 id="chosen-title" class="section">
          {{ 'admin.modules.elements.inModule' | transloco: { n: chosen().length } }}
        </h2>
        <ol class="list">
          @for (b of chosen(); track b.code; let i = $index; let last = $last) {
            <li class="row">
              <p class="name">{{ i + 1 }} · {{ nameOf(b.code) }}</p>
              <label class="required">
                <input
                  type="checkbox"
                  [checked]="b.required"
                  [disabled]="!editable()"
                  (change)="toggle(i)"
                />
                {{ 'admin.modules.elements.required' | transloco }}
              </label>
              @if (editable()) {
                <span class="moves">
                  <button
                    type="button"
                    class="sl-button sl-button--quiet"
                    [disabled]="i === 0"
                    [attr.aria-label]="
                      'admin.modules.elements.moveUp' | transloco: { name: nameOf(b.code) }
                    "
                    (click)="move(i, -1)"
                  >
                    ↑
                  </button>
                  <button
                    type="button"
                    class="sl-button sl-button--quiet"
                    [disabled]="last"
                    [attr.aria-label]="
                      'admin.modules.elements.moveDown' | transloco: { name: nameOf(b.code) }
                    "
                    (click)="move(i, 1)"
                  >
                    ↓
                  </button>
                  <button
                    type="button"
                    class="sl-button sl-button--quiet"
                    [attr.aria-label]="
                      'admin.modules.elements.remove' | transloco: { name: nameOf(b.code) }
                    "
                    (click)="remove(i)"
                  >
                    ×
                  </button>
                </span>
              }
            </li>
          }
        </ol>
        @if (tooMany()) {
          <p class="warning" role="status">{{ 'admin.modules.elements.tooMany' | transloco }}</p>
        }
        <p class="sl-meta">{{ 'admin.modules.elements.note' | transloco }}</p>
      </section>
    </div>

    @if (failed()) {
      <p class="error" role="alert">{{ 'common.checkConnection' | transloco }}</p>
    }
    <div class="actions">
      <button type="button" class="sl-button" (click)="back.emit()">
        {{ 'admin.modules.elements.back' | transloco }}
      </button>
      <button
        type="button"
        class="sl-button sl-button--primary"
        [disabled]="busy() || (editable() && chosen().length === 0)"
        (click)="saveAndNext()"
      >
        {{ 'admin.modules.elements.next' | transloco }}
      </button>
    </div>
  `,
  styles: `
    .layout {
      display: grid;
      grid-template-columns: 1fr 1fr;
      gap: var(--space-20);
      align-items: start;
    }
    .section {
      margin: 0 0 var(--space-4);
      font-size: 1rem;
    }
    .list {
      list-style: none;
      padding: 0;
      margin: var(--space-12) 0;
    }
    .row {
      display: flex;
      align-items: center;
      justify-content: space-between;
      gap: var(--space-12);
      padding: var(--space-8) 0;
      border-bottom: 1px solid var(--color-line, #eef1ec);
    }
    .name {
      margin: 0;
      font-weight: 600;
    }
    .row .sl-meta {
      margin: 0;
    }
    .required {
      display: inline-flex;
      align-items: center;
      gap: var(--space-4);
      min-height: 44px;
      margin-left: auto;
    }
    .moves {
      display: inline-flex;
      gap: var(--space-4);
    }
    .moves button {
      min-width: 44px;
    }
    .warning {
      color: var(--warning-ink, inherit);
      font-weight: 600;
    }
    .actions {
      display: flex;
      justify-content: end;
      gap: var(--space-8);
      margin-top: var(--space-16);
    }
    .error {
      color: var(--danger);
    }
  `,
})
export class ElementsStep {
  private readonly api = inject(TrackingApi);
  private readonly transloco = inject(TranslocoService);

  readonly version = input.required<Version>();
  readonly library = input<Element[]>([]);
  readonly editable = input(false);
  readonly saved = output<Version>();
  readonly back = output<void>();
  readonly next = output<void>();

  readonly chosen = signal<Binding[]>([]);
  readonly busy = signal(false);
  readonly failed = signal(false);
  private readonly lang = signal(this.transloco.getActiveLang());
  readonly tooMany = computed(() => this.chosen().length > ELEMENT_WARNING);

  constructor() {
    this.transloco.langChanges$.subscribe((lang) => this.lang.set(lang));
    effect(() => {
      const v = this.version();
      untracked(() =>
        this.chosen.set(
          [...(v.elements ?? [])].sort((a, b) => (a.position ?? 0) - (b.position ?? 0)),
        ),
      );
    });
  }

  label(e: Element): string {
    const labels = e.labels as Record<string, { name?: string } | undefined>;
    return labels[this.lang()]?.name ?? labels['en']?.name ?? e.code;
  }

  nameOf(code: string): string {
    const e = this.library().find((x) => x.code === code);
    return e ? this.label(e) : code;
  }

  has(e: Element): boolean {
    return this.chosen().some((b) => b.code === e.code);
  }

  add(e: Element) {
    this.chosen.update((list) => [...list, { code: e.code, version: e.version, required: true }]);
  }

  remove(i: number) {
    this.chosen.update((list) => list.filter((_, j) => j !== i));
  }

  toggle(i: number) {
    this.chosen.update((list) =>
      list.map((b, j) => (j === i ? { ...b, required: !b.required } : b)),
    );
  }

  move(i: number, by: -1 | 1) {
    this.chosen.update((list) => {
      const copy = [...list];
      const [item] = copy.splice(i, 1);
      copy.splice(i + by, 0, item!);
      return copy;
    });
  }

  async saveAndNext() {
    if (!this.editable()) {
      this.next.emit();
      return;
    }
    this.busy.set(true);
    this.failed.set(false);
    try {
      const v = orThrow(
        await this.api.client.PUT('/admin/v1/tracking/versions/{versionId}/elements', {
          params: { path: { versionId: this.version().id! } },
          body: this.chosen().map((b) => ({
            code: b.code,
            version: b.version,
            required: b.required,
          })),
        }),
      );
      this.saved.emit(v);
      this.next.emit();
    } catch {
      this.failed.set(true);
    } finally {
      this.busy.set(false);
    }
  }
}
