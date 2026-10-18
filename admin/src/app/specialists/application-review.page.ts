import {
  ChangeDetectionStrategy,
  Component,
  type ElementRef,
  computed,
  effect,
  inject,
  input,
  signal,
  viewChild,
} from '@angular/core';
import { NgTemplateOutlet } from '@angular/common';
import { RouterLink } from '@angular/router';
import { TranslocoPipe, TranslocoService } from '@jsverse/transloco';
import { LucideChevronLeft, LucideDynamicIcon } from '@lucide/angular';

import { ApiProblem, CoreApi, type CoreSchemas, orThrow } from '../api/core-api';
import { StaffSession } from '../auth/staff-session';
import {
  calendarDate,
  capitalised,
  dateTime,
  fcfa,
  initials,
  languageName,
  shortDate,
} from './format';

type Review = CoreSchemas['ApplicationReview'];
type Item = 'IDENTITY' | 'LICENCE' | 'PROFILE' | 'PAYOUT';
type Level = 'NATIVE' | 'FLUENT' | 'PROFESSIONAL' | 'BELOW_PROFESSIONAL';
type Decision = 'changes' | 'reject';

const LEVELS: Level[] = ['NATIVE', 'FLUENT', 'PROFESSIONAL', 'BELOW_PROFESSIONAL'];

/**
 * Admin screen 5: one applicant's licence, documents, languages and services
 * (ApplicationReview.dc.html). Approval needs all six items verified; a change request or
 * rejection carries a reason the applicant sees. Core checks every action again.
 */
@Component({
  selector: 'sl-application-review',
  imports: [TranslocoPipe, RouterLink, LucideDynamicIcon, NgTemplateOutlet],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './application-review.page.html',
  styleUrl: './application-review.page.css',
})
export class ApplicationReviewPage {
  private readonly api = inject(CoreApi);
  private readonly transloco = inject(TranslocoService);
  private readonly staff = inject(StaffSession);

  /** The application id, from the route. */
  readonly id = input.required<string>();

  readonly back = LucideChevronLeft;
  readonly levels = LEVELS;
  readonly review = signal<Review | null>(null);
  readonly error = signal<string | null>(null);
  readonly busy = signal(false);
  readonly lang = signal(this.transloco.getActiveLang());
  readonly canChange = computed(() => this.staff.accessTo('specialists') === 'F');
  readonly underReview = computed(() => this.review()?.status === 'UNDER_REVIEW');
  readonly inReview = computed(() =>
    ['SUBMITTED', 'UNDER_REVIEW'].includes(this.review()?.status ?? ''),
  );

  // Form state: what the reviewer is typing, apart from what Core has.
  readonly licenceValidUntil = signal('');
  readonly bioDraft = signal<{ en?: string; fr?: string }>({});
  readonly notesDraft = signal<string | null>(null);
  readonly interviewAt = signal('');
  readonly decision = signal<Decision | null>(null);
  readonly reason = signal('');
  private readonly dialog = viewChild<ElementRef<HTMLDialogElement>>('decisionDialog');

  constructor() {
    this.transloco.langChanges$.subscribe((lang) => this.lang.set(lang));
    effect(() => {
      this.lang();
      void this.load(this.id());
    });
  }

  async load(id: string) {
    this.error.set(null);
    try {
      this.review.set(
        orThrow(
          await this.api.client.GET('/admin/v1/specialist-applications/{id}', {
            params: { path: { id } },
          }),
        ),
      );
    } catch (e) {
      this.error.set(this.message(e));
    }
  }

  // --- Display ---------------------------------------------------------------------------------

  status(r: Review): string {
    return r.changesRequested ? 'CHANGES_REQUESTED' : r.status;
  }

  statusClass(r: Review): string {
    if (r.status === 'APPROVED') return 'sl-pill sl-pill--brand';
    if (r.status === 'REJECTED' || r.status === 'SUSPENDED') return 'sl-pill sl-pill--danger';
    return 'sl-pill sl-pill--warning';
  }

  initialsOf(name: string): string {
    return initials(name);
  }

  short(iso: string | undefined): string {
    return shortDate(iso, this.lang());
  }

  when(iso: string | undefined): string {
    return dateTime(iso, this.lang());
  }

  day(isoDate: string | undefined): string {
    return calendarDate(isoDate, this.lang());
  }

  verifiedBy(v: { by: { name: string }; at: string } | undefined): string {
    return v
      ? this.transloco.translate('admin.specialists.review.verifiedBy', {
          name: v.by.name,
          date: this.short(v.at),
        })
      : '';
  }

  languageOf(code: string): string {
    return capitalised(languageName(code, this.lang()));
  }

  verifiedLanguages(r: Review): number {
    return r.languages.filter((l) => l.verifiedLevel !== undefined).length;
  }

  offering(o: CoreSchemas['Offering']): string {
    return this.transloco.translate('admin.specialists.review.offering', {
      modality: this.transloco.translate(`admin.specialists.review.modality_${o.modality}`),
      minutes: o.durationMinutes,
      price: fcfa(o.price, this.lang()),
    });
  }

  missingBio(r: Review): 'en' | 'fr' | null {
    if (!r.profile.bioEn) return 'en';
    if (!r.profile.bioFr) return 'fr';
    return null;
  }

  bioValue(r: Review, language: 'en' | 'fr'): string {
    const draft = this.bioDraft()[language];
    if (draft !== undefined) return draft;
    return (language === 'en' ? r.profile.bioEn : r.profile.bioFr) ?? '';
  }

  documents(r: Review, kind: string) {
    return r.documents.filter((d) => d.kind === kind);
  }

