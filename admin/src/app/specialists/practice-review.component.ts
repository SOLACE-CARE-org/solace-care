import {
  ChangeDetectionStrategy,
  Component,
  type ElementRef,
  computed,
  inject,
  input,
  output,
  signal,
  viewChild,
} from '@angular/core';
import { RouterLink } from '@angular/router';
import { TranslocoPipe, TranslocoService } from '@jsverse/transloco';

import { ApiProblem, CoreApi, type CoreSchemas, orThrow } from '../api/core-api';
import { StaffSession } from '../auth/staff-session';
import { calendarDate, shortDate } from '../shared/format';

type Review = CoreSchemas['ApplicationReview'];
type Profession = Review['professions'][number];
type Specialty = Profession['specialties'][number];
type Rejection = { profession: string; specialty?: string };

/**
 * Each profession a specialist claims, with its licence and specialties (D62). In an application
 * under review, checking them is one of the six items and the application's approval approves
 * them. On an approved specialist's record, a profession they added needs a second person to
 * approve it after the first checks the licence; a specialty needs one reviewer. Core checks every
 * action again.
 */
@Component({
  selector: 'sl-practice-review',
  imports: [TranslocoPipe, RouterLink],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    @for (p of review().professions; track p.profession) {
      <section class="sl-card profession" [class.current]="checkable(p) && !p.verification">
        <div class="head">
          <h3>
            {{ p.label }}
            @if (p.position === 1) {
              <span class="sl-meta">· {{ 'admin.specialists.practice.main' | transloco }}</span>
            }
          </h3>
          <span [class]="pill(p.status)">{{
            'admin.specialists.practice.status_' + p.status | transloco
          }}</span>
          @if (p.addedAfterApproval && p.status === 'PENDING') {
            <span class="sl-pill">{{ 'admin.specialists.practice.added' | transloco }}</span>
          }
        </div>
        <dl class="facts">
          <div>
            <dt>{{ 'admin.specialists.review.licenceNumber' | transloco }}</dt>
            <dd>{{ p.licenceNumber }}</dd>
          </div>
          <div>
            <dt>{{ 'admin.specialists.review.issuedBy' | transloco }}</dt>
            <dd>{{ p.licensingBody }}</dd>
          </div>
          <div>
            <dt>{{ 'admin.specialists.review.validUntil' | transloco }}</dt>
            <dd>
              @if (p.validUntil && p.verification) {
                {{ day(p.validUntil) }}
              } @else if (checkable(p) && canChange()) {
                <input
                  class="sl-input"
                  type="date"
                  [attr.aria-label]="
                    ('admin.specialists.review.validUntil' | transloco) + ', ' + p.label
                  "
                  [value]="validUntil()[p.profession] ?? ''"
                  (input)="setValidUntil(p.profession, value($event))"
                />
              } @else {
                —
              }
            </dd>
          </div>
        </dl>
        <div class="files">
          @for (d of licences(p.profession); track d.id) {
            <button class="file" type="button" (click)="openDocument.emit(d.id)">
              {{ 'admin.specialists.review.doc_LICENCE' | transloco }} ·
              {{ d.fileName }}
            </button>
          }
        </div>
        @if (p.verification) {
          <p class="sl-meta">{{ verifiedBy(p.verification) }}</p>
        }
        @if (p.decision && p.status !== 'PENDING') {
          <p class="sl-meta">
            {{
              'admin.specialists.practice.decidedBy'
                | transloco: { name: p.decision.by.name, date: short(p.decision.at) }
            }}
          </p>
        }
        @if (p.reviewNote) {
          <p class="sl-meta">
            {{ 'admin.specialists.review.lastReason' | transloco: { reason: p.reviewNote } }}
          </p>
        }
        @if (checkable(p) && canChange()) {
          <div class="actions">
            @if (p.verification) {
              <button
                class="sl-button sl-button--quiet"
                type="button"
                [disabled]="busy()"
                (click)="verifyProfession(p, false)"
              >
                {{ 'admin.specialists.review.undo' | transloco }}
              </button>
            } @else {
              <button
                class="sl-button"
                type="button"
                [disabled]="busy() || !validUntil()[p.profession]"
                (click)="verifyProfession(p, true)"
              >
                {{ 'admin.specialists.practice.checkLicence' | transloco }}
              </button>
            }
            <button
              class="sl-button sl-button--danger"
              type="button"
              [disabled]="busy()"
              (click)="askReject({ profession: p.profession })"
            >
              {{ 'admin.specialists.practice.reject' | transloco }}
            </button>
            @if (mode() === 'record' && p.verification) {
              @if (p.verification.by.id === staffId()) {
                <span class="sl-meta">{{
                  'admin.specialists.practice.secondPerson' | transloco
                }}</span>
              } @else {
                <button
                  class="sl-button sl-button--primary"
                  type="button"
                  [disabled]="busy()"
                  (click)="approveProfession(p)"
                >
                  {{ 'admin.specialists.practice.approveProfession' | transloco }}
                </button>
              }
            }
          </div>
        }

        <h4 class="sub">
          {{ 'admin.specialists.practice.specialties' | transloco }}
        </h4>
        <ul class="specialties">
          @for (s of p.specialties; track s.specialty) {
            <li>
              <div class="line">
                <strong>{{ s.label }}</strong>
                <span [class]="pill(s.status)">{{
                  'admin.specialists.practice.status_' + s.status | transloco
                }}</span>
                @if (s.verification && s.status === 'PENDING') {
                  <span class="sl-meta">{{ verifiedBy(s.verification) }}</span>
                }
              </div>
              @if (s.note) {
                <p class="note">
                  <span class="sl-meta">{{ 'admin.specialists.practice.note' | transloco }}</span>
                  {{ s.note }}
                </p>
              }
              @if (s.reviewNote) {
                <p class="sl-meta">
                  {{ 'admin.specialists.review.lastReason' | transloco: { reason: s.reviewNote } }}
                </p>
              }
              <div class="actions">
                @if (s.certificateId; as certificate) {
                  <button class="file" type="button" (click)="openDocument.emit(certificate)">
                    {{ 'admin.specialists.review.doc_CERTIFICATE' | transloco }}
                  </button>
                }
                @if (specialtyCheckable(p, s) && canChange()) {
                  @if (approvesAtOnce(p)) {
                    <button
                      class="sl-button sl-button--primary"
                      type="button"
                      [disabled]="busy()"
                      (click)="verifySpecialty(p, s, true)"
                    >
                      {{ 'admin.specialists.practice.approveSpecialty' | transloco }}
                    </button>
                  } @else if (s.verification) {
                    <button
                      class="sl-button sl-button--quiet"
                      type="button"
                      [disabled]="busy()"
                      (click)="verifySpecialty(p, s, false)"
                    >
                      {{ 'admin.specialists.review.undo' | transloco }}
                    </button>
                  } @else {
                    <button
                      class="sl-button"
                      type="button"
                      [disabled]="busy()"
                      (click)="verifySpecialty(p, s, true)"
                    >
                      {{ 'admin.specialists.review.verify' | transloco }}
                    </button>
                  }
                  <button
                    class="sl-button sl-button--danger"
                    type="button"
                    [disabled]="busy()"
                    (click)="
                      askReject({
                        profession: p.profession,
                        specialty: s.specialty,
                      })
                    "
                  >
                    {{ 'admin.specialists.practice.decline' | transloco }}
                  </button>
                }
              </div>
            </li>
          }
        </ul>
      </section>
    }

    @if (review().specialtyRequests.length > 0) {
      <section class="sl-card">
        <h3>{{ 'admin.specialists.practice.requests' | transloco }}</h3>
        <ul class="specialties">
          @for (q of review().specialtyRequests; track q.id) {
            <li class="line">
              <strong>{{ q.proposedName }}</strong>
              <span class="sl-meta">{{ q.professionLabel }}</span>
              <span [class]="requestPill(q.status)">{{
                'admin.specialists.practice.request_' + q.status | transloco
              }}</span>
              @if (q.specialtyLabel) {
                <span class="sl-meta">→ {{ q.specialtyLabel }}</span>
              }
            </li>
          }
        </ul>
        <a class="sl-meta" routerLink="/practice-requests">{{
          'admin.specialists.practice.openRequests' | transloco
        }}</a>
      </section>
    }

    @if (error()) {
      <p class="error" role="alert">{{ error() }}</p>
    }

    <dialog #rejectDialog class="sl-dialog" (close)="rejecting.set(null)">
      <h2>
        {{
          (rejecting()?.specialty
            ? 'admin.specialists.practice.declineTitle'
            : 'admin.specialists.practice.rejectTitle'
          ) | transloco
        }}
      </h2>
      <label class="sl-field">
        <span>{{ 'admin.specialists.practice.reasonLabel' | transloco }}</span>
        <textarea
          class="sl-textarea"
          maxlength="1000"
          [value]="reason()"
          (input)="reason.set(value($event))"
        ></textarea>
      </label>
      <div class="dialog-actions">
        <button class="sl-button" type="button" (click)="closeDialog()">
          {{ 'admin.specialists.review.cancel' | transloco }}
        </button>
        <button
          class="sl-button sl-button--danger"
          type="button"
          [disabled]="busy() || !reason().trim()"
          (click)="reject()"
        >
          {{ 'admin.specialists.review.send' | transloco }}
        </button>
      </div>
    </dialog>
  `,
  styles: `
    :host {
      display: flex;
      flex-direction: column;
      gap: var(--space-12);
    }
    .profession.current {
      border-color: var(--brand);
    }
    .head,
    .line,
    .actions {
      display: flex;
      flex-wrap: wrap;
      align-items: center;
      gap: var(--space-10);
    }
    h3 {
      margin: 0;
      font-size: var(--type-title-sm-size);
    }
    .sub {
      margin: var(--space-16) 0 var(--space-8);
      font-size: var(--type-body-admin-size);
    }
    .facts {
      display: grid;
      grid-template-columns: repeat(3, minmax(0, 1fr));
      gap: var(--space-12);
      margin: var(--space-12) 0;
    }
    .facts dt {
      color: var(--ink-muted);
      font-size: 13px;
    }
    .facts dd {
      margin: var(--space-2) 0 0;
    }
    .files {
      display: flex;
      flex-wrap: wrap;
      gap: var(--space-8);
      margin-bottom: var(--space-8);
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
    .specialties {
      list-style: none;
      margin: 0;
      padding: 0;
      display: flex;
      flex-direction: column;
      gap: var(--space-12);
    }
    .note {
      margin: var(--space-4) 0;
    }
    .error {
      color: var(--danger);
      font-weight: 600;
    }
    .dialog-actions {
      display: flex;
      justify-content: flex-end;
      gap: var(--space-10);
      margin-top: var(--space-16);
    }
  `,
})
export class PracticeReviewComponent {
  private readonly api = inject(CoreApi);
  private readonly transloco = inject(TranslocoService);
  private readonly staff = inject(StaffSession);

  readonly review = input.required<Review>();
  /** An application under review, or an approved specialist's record. */
  readonly mode = input.required<'application' | 'record'>();
  readonly changed = output<Review>();
  readonly openDocument = output<string>();

  readonly canChange = computed(() => this.staff.accessTo('specialists') === 'F');
  readonly staffId = computed(() => this.staff.id());
  readonly validUntil = signal<Record<string, string>>({});
  readonly rejecting = signal<Rejection | null>(null);
  readonly reason = signal('');
  readonly busy = signal(false);
  readonly error = signal<string | null>(null);
  private readonly lang = signal(this.transloco.getActiveLang());
  private readonly dialog = viewChild<ElementRef<HTMLDialogElement>>('rejectDialog');

  constructor() {
    this.transloco.langChanges$.subscribe((lang) => this.lang.set(lang));
  }

  /** Open to checks: pending, in an application under review or on an approved record. */
  checkable(p: Profession): boolean {
    const status = this.review().status;
    const open = this.mode() === 'application' ? status === 'UNDER_REVIEW' : status === 'APPROVED';
    return open && p.status === 'PENDING';
  }

  specialtyCheckable(p: Profession, s: Specialty): boolean {
    return s.status === 'PENDING' && (this.checkable(p) || this.approvesAtOnce(p));
  }

  /** Under an approved profession on a record, one reviewer approves a specialty (D62). */
  approvesAtOnce(p: Profession): boolean {
    return (
      this.mode() === 'record' && this.review().status === 'APPROVED' && p.status === 'APPROVED'
    );
  }

  licences(profession: string) {
    return this.review().documents.filter(
      (d) => d.kind === 'LICENCE' && d.profession === profession,
    );
  }

  pill(status: string): string {
    if (status === 'APPROVED') return 'sl-pill sl-pill--brand';
    if (status === 'REJECTED' || status === 'DECLINED') return 'sl-pill sl-pill--danger';
    if (status === 'WITHDRAWN') return 'sl-pill';
    return 'sl-pill sl-pill--warning';
  }

  requestPill(status: string): string {
    if (status === 'CREATED' || status === 'MATCHED') return 'sl-pill sl-pill--brand';
    if (status === 'DECLINED') return 'sl-pill sl-pill--danger';
    if (status === 'WITHDRAWN') return 'sl-pill';
    return 'sl-pill sl-pill--warning';
  }

  verifiedBy(v: { by: { name: string }; at: string }): string {
    return this.transloco.translate('admin.specialists.review.verifiedBy', {
      name: v.by.name,
      date: this.short(v.at),
    });
  }

  setValidUntil(profession: string, date: string) {
    this.validUntil.update((v) => ({ ...v, [profession]: date }));
  }

  verifyProfession(p: Profession, verified: boolean) {
    const licenceValidUntil = this.validUntil()[p.profession];
    return this.act(() =>
      this.api.client.PUT('/admin/v1/specialists/{id}/professions/{profession}/verification', {
        params: { path: { id: this.review().id, profession: p.profession } },
        body: verified && licenceValidUntil ? { verified, licenceValidUntil } : { verified },
      }),
    );
  }

  /** The second person (D29); Core refuses whoever checked the licence. */
  approveProfession(p: Profession) {
    return this.act(() =>
      this.api.client.POST('/admin/v1/specialists/{id}/professions/{profession}/approval', {
        params: { path: { id: this.review().id, profession: p.profession } },
      }),
    );
  }

  verifySpecialty(p: Profession, s: Specialty, verified: boolean) {
    return this.act(() =>
      this.api.client.PUT(
        '/admin/v1/specialists/{id}/professions/{profession}/specialties/{specialty}/verification',
        {
          params: {
            path: {
              id: this.review().id,
              profession: p.profession,
              specialty: s.specialty,
            },
          },
          body: { verified },
        },
      ),
    );
  }

  askReject(target: Rejection) {
    this.rejecting.set(target);
    this.reason.set('');
    this.dialog()?.nativeElement.showModal();
  }

  closeDialog() {
    this.dialog()?.nativeElement.close();
  }

  async reject() {
    const target = this.rejecting();
    const reason = this.reason().trim();
    if (!target || !reason) return;
    const id = this.review().id;
    await this.act(() =>
      target.specialty
        ? this.api.client.POST(
            '/admin/v1/specialists/{id}/professions/{profession}/specialties/{specialty}/rejection',
            {
              params: {
                path: {
                  id,
                  profession: target.profession,
                  specialty: target.specialty,
                },
              },
              body: { reason },
            },
          )
        : this.api.client.POST('/admin/v1/specialists/{id}/professions/{profession}/rejection', {
            params: { path: { id, profession: target.profession } },
            body: { reason },
          }),
    );
    this.closeDialog();
  }

  short(iso: string | undefined): string {
    return shortDate(iso, this.lang());
  }

  day(isoDate: string | undefined): string {
    return calendarDate(isoDate, this.lang());
  }

  value(event: Event): string {
    return (event.target as HTMLInputElement | HTMLTextAreaElement).value;
  }

  private async act(call: () => Promise<{ data?: Review; error?: unknown; response: Response }>) {
    this.busy.set(true);
    this.error.set(null);
    try {
      this.changed.emit(orThrow(await call()));
    } catch (e) {
      this.error.set(
        e instanceof ApiProblem && e.title
          ? e.title
          : this.transloco.translate('common.checkConnection'),
      );
    } finally {
      this.busy.set(false);
    }
  }
}
