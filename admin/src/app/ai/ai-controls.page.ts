import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { TranslocoPipe, TranslocoService } from '@jsverse/transloco';

import { AiApi, type AiSchemas } from '../api/ai-api';
import { ApiProblem, orThrow } from '../api/core-api';
import { StaffSession } from '../auth/staff-session';

type Overview = AiSchemas['AiOverview'];
type Feature = AiSchemas['AiFeature'];
type Version = AiSchemas['PromptVersion'];
type Status = 'LIVE' | 'PASSING' | 'BLOCKED' | 'NOT_EVALUATED';

/**
 * Admin screen 26, AI controls (AIControls.dc.html; D56). A feature switches on only while its
 * live version passed in EN and FR; a new version goes live after a passing evaluation, a Clinical
 * reviewer's sign-off and promotion. Super admins change; Operations and reviewers look.
 */
@Component({
  selector: 'sl-ai-controls',
  imports: [TranslocoPipe, FormsModule, RouterLink],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './ai-controls.page.html',
  styles: `
    .subtitle {
      margin: var(--space-4) 0 var(--space-16);
      max-width: 80ch;
    }
    .table-card {
      padding: 0;
      overflow-x: auto;
    }
    .strong {
      font-weight: 700;
    }
    .link {
      background: none;
      border: 0;
      padding: 0;
      font: inherit;
      font-weight: 700;
      color: var(--brand);
      cursor: pointer;
      min-height: 44px;
      text-align: left;
    }
    tr.selected {
      background: var(--brand-tint, #e6f1ee);
    }
    input[role='switch'] {
      width: 44px;
      height: 24px;
    }
    .reason {
      margin-top: var(--space-16);
      max-width: 40rem;
    }
    .panel {
      margin-top: var(--space-16);
      display: grid;
      gap: var(--space-8);
    }
    .section {
      font-size: 1rem;
      margin: 0;
    }
    .prompt {
      white-space: pre-wrap;
      font: inherit;
      background: var(--surface-sunken, #f3f5f2);
      padding: var(--space-12);
      border-radius: 8px;
    }
    .failures {
      margin: 0;
    }
    .actions {
      display: flex;
      justify-content: end;
      gap: var(--space-8);
      flex-wrap: wrap;
    }
    .error {
      color: var(--danger);
    }
  `,
})
export class AiControlsPage {
  private readonly api = inject(AiApi);
  private readonly staff = inject(StaffSession);
  private readonly transloco = inject(TranslocoService);

  readonly overview = signal<Overview | null>(null);
  readonly selected = signal<string>('TRACKING_INSIGHTS');
  readonly reason = signal('');
  readonly showFailures = signal(false);
  readonly error = signal(false);
  readonly failure = signal<string | null>(null);
  readonly busy = signal(false);

  readonly mayChange = computed(() => this.staff.accessTo('aiControls') === 'F');
  readonly isReviewer = computed(() => this.staff.roles().includes('CLINICAL_REVIEWER'));
  readonly current = computed(
    () => this.overview()?.features.find((f) => f.feature === this.selected()) ?? null,
  );

  constructor() {
    void this.load();
  }

  async load() {
    this.error.set(false);
    try {
      this.overview.set(orThrow(await this.api.client.GET('/admin/v1/ai/features')));
    } catch {
      this.error.set(true);
    }
  }

  name(feature: string): string {
    return this.transloco.translate(`admin.ai.feature.${feature}`);
  }

  safetyLine(o: Overview): string {
    const s = o.safetyMonitor;
    if (s.recallEn === 0 && s.recallFr === 0)
      return this.transloco.translate('admin.ai.noSafetySet');
    return this.transloco.translate('admin.ai.recall', {
      en: s.recallEn.toFixed(1),
      fr: s.recallFr.toFixed(1),
      gate: s.gate,
    });
  }

  routeLine(f: Feature): string {
    return f.routes.length ? f.routes.join(' → ') : this.transloco.translate('admin.ai.noRoute');
  }

  promptLine(f: Feature): string {
    const v = f.candidate ?? f.live;
    if (!v) return this.transloco.translate('admin.ai.notApplicable');
    const line = this.transloco.translate('admin.ai.prompt', { v: v.version });
    return f.candidate ? `${line} ${this.transloco.translate('admin.ai.new')}` : line;
  }

  evalLine(v: Version | undefined): string {
    const e = v?.evaluation;
    if (!e) return this.transloco.translate('admin.ai.notRun');
    const first = e.failures[0];
    if (!e.passed && first) {
      return this.transloco.translate('admin.ai.failedLine', {
        locale: first.locale.toUpperCase(),
        reason: this.failureText(first.reason),
      });
    }
    return this.transloco.translate('admin.ai.passedLine', {
      pe: e.passedEn,
      te: e.totalEn,
      pf: e.passedFr,
      tf: e.totalFr,
    });
  }

  status(f: Feature): Status {
    if (f.enabled && f.live) return 'LIVE';
    const v = f.candidate ?? f.live;
    if (!v?.evaluation) return 'NOT_EVALUATED';
    return v.evaluation.passed ? 'PASSING' : 'BLOCKED';
  }

  switchLabel(f: Feature): string {
    return this.transloco.translate(f.enabled ? 'admin.ai.switchOff' : 'admin.ai.switchOn', {
      feature: this.name(f.feature),
    });
  }

  panelNote(f: Feature, c: Version): string {
    return f.live
      ? this.transloco.translate('admin.ai.panelNote', { live: f.live.version, v: c.version })
      : this.transloco.translate('admin.ai.panelNoteFirst');
  }

  failureText(reason: string): string {
    const key = reason.split(':')[0];
    return this.transloco.translate(`admin.ai.failure.${key}`);
  }

  async toggle(f: Feature) {
    await this.act(async () => {
      orThrow(
        await this.api.client.PUT('/admin/v1/ai/features/{feature}/switch', {
          params: { path: { feature: f.feature as never } },
          body: { enabled: !f.enabled, reason: this.reason().trim() || '—' },
        }),
      );
    });
  }

  async evaluate(v: Version) {
    await this.act(async () => {
      orThrow(
        await this.api.client.POST('/admin/v1/ai/versions/{versionId}/evaluation', {
          params: { path: { versionId: v.id } },
        }),
      );
    });
  }

  async signOff(v: Version) {
    await this.act(async () => {
      orThrow(
        await this.api.client.POST('/admin/v1/ai/versions/{versionId}/sign-off', {
          params: { path: { versionId: v.id } },
        }),
      );
    });
  }

  async promote(v: Version) {
    await this.act(async () => {
      orThrow(
        await this.api.client.POST('/admin/v1/ai/versions/{versionId}/promotion', {
          params: { path: { versionId: v.id } },
        }),
      );
    });
  }

  private async act(call: () => Promise<void>) {
    this.busy.set(true);
    this.failure.set(null);
    try {
      await call();
      await this.load();
    } catch (e) {
      this.failure.set(
        e instanceof ApiProblem && e.title
          ? e.title
          : this.transloco.translate('common.checkConnection'),
      );
      await this.load();
    } finally {
      this.busy.set(false);
    }
  }
}
