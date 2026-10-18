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
import { FormsModule } from '@angular/forms';
import { TranslocoPipe } from '@jsverse/transloco';

import { orThrow } from '../api/core-api';
import { TrackingApi, type TrackingSchemas } from '../api/tracking-api';
import { ACCENTS, type Accent } from './module-shared';

type Version = TrackingSchemas['ModuleVersion'];
type Kind = 'NONE' | 'YES_NO' | 'CHOICE';

interface Option {
  code: string;
  en: string;
  fr: string;
}

interface Form {
  nameEn: string;
  nameFr: string;
  descriptionEn: string;
  descriptionFr: string;
  audienceEn: string;
  audienceFr: string;
  imageAltEn: string;
  imageAltFr: string;
  imageId: string | undefined;
  accentColour: Accent;
  minAge: number | null;
  reminderDefault: string;
  questionKind: Kind;
  questionEn: string;
  questionFr: string;
  options: Option[];
}

const KINDS: Kind[] = ['NONE', 'YES_NO', 'CHOICE'];

function formOf(v: Version): Form {
  const q = v.enrolmentQuestion;
  return {
    nameEn: v.name?.en ?? '',
    nameFr: v.name?.fr ?? '',
    descriptionEn: v.description?.en ?? '',
    descriptionFr: v.description?.fr ?? '',
    audienceEn: v.audience?.en ?? '',
    audienceFr: v.audience?.fr ?? '',
    imageAltEn: v.imageAlt?.en ?? '',
    imageAltFr: v.imageAlt?.fr ?? '',
    imageId: v.imageId,
    accentColour: (v.accentColour as Accent) ?? 'brand',
    minAge: v.minAge ?? null,
    reminderDefault: (v.reminderDefault ?? '').slice(0, 5),
    questionKind: (q?.kind as Kind) ?? 'NONE',
    questionEn: q?.question?.en ?? '',
    questionFr: q?.question?.fr ?? '',
    options: (q?.options ?? []).map((o) => ({
      code: o.code ?? '',
      en: o.en ?? '',
      fr: o.fr ?? '',
    })),
  };
}

