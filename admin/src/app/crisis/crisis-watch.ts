import { DestroyRef, inject, Injectable, signal } from '@angular/core';

import { CoreApi, type CoreSchemas, orThrow } from '../api/core-api';
import { StaffSession } from '../auth/staff-session';

/** How often the red badge looks again; the crisis queue page also refreshes it after each action. */
export const CRISIS_POLL_MS = 20_000;

/**
 * Open and unacknowledged crisis alerts, for the red badge on every page (admin spec, Site map).
 * Only staff who may see the crisis queue ask; for everyone else the badge stays at zero.
 */
@Injectable({ providedIn: 'root' })
export class CrisisWatch {
  private readonly api = inject(CoreApi);
  private readonly staff = inject(StaffSession);

  readonly summary = signal<CoreSchemas['CrisisSummary'] | null>(null);
  private timer: ReturnType<typeof setInterval> | undefined;

  /** Starts polling for the lifetime of the caller (the shell). */
  start(destroy: DestroyRef) {
    if (this.timer || this.staff.accessTo('crisis') === null) return;
    void this.refresh();
    this.timer = setInterval(() => void this.refresh(), CRISIS_POLL_MS);
    destroy.onDestroy(() => {
      clearInterval(this.timer);
      this.timer = undefined;
    });
  }

  async refresh() {
    if (this.staff.accessTo('crisis') === null) return;
    try {
      this.summary.set(orThrow(await this.api.client.GET('/admin/v1/crisis/summary')));
    } catch {
      // Keep the last count; the next poll tries again.
    }
  }

  unacknowledged(): number {
    return this.summary()?.unacknowledged ?? 0;
  }
}
