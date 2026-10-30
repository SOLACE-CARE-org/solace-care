import {
  ChangeDetectionStrategy,
  Component,
  type ElementRef,
  computed,
  inject,
  signal,
  viewChild,
} from '@angular/core';
import { RouterLink } from '@angular/router';
import { TranslocoPipe, TranslocoService } from '@jsverse/transloco';

import { ApiProblem, CoreApi, type CoreSchemas, orThrow } from '../api/core-api';
import { StaffSession } from '../auth/staff-session';
import { shortDate } from '../shared/format';

type Addition = CoreSchemas['PracticeAddition'];
type Request = CoreSchemas['SpecialtyRequest'];
type Status = CoreSchemas['SpecialtyRequestStatus'];
type Entry = CoreSchemas['CatalogueEntry'];
type Answer = 'create' | 'match' | 'decline';

const STATUSES: Status[] = ['OPEN', 'CREATED', 'MATCHED', 'DECLINED', 'WITHDRAWN'];

/**
 * What specialists added to their practice since approval, and their requests for specialties the
 * catalogue lacks (D62). Additions are checked on each specialist's record. A request is answered
 * here: create the specialty under a main one, match it to one that exists, or decline it with a
 * reason the specialist sees. Answering needs F on the Catalogue; Core checks it again.
 */