  activity(a: Review['activity'][number]): string {
    const subject =
      a.kind === 'LANGUAGE_VERIFIED' || a.kind === 'BIO_ADDED'
        ? languageName(a.subject ?? '', this.lang())
        : a.subject
          ? this.transloco.translate(`admin.specialists.review.item_${a.subject}`)
          : '';
    const value = a.value
      ? this.transloco.translate(`admin.specialists.review.level_${a.value}`)
      : '';
    const text = this.transloco.translate(`admin.specialists.review.kind_${a.kind}`, {
      subject,
      value,
    });
    return `${this.short(a.at)} · ${capitalised(text)}${a.by ? ` (${a.by.name})` : ''}`;
  }

  // --- Actions ---------------------------------------------------------------------------------

  open() {
    return this.act(() =>
      this.api.client.POST('/admin/v1/specialist-applications/{id}/review', {
        params: { path: { id: this.id() } },
      }),
    );
  }

  verify(item: Item, verified: boolean) {
    const licenceValidUntil = item === 'LICENCE' && verified ? this.licenceValidUntil() : undefined;
    return this.act(() =>
      this.api.client.PUT('/admin/v1/specialist-applications/{id}/items/{item}', {
        params: { path: { id: this.id(), item } },
        body: {
          verified,
          ...(licenceValidUntil ? { licenceValidUntil } : {}),
        },
      }),
    );
  }

  verifyLanguage(code: string, event: Event) {
    const verifiedLevel = (event.target as HTMLSelectElement).value as Level;
    if (!verifiedLevel) return;
    return this.act(() =>
      this.api.client.PUT('/admin/v1/specialist-applications/{id}/languages/{code}', {
        params: { path: { id: this.id(), code } },
        body: { verifiedLevel },
      }),
    );
  }

  hasDraft(language: 'en' | 'fr'): boolean {
    return this.bioDraft()[language] !== undefined;
  }

  draftBio(language: 'en' | 'fr', text: string) {
    this.bioDraft.update((d) => ({ ...d, [language]: text }));
  }

  saveBio(language: 'en' | 'fr') {
    const text = (this.bioDraft()[language] ?? '').trim();
    if (!text) return;
    return this.act(
      () =>
        this.api.client.PUT('/admin/v1/specialist-applications/{id}/bio', {
          params: { path: { id: this.id() } },
          body: { language, text },
        }),
      () => this.bioDraft.set({}),
    );
  }

  saveNotes() {
    const notes = this.notesDraft();
    if (notes === null) return;
    return this.act(
      () =>
        this.api.client.PUT('/admin/v1/specialist-applications/{id}/notes', {
          params: { path: { id: this.id() } },
          body: { notes },
        }),
      () => this.notesDraft.set(null),
    );
  }

  bookInterview() {
    const local = this.interviewAt();
    if (!local) return;
    // The input is in the viewer's time zone; Core stores UTC.
    const at = new Date(local).toISOString();
    return this.act(() =>
      this.api.client.PUT('/admin/v1/specialist-applications/{id}/interview', {
        params: { path: { id: this.id() } },
        body: { at },
      }),
    );
  }

  /** True when the signed-in staff member proposed the waiting approval (D29). */
  proposedByMe(r: Review): boolean {
    return r.approvalProposal?.by.id === this.staff.id();
  }

  proposeApproval() {
    return this.act(() =>
      this.api.client.POST('/admin/v1/specialist-applications/{id}/approval-proposal', {
        params: { path: { id: this.id() } },
      }),
    );
  }

  withdrawApproval() {
    return this.act(() =>
      this.api.client.DELETE('/admin/v1/specialist-applications/{id}/approval-proposal', {
        params: { path: { id: this.id() } },
      }),
    );
  }

  /** The second person confirms; Core refuses the proposer. */
  approve() {
    return this.act(() =>
      this.api.client.POST('/admin/v1/specialist-applications/{id}/approval', {
        params: { path: { id: this.id() } },
      }),
    );
  }

  ask(decision: Decision) {
    this.decision.set(decision);
    this.reason.set('');
    this.dialog()?.nativeElement.showModal();
  }

  closeDialog() {
    this.dialog()?.nativeElement.close();
    this.decision.set(null);
  }

  async decide() {
    const reason = this.reason().trim();
    const decision = this.decision();
    if (!reason || !decision) return;
    const path =
      decision === 'changes'
        ? ('/admin/v1/specialist-applications/{id}/change-request' as const)
        : ('/admin/v1/specialist-applications/{id}/rejection' as const);
    await this.act(() =>
      this.api.client.POST(path, {
        params: { path: { id: this.id() } },
        body: { reason },
      }),
    );
    this.closeDialog();
  }

  async openDocument(fileId: string) {
    // Open the tab now, while the click still counts as the user's gesture, then fill it.
    const tab = window.open('', '_blank');
    try {
      const blob = await this.api.document(this.id(), fileId);
      const url = URL.createObjectURL(blob);
      if (tab) tab.location.href = url;
      setTimeout(() => URL.revokeObjectURL(url), 60_000);
    } catch (e) {
      tab?.close();
      this.error.set(this.message(e));
    }
  }

  value(event: Event): string {
    return (event.target as HTMLInputElement | HTMLTextAreaElement).value;
  }

  private async act(
    call: () => Promise<{ data?: Review; error?: unknown; response: Response }>,
    after?: () => void,
  ) {
    this.busy.set(true);
    this.error.set(null);
    try {
      this.review.set(orThrow(await call()));
      after?.();
    } catch (e) {
      this.error.set(this.message(e));
    } finally {
      this.busy.set(false);
    }
  }

  private message(e: unknown): string {
    return e instanceof ApiProblem && e.title
      ? e.title
      : this.transloco.translate('common.checkConnection');
  }
}