/** Builder step 1 (admin screen 22): names, card image, accent colour and enrolment. */
@Component({
  selector: 'sl-details-step',
  imports: [TranslocoPipe, FormsModule],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <form class="layout" (ngSubmit)="save(false)">
      <div class="main">
        <section class="sl-card">
          <h2 class="section">{{ 'admin.modules.details.names' | transloco }}</h2>
          <div class="pair">
            <label class="sl-field">
              <span>{{ 'admin.modules.details.nameEn' | transloco }}</span>
              <input
                class="sl-input"
                name="nameEn"
                maxlength="80"
                lang="en"
                [disabled]="!editable()"
                [ngModel]="form().nameEn"
                (ngModelChange)="set('nameEn', $event)"
              />
            </label>
            <label class="sl-field">
              <span>{{ 'admin.modules.details.nameFr' | transloco }}</span>
              <input
                class="sl-input"
                name="nameFr"
                maxlength="80"
                lang="fr"
                [disabled]="!editable()"
                [ngModel]="form().nameFr"
                (ngModelChange)="set('nameFr', $event)"
              />
            </label>
            <label class="sl-field">
              <span>{{ 'admin.modules.details.descriptionEn' | transloco }}</span>
              <textarea
                class="sl-textarea"
                rows="2"
                name="descriptionEn"
                maxlength="200"
                lang="en"
                [disabled]="!editable()"
                [ngModel]="form().descriptionEn"
                (ngModelChange)="set('descriptionEn', $event)"
              ></textarea>
            </label>
            <label class="sl-field">
              <span>{{ 'admin.modules.details.descriptionFr' | transloco }}</span>
              <textarea
                class="sl-textarea"
                rows="2"
                name="descriptionFr"
                maxlength="200"
                lang="fr"
                [disabled]="!editable()"
                [ngModel]="form().descriptionFr"
                (ngModelChange)="set('descriptionFr', $event)"
              ></textarea>
            </label>
            <label class="sl-field">
              <span>{{ 'admin.modules.details.audienceEn' | transloco }}</span>
              <input
                class="sl-input"
                name="audienceEn"
                maxlength="120"
                lang="en"
                [disabled]="!editable()"
                [ngModel]="form().audienceEn"
                (ngModelChange)="set('audienceEn', $event)"
              />
            </label>
            <label class="sl-field">
              <span>{{ 'admin.modules.details.audienceFr' | transloco }}</span>
              <input
                class="sl-input"
                name="audienceFr"
                maxlength="120"
                lang="fr"
                [disabled]="!editable()"
                [ngModel]="form().audienceFr"
                (ngModelChange)="set('audienceFr', $event)"
              />
            </label>
          </div>
        </section>

        <section class="sl-card">
          <h2 class="section">{{ 'admin.modules.details.image' | transloco }}</h2>
          <p class="sl-meta">{{ 'admin.modules.details.imageHint' | transloco }}</p>
          @if (form().imageId; as image) {
            <img class="card-image" [src]="imageUrl(image)" [alt]="form().imageAltEn" />
          }
          @if (editable()) {
            <label class="sl-button upload">
              {{
                (form().imageId
                  ? 'admin.modules.details.replaceImage'
                  : 'admin.modules.details.addImage'
                ) | transloco
              }}
              <input
                type="file"
                accept="image/jpeg,image/png,image/webp"
                class="visually-hidden"
                (change)="upload($event)"
              />
            </label>
          }
          @if (imageError()) {
            <p class="error" role="alert">{{ 'admin.modules.details.imageRefused' | transloco }}</p>
          }
          <div class="pair">
            <label class="sl-field">
              <span>{{ 'admin.modules.details.altEn' | transloco }}</span>
              <input
                class="sl-input"
                name="altEn"
                maxlength="200"
                lang="en"
                [disabled]="!editable()"
                [ngModel]="form().imageAltEn"
                (ngModelChange)="set('imageAltEn', $event)"
              />
            </label>
            <label class="sl-field">
              <span>{{ 'admin.modules.details.altFr' | transloco }}</span>
              <input
                class="sl-input"
                name="altFr"
                maxlength="200"
                lang="fr"
                [disabled]="!editable()"
                [ngModel]="form().imageAltFr"
                (ngModelChange)="set('imageAltFr', $event)"
              />
            </label>
          </div>
          <fieldset class="accents">
            <legend>{{ 'admin.modules.details.accent' | transloco }}</legend>
            @for (a of accents; track a) {
              <label class="accent">
                <input
                  type="radio"
                  name="accentColour"
                  [value]="a"
                  [disabled]="!editable()"
                  [ngModel]="form().accentColour"
                  (ngModelChange)="set('accentColour', $event)"
                />
                {{ 'admin.modules.colour.' + a | transloco }}
              </label>
            }
          </fieldset>
        </section>

        <section class="sl-card">
          <h2 class="section">{{ 'admin.modules.details.enrolment' | transloco }}</h2>
          <div class="pair">
            <label class="sl-field">
              <span>{{ 'admin.modules.details.minAge' | transloco }}</span>
              <input
                class="sl-input"
                type="number"
                min="18"
                max="120"
                name="minAge"
                [disabled]="!editable()"
                [ngModel]="form().minAge"
                (ngModelChange)="set('minAge', $event)"
              />
            </label>
            <label class="sl-field">
              <span>{{ 'admin.modules.details.reminder' | transloco }}</span>
              <input
                class="sl-input"
                type="time"
                name="reminder"
                [disabled]="!editable()"
                [ngModel]="form().reminderDefault"
                (ngModelChange)="set('reminderDefault', $event)"
              />
            </label>
          </div>
          <fieldset class="kinds">
            <legend>{{ 'admin.modules.details.question' | transloco }}</legend>
            @for (k of kinds; track k) {
              <label class="accent">
                <input
                  type="radio"
                  name="questionKind"
                  [value]="k"
                  [disabled]="!editable()"
                  [ngModel]="form().questionKind"
                  (ngModelChange)="set('questionKind', $event)"
                />
                {{ 'admin.modules.details.questionKind.' + k | transloco }}
              </label>
            }
          </fieldset>
          @if (form().questionKind !== 'NONE') {
            <div class="pair">
              <label class="sl-field">
                <span>{{ 'admin.modules.details.questionEn' | transloco }}</span>
                <input
                  class="sl-input"
                  name="questionEn"
                  maxlength="200"
                  lang="en"
                  [disabled]="!editable()"
                  [ngModel]="form().questionEn"
                  (ngModelChange)="set('questionEn', $event)"
                />
              </label>
              <label class="sl-field">
                <span>{{ 'admin.modules.details.questionFr' | transloco }}</span>
                <input
                  class="sl-input"
                  name="questionFr"
                  maxlength="200"
                  lang="fr"
                  [disabled]="!editable()"
                  [ngModel]="form().questionFr"
                  (ngModelChange)="set('questionFr', $event)"
                />
              </label>
            </div>
          }
          @if (form().questionKind === 'CHOICE') {
            @for (o of form().options; track $index; let i = $index) {
              <fieldset class="option">
                <legend>{{ 'admin.modules.details.option' | transloco: { n: i + 1 } }}</legend>
                <label class="sl-field">
                  <span>{{ 'admin.modules.details.optionCode' | transloco }}</span>
                  <input
                    class="sl-input"
                    [name]="'code' + i"
                    maxlength="40"
                    pattern="[a-z][a-z0-9_]*"
                    [disabled]="!editable()"
                    [ngModel]="o.code"
                    (ngModelChange)="setOption(i, 'code', $event)"
                  />
                </label>
                <label class="sl-field">
                  <span>{{ 'admin.modules.details.optionEn' | transloco }}</span>
                  <input
                    class="sl-input"
                    [name]="'en' + i"
                    maxlength="80"
                    lang="en"
                    [disabled]="!editable()"
                    [ngModel]="o.en"
                    (ngModelChange)="setOption(i, 'en', $event)"
                  />
                </label>
                <label class="sl-field">
                  <span>{{ 'admin.modules.details.optionFr' | transloco }}</span>
                  <input
                    class="sl-input"
                    [name]="'fr' + i"
                    maxlength="80"
                    lang="fr"
                    [disabled]="!editable()"
                    [ngModel]="o.fr"
                    (ngModelChange)="setOption(i, 'fr', $event)"
                  />
                </label>
                @if (editable()) {
                  <button
                    type="button"
                    class="sl-button sl-button--quiet"
                    (click)="removeOption(i)"
                  >
                    {{ 'admin.modules.details.removeOption' | transloco: { n: i + 1 } }}
                  </button>
                }
              </fieldset>
            }
            @if (editable() && form().options.length < 6) {
              <button type="button" class="sl-button" (click)="addOption()">
                {{ 'admin.modules.details.addOption' | transloco }}
              </button>
            }
          }
          <p class="sl-meta">{{ 'admin.modules.details.questionNote' | transloco }}</p>
        </section>
      </div>

      <aside
        class="sl-card preview"
        [attr.aria-label]="'admin.modules.details.preview' | transloco"
      >
        <h2 class="section">{{ 'admin.modules.details.preview' | transloco }}</h2>
        <div class="sl-tabs" role="tablist">
          @for (l of langs; track l) {
            <button
              role="tab"
              type="button"
              [attr.aria-selected]="l === previewLang()"
              (click)="previewLang.set(l)"
            >
              {{ l.toUpperCase() }}
            </button>
          }
        </div>
        <div
          class="phone-card"
          [attr.data-accent]="form().accentColour"
          [attr.lang]="previewLang()"
        >
          @if (form().imageId; as image) {
            <img
              [src]="imageUrl(image)"
              [alt]="previewLang() === 'fr' ? form().imageAltFr : form().imageAltEn"
            />
          } @else {
            <div class="placeholder" aria-hidden="true"></div>
          }
          <p class="card-name">{{ previewLang() === 'fr' ? form().nameFr : form().nameEn }}</p>
          <p class="sl-meta">
            {{ previewLang() === 'fr' ? form().descriptionFr : form().descriptionEn }}
          </p>
          <p class="sl-meta">
            {{ previewLang() === 'fr' ? form().audienceFr : form().audienceEn }}
          </p>
          <span class="sl-button sl-button--primary add" aria-hidden="true">
            {{ 'admin.modules.details.add' | transloco }}
          </span>
        </div>
      </aside>

      @if (editable()) {
        <div class="actions">
          @if (justSaved()) {
            <span class="sl-meta" role="status">{{ 'admin.modules.saved' | transloco }}</span>
          }
          <button type="submit" class="sl-button" [disabled]="busy()">
            {{ 'admin.modules.details.save' | transloco }}
          </button>
          <button
            type="button"
            class="sl-button sl-button--primary"
            [disabled]="busy()"
            (click)="save(true)"
          >
            {{ 'admin.modules.details.next' | transloco }}
          </button>
        </div>
      } @else {
        <div class="actions">
          <button type="button" class="sl-button sl-button--primary" (click)="next.emit()">
            {{ 'admin.modules.details.next' | transloco }}
          </button>
        </div>
      }
      @if (failed()) {
        <p class="error" role="alert">{{ 'common.checkConnection' | transloco }}</p>
      }
    </form>
  `,
  styles: `
    .layout {
      display: grid;
      grid-template-columns: minmax(0, 1fr) 20rem;
      gap: var(--space-20);
      align-items: start;
    }
    .main {
      display: grid;
      gap: var(--space-16);
    }
    .section {
      margin: 0 0 var(--space-12);
      font-size: 1rem;
    }
    .pair {
      display: grid;
      grid-template-columns: 1fr 1fr;
      gap: var(--space-12);
    }
    .accents,
    .kinds,
    .option {
      border: 0;
      padding: 0;
      margin: var(--space-12) 0;
      display: flex;
      flex-wrap: wrap;
      gap: var(--space-12);
      align-items: end;
    }
    .accent {
      display: inline-flex;
      align-items: center;
      gap: var(--space-8);
      min-height: 44px;
    }
    .card-image,
    .phone-card img,
    .placeholder {
      width: 100%;
      aspect-ratio: 3 / 2;
      object-fit: cover;
      border-radius: var(--radius-md, 8px);
      border: 1px solid var(--color-line, #dde3dc);
    }
    .card-image {
      max-width: 24rem;
      display: block;
      margin-bottom: var(--space-12);
    }
    .upload {
      display: inline-flex;
      align-items: center;
      cursor: pointer;
    }
    .visually-hidden {
      position: absolute;
      width: 1px;
      height: 1px;
      overflow: hidden;
      clip: rect(0 0 0 0);
    }
    .preview {
      position: sticky;
      top: var(--space-16);
    }
    .phone-card {
      border: 1px solid var(--color-line, #dde3dc);
      border-top: 4px solid var(--brand);
      border-radius: var(--radius-md, 8px);
      padding: var(--space-12);
      margin-top: var(--space-12);
    }
    .phone-card[data-accent='track-pink'] {
      border-top-color: var(--track-pink);
    }
    .phone-card[data-accent='track-gold'] {
      border-top-color: var(--track-gold);
    }
    .phone-card[data-accent='track-blue'] {
      border-top-color: var(--track-blue);
    }
    .card-name {
      font-weight: 700;
      margin: var(--space-8) 0 var(--space-4);
    }
    .add {
      margin-top: var(--space-8);
    }
    .actions {
      grid-column: 1 / -1;
      display: flex;
      justify-content: end;
      align-items: center;
      gap: var(--space-8);
    }
    .error {
      color: var(--danger);
    }
  `,
})
export class DetailsStep {
  private readonly api = inject(TrackingApi);

  readonly version = input.required<Version>();
  readonly editable = input(false);
  readonly justSaved = signal(false);
  readonly savedVersion = output<Version>({ alias: 'saved' });
  readonly next = output<void>();

  readonly accents = ACCENTS;
  readonly kinds = KINDS;
  readonly langs = ['en', 'fr'] as const;
  readonly previewLang = signal<'en' | 'fr'>('en');
  readonly form = signal<Form>(formOf({} as Version));
  readonly busy = signal(false);
  readonly failed = signal(false);
  readonly imageError = signal(false);

  readonly question = computed(() => {
    const f = this.form();
    if (f.questionKind === 'NONE') return undefined;
    return {
      kind: f.questionKind,
      question: { en: f.questionEn, fr: f.questionFr },
      options: f.questionKind === 'CHOICE' ? f.options : [],
    };
  });

  constructor() {
    effect(() => {
      const v = this.version();
      untracked(() => this.form.set(formOf(v)));
    });
  }

  imageUrl(id: string): string {
    return this.api.imageUrl(id);
  }

  set<K extends keyof Form>(key: K, value: Form[K]) {
    this.justSaved.set(false);
    this.form.update((f) => ({ ...f, [key]: value }));
  }

  setOption(i: number, key: keyof Option, value: string) {
    this.form.update((f) => ({
      ...f,
      options: f.options.map((o, j) => (j === i ? { ...o, [key]: value } : o)),
    }));
  }

  addOption() {
    this.form.update((f) => ({ ...f, options: [...f.options, { code: '', en: '', fr: '' }] }));
  }

  removeOption(i: number) {
    this.form.update((f) => ({ ...f, options: f.options.filter((_, j) => j !== i) }));
  }

  async upload(event: Event) {
    const file = (event.target as HTMLInputElement).files?.[0];
    if (!file) return;
    this.imageError.set(false);
    const body = new FormData();
    body.append('file', file);
    try {
      const stored = orThrow(
        await this.api.client.POST('/admin/v1/tracking/images', {
          // The multipart body goes as is; openapi-fetch would otherwise serialise it as JSON.
          body: body as unknown as { file: string },
          bodySerializer: (b) => b as unknown as FormData,
        }),
      );
      if (stored.id) this.set('imageId', stored.id);
    } catch {
      this.imageError.set(true);
    }
  }

  async save(andNext: boolean) {
    const f = this.form();
    this.busy.set(true);
    this.failed.set(false);
    try {
      const v = orThrow(
        await this.api.client.PUT('/admin/v1/tracking/versions/{versionId}/details', {
          params: { path: { versionId: this.version().id! } },
          body: {
            nameEn: f.nameEn,
            nameFr: f.nameFr,
            descriptionEn: f.descriptionEn,
            descriptionFr: f.descriptionFr,
            audienceEn: f.audienceEn,
            audienceFr: f.audienceFr,
            imageAltEn: f.imageAltEn,
            imageAltFr: f.imageAltFr,
            accentColour: f.accentColour,
            ...(f.imageId ? { imageId: f.imageId } : {}),
            ...(f.minAge ? { minAge: Number(f.minAge) } : {}),
            ...(f.reminderDefault ? { reminderDefault: f.reminderDefault } : {}),
            ...(this.question() ? { enrolmentQuestion: this.question() } : {}),
          },
        }),
      );
      this.savedVersion.emit(v);
      this.justSaved.set(true);
      if (andNext) this.next.emit();
    } catch {
      this.failed.set(true);
    } finally {
      this.busy.set(false);
    }
  }
}