@Component({
  selector: 'sl-practice-requests',
  imports: [TranslocoPipe, RouterLink],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <a class="back" routerLink="/applications">{{ 'admin.specialists.review.back' | transloco }}</a>
    <h1 class="sl-title">
      {{ 'admin.specialists.requests.title' | transloco }}
    </h1>
    <p class="sl-muted">
      {{ 'admin.specialists.requests.subtitle' | transloco }}
    </p>

    <section class="sl-card table-card" aria-labelledby="additions-title">
      <h2 id="additions-title" class="section">
        {{ 'admin.specialists.requests.additions' | transloco }}
      </h2>
      @if (additions() === null) {
        <p class="state sl-muted">{{ 'common.loading' | transloco }}</p>
      } @else if (additions()!.length === 0) {
        <p class="state sl-muted">
          {{ 'admin.specialists.requests.noAdditions' | transloco }}
        </p>
      } @else {
        <table class="sl-table">
          <thead>
            <tr>
              <th scope="col">
                {{ 'admin.specialists.requests.specialist' | transloco }}
              </th>
              <th scope="col">
                {{ 'admin.specialists.requests.what' | transloco }}
              </th>
              <th scope="col">
                {{ 'admin.specialists.requests.asked' | transloco }}
              </th>
              <th scope="col">
                {{ 'admin.specialists.requests.state' | transloco }}
              </th>
            </tr>
          </thead>
          <tbody>
            @for (a of additions(); track $index) {
              <tr>
                <td>
                  <a [routerLink]="['/specialists', a.specialistId]">{{ a.fullName }}</a>
                </td>
                <td>
                  @if (a.kind === 'PROFESSION') {
                    {{
                      'admin.specialists.requests.addedProfession'
                        | transloco: { profession: a.professionLabel }
                    }}
                  } @else {
                    {{
                      'admin.specialists.requests.addedSpecialty'
                        | transloco
                          : {
                              specialty: a.specialtyLabel,
                              profession: a.professionLabel,
                            }
                    }}
                  }
                </td>
                <td>{{ short(a.requestedAt) }}</td>
                <td>
                  @if (a.verification) {
                    {{
                      'admin.specialists.requests.waitingSecond'
                        | transloco: { name: a.verification.by.name }
                    }}
                  } @else {
                    {{ 'admin.specialists.review.notChecked' | transloco }}
                  }
                </td>
              </tr>
            }
          </tbody>
        </table>
      }
    </section>

    <section class="sl-card table-card" aria-labelledby="requests-title">
      <div class="row">
        <h2 id="requests-title" class="section">
          {{ 'admin.specialists.requests.requests' | transloco }}
        </h2>
        <label class="filter">
          <span class="sl-meta">{{ 'admin.specialists.requests.show' | transloco }}</span>
          <select class="sl-select" [value]="status()" (change)="filter(value($event))">
            @for (s of statuses; track s) {
              <option [value]="s">
                {{ 'admin.specialists.practice.request_' + s | transloco }}
              </option>
            }
          </select>
        </label>
      </div>
      @if (requests() === null) {
        <p class="state sl-muted">{{ 'common.loading' | transloco }}</p>
      } @else if (requests()!.length === 0) {
        <p class="state sl-muted">
          {{ 'admin.specialists.requests.noRequests' | transloco }}
        </p>
      } @else {
        <ul class="requests">
          @for (q of requests(); track q.id) {
            <li>
              <div class="line">
                <strong>{{ q.proposedName }}</strong>
                <span class="sl-meta">
                  {{ q.professionLabel }} ·
                  <a [routerLink]="['/specialists', q.specialistId]">{{ q.fullName }}</a>
                  ·
                  {{ short(q.requestedAt) }}
                </span>
              </div>
              <p class="note">{{ q.note }}</p>
              @if (q.specialtyLabel) {
                <p class="sl-meta">→ {{ q.specialtyLabel }}</p>
              }
              @if (q.reviewNote) {
                <p class="sl-meta">
                  {{ 'admin.specialists.review.lastReason' | transloco: { reason: q.reviewNote } }}
                </p>
              }
              <div class="actions">
                @if (q.certificateId; as certificate) {
                  <button class="file" type="button" (click)="open(q.specialistId, certificate)">
                    {{ 'admin.specialists.review.doc_CERTIFICATE' | transloco }}
                  </button>
                }
                @if (q.status === 'OPEN' && canAnswer()) {
                  <button
                    class="sl-button sl-button--primary"
                    type="button"
                    (click)="ask(q, 'create')"
                  >
                    {{ 'admin.specialists.requests.create' | transloco }}
                  </button>
                  <button class="sl-button" type="button" (click)="ask(q, 'match')">
                    {{ 'admin.specialists.requests.match' | transloco }}
                  </button>
                  <button
                    class="sl-button sl-button--danger"
                    type="button"
                    (click)="ask(q, 'decline')"
                  >
                    {{ 'admin.specialists.practice.decline' | transloco }}
                  </button>
                }
              </div>
            </li>
          }
        </ul>
      }
    </section>
    @if (problem()) {
      <p class="error" role="alert">{{ problem() }}</p>
    }

    <dialog #answerDialog class="sl-dialog" aria-labelledby="answer-title">
      <h2 id="answer-title" class="section">
        {{ 'admin.specialists.requests.' + (answer() ?? 'create') + 'Title' | transloco }}
      </h2>
      @if (answering(); as q) {
        <p class="sl-meta">{{ q.proposedName }} · {{ q.professionLabel }}</p>
      }
      @switch (answer()) {
        @case ('create') {
          <label class="sl-field">
            <span>{{ 'admin.catalogue.code' | transloco }}</span>
            <input
              class="sl-input"
              maxlength="40"
              [value]="code()"
              (input)="code.set(value($event))"
            />
          </label>
          <label class="sl-field">
            <span>{{ 'admin.catalogue.english' | transloco }}</span>
            <input
              class="sl-input"
              lang="en"
              maxlength="80"
              [value]="labelEn()"
              (input)="labelEn.set(value($event))"
            />
          </label>
          <label class="sl-field">
            <span>{{ 'admin.catalogue.french' | transloco }}</span>
            <input
              class="sl-input"
              lang="fr"
              maxlength="80"
              [value]="labelFr()"
              (input)="labelFr.set(value($event))"
            />
          </label>
          <label class="sl-field">
            <span>{{ 'admin.catalogue.parent' | transloco }}</span>
            <select class="sl-select" [value]="parent()" (change)="parent.set(value($event))">
              <option value="" disabled>
                {{ 'admin.catalogue.choose' | transloco }}
              </option>
              @for (s of mainSpecialties(); track s.code) {
                <option [value]="s.code">{{ label(s) }}</option>
              }
            </select>
          </label>
        }
        @case ('match') {
          <label class="sl-field">
            <span>{{ 'admin.specialists.requests.matchTo' | transloco }}</span>
            <select class="sl-select" [value]="specialty()" (change)="specialty.set(value($event))">
              <option value="" disabled>
                {{ 'admin.catalogue.choose' | transloco }}
              </option>
              @for (s of specialties(); track s.code) {
                <option [value]="s.code">{{ label(s) }}</option>
              }
            </select>
          </label>
        }
      }
      <label class="sl-field">
        <span>{{
          (answer() === 'decline'
            ? 'admin.specialists.practice.reasonLabel'
            : 'admin.bookings.reasonRequired'
          ) | transloco
        }}</span>
        <textarea
          class="sl-textarea"
          rows="2"
          maxlength="500"
          [value]="reason()"
          (input)="reason.set(value($event))"
        ></textarea>
      </label>
      <div class="dialog-actions">
        <button class="sl-button" type="button" (click)="answerDialog.close()">
          {{ 'admin.common.cancel' | transloco }}
        </button>
        <button
          class="sl-button sl-button--primary"
          type="button"
          [disabled]="busy() || !ready()"
          (click)="send()"
        >
          {{ 'admin.specialists.review.send' | transloco }}
        </button>
      </div>
    </dialog>
  `,
  styles: `
    .back {
      display: inline-block;
      margin-bottom: var(--space-12);
    }
    .table-card {
      margin-top: var(--space-16);
      overflow-x: auto;
    }
    .section {
      margin: 0 0 var(--space-8);
      font-size: var(--type-title-md-size);
    }
    .state {
      padding: var(--space-8) 0;
    }
    .row,
    .line,
    .actions {
      display: flex;
      flex-wrap: wrap;
      align-items: center;
      gap: var(--space-10);
    }
    .row {
      justify-content: space-between;
    }
    .filter {
      display: flex;
      align-items: center;
      gap: var(--space-8);
    }
    .requests {
      list-style: none;
      margin: 0;
      padding: 0;
      display: flex;
      flex-direction: column;
      gap: var(--space-16);
    }
    .note {
      margin: var(--space-4) 0;
    }
    .file {
      min-height: 44px;
      padding: 0 var(--space-12);
      border: 1px solid var(--line-control);
      border-radius: var(--radius-10);
      background: var(--surface-card);
      color: var(--brand-deep);
      font: inherit;
      font-weight: 600;
      cursor: pointer;
    }
    .error {
      color: var(--danger);
      font-weight: 600;
    }
    .dialog-actions {
      display: flex;
      justify-content: flex-end;
      gap: var(--space-8);
      margin-top: var(--space-12);
    }
  `,
})
export class PracticeRequestsPage {
  private readonly api = inject(CoreApi);
  private readonly transloco = inject(TranslocoService);
  private readonly staff = inject(StaffSession);
  private readonly dialog = viewChild<ElementRef<HTMLDialogElement>>('answerDialog');

  readonly statuses = STATUSES;
  readonly additions = signal<Addition[] | null>(null);
  readonly requests = signal<Request[] | null>(null);
  readonly catalogue = signal<Entry[]>([]);
  readonly status = signal<Status>('OPEN');
  readonly answering = signal<Request | null>(null);
  readonly answer = signal<Answer | null>(null);
  readonly code = signal('');
  readonly labelEn = signal('');
  readonly labelFr = signal('');
  readonly parent = signal('');
  readonly specialty = signal('');
  readonly reason = signal('');
  readonly busy = signal(false);
  readonly problem = signal<string | null>(null);
  readonly canAnswer = computed(() => this.staff.accessTo('catalogue') === 'F');
  private readonly lang = signal(this.transloco.getActiveLang());

  readonly specialties = computed(() => this.catalogue().filter((s) => s.enabled));
  readonly mainSpecialties = computed(() => this.specialties().filter((s) => !s.parentCode));

  readonly ready = computed(() => {
    if (!this.reason().trim()) return false;
    switch (this.answer()) {
      case 'create':
        return (
          /^[a-z][a-z0-9-]*$/.test(this.code().trim()) &&
          !!this.labelEn().trim() &&
          !!this.labelFr().trim() &&
          !!this.parent()
        );
      case 'match':
        return !!this.specialty();
      default:
        return true;
    }
  });

  constructor() {
    this.transloco.langChanges$.subscribe((lang) => {
      this.lang.set(lang);
      void this.load();
    });
  }

  async load() {
    this.problem.set(null);
    try {
      this.additions.set(orThrow(await this.api.client.GET('/admin/v1/specialist-additions')));
      this.requests.set(
        orThrow(
          await this.api.client.GET('/admin/v1/specialty-requests', {
            params: { query: { status: this.status() } },
          }),
        ),
      );
    } catch (e) {
      this.problem.set(this.message(e));
    }
    if (this.canAnswer()) {
      try {
        this.catalogue.set(orThrow(await this.api.client.GET('/admin/v1/catalogue')).specialties);
      } catch {
        this.catalogue.set([]);
      }
    }
  }

  filter(status: string) {
    this.status.set(status as Status);
    this.requests.set(null);
    void this.load();
  }

  ask(q: Request, answer: Answer) {
    this.answering.set(q);
    this.answer.set(answer);
    // A code from the name the specialist gave, for staff to adjust.
    this.code.set(
      q.proposedName
        .normalize('NFD')
        .replace(/\p{M}/gu, '')
        .toLowerCase()
        .replace(/[^a-z0-9]+/g, '-')
        .replace(/^-+|-+$/g, '')
        .replace(/^[^a-z]+/, ''),
    );
    this.labelEn.set('');
    this.labelFr.set('');
    this.parent.set('');
    this.specialty.set('');
    this.reason.set('');
    this.dialog()?.nativeElement.showModal();
  }

  async send() {
    const q = this.answering();
    const answer = this.answer();
    if (!q || !answer) return;
    const path = { requestId: q.id };
    const reason = this.reason().trim();
    this.busy.set(true);
    this.problem.set(null);
    try {
      if (answer === 'create') {
        orThrow(
          await this.api.client.POST('/admin/v1/specialty-requests/{requestId}/creation', {
            params: { path },
            body: {
              code: this.code().trim(),
              labelEn: this.labelEn().trim(),
              labelFr: this.labelFr().trim(),
              parentCode: this.parent(),
              reason,
            },
          }),
        );
      } else if (answer === 'match') {
        orThrow(
          await this.api.client.POST('/admin/v1/specialty-requests/{requestId}/match', {
            params: { path },
            body: { specialty: this.specialty(), reason },
          }),
        );
      } else {
        orThrow(
          await this.api.client.POST('/admin/v1/specialty-requests/{requestId}/decline', {
            params: { path },
            body: { reason },
          }),
        );
      }
      this.dialog()?.nativeElement.close();
      await this.load();
    } catch (e) {
      this.problem.set(this.message(e));
    } finally {
      this.busy.set(false);
    }
  }

  async open(specialistId: string, fileId: string) {
    const tab = window.open('', '_blank');
    try {
      const url = URL.createObjectURL(await this.api.document(specialistId, fileId));
      if (tab) tab.location.href = url;
      setTimeout(() => URL.revokeObjectURL(url), 60_000);
    } catch (e) {
      tab?.close();
      this.problem.set(this.message(e));
    }
  }

  label(e: Entry): string {
    return this.lang() === 'fr' ? e.labelFr : e.labelEn;
  }

  short(iso: string | undefined): string {
    return shortDate(iso, this.lang());
  }

  value(event: Event): string {
    return (event.target as HTMLInputElement).value;
  }

  private message(e: unknown): string {
    return e instanceof ApiProblem && e.title
      ? e.title
      : this.transloco.translate('common.checkConnection');
  }
}
