import { ChangeDetectionStrategy, Component, computed, inject, input, output } from '@angular/core';
import { RouterLink, RouterLinkActive } from '@angular/router';
import { LucideBell, LucideBellOff, LucideDynamicIcon, LucideLogOut } from '@lucide/angular';
import { TranslocoPipe } from '@jsverse/transloco';

import { StaffSession } from '../auth/staff-session';
import { NAV, type NavGroup } from '../nav/nav';
import { StaffPush } from './staff-push';

/**
 * Sidebar (design-system components/Sidebar; screens/admin/source/Sidebar.dc.html). Shows only the
 * entries the signed-in roles may open; groups with nothing left are not shown.
 */
@Component({
  selector: 'sl-sidebar',
  imports: [RouterLink, RouterLinkActive, LucideDynamicIcon, TranslocoPipe],
  changeDetection: ChangeDetectionStrategy.OnPush,
  styleUrl: './sidebar.css',
  template: `
    <div class="brand">
      <img
        src="brand/solace-care-logo.png"
        [alt]="'common.appName' | transloco"
        width="96"
        height="32"
      />
      <span class="pill">{{ 'admin.badge' | transloco }}</span>
    </div>

    <nav [attr.aria-label]="'admin.nav.label' | transloco">
      @for (group of groups(); track group.key) {
        <div class="group">
          <span class="group-label">{{ 'admin.nav.groups.' + group.key | transloco }}</span>
          @for (entry of group.entries; track entry.key) {
            <a
              class="item"
              [routerLink]="['/', entry.path]"
              routerLinkActive="current"
              ariaCurrentWhenActive="page"
            >
              <svg [lucideIcon]="entry.icon" [size]="18" [strokeWidth]="1.8"></svg>
              <span class="item-label">{{ 'admin.nav.items.' + entry.key | transloco }}</span>
              @if (entry.key === 'crisis' && crisisCount() > 0) {
                <span
                  class="count-badge"
                  [attr.aria-label]="
                    'admin.nav.crisisUnacknowledged' | transloco: { count: crisisCount() }
                  "
                  >{{ crisisCount() }}</span
                >
              }
            </a>
          }
        </div>
      }
    </nav>

    <div class="spacer"></div>

    <div class="me">
      <div class="avatar" aria-hidden="true">{{ session.initials() }}</div>
      <div class="who">
        <span class="name">{{ session.name() }}</span>
        @if (session.primaryRole(); as role) {
          <span class="role">{{ 'admin.roles.' + role | transloco }}</span>
        }
      </div>
      @if (push.state() === 'on' || push.state() === 'off') {
        <button
          type="button"
          class="sign-out"
          [attr.aria-label]="
            (push.state() === 'on' ? 'admin.push.turnOff' : 'admin.push.turnOn') | transloco
          "
          [attr.aria-pressed]="push.state() === 'on'"
          (click)="togglePush()"
        >
          <svg
            [lucideIcon]="push.state() === 'on' ? bellIcon : bellOffIcon"
            [size]="18"
            [strokeWidth]="1.8"
          ></svg>
        </button>
      }
      <button
        type="button"
        class="sign-out"
        [attr.aria-label]="'admin.signOut' | transloco"
        (click)="signOut.emit()"
      >
        <svg [lucideIcon]="logOutIcon" [size]="18" [strokeWidth]="1.8"></svg>
      </button>
    </div>
  `,
})
export class Sidebar {
  protected readonly session = inject(StaffSession);
  protected readonly logOutIcon = LucideLogOut;
  protected readonly bellIcon = LucideBell;
  protected readonly bellOffIcon = LucideBellOff;
  protected readonly push = inject(StaffPush);

  constructor() {
    void this.push.refresh();
  }

  protected togglePush() {
    void (this.push.state() === 'on' ? this.push.turnOff() : this.push.turnOn()).catch(() =>
      this.push.refresh(),
    );
  }

  /** Unacknowledged crisis alerts; the badge shows on every page when above zero. */
  readonly crisisCount = input(0);
  readonly signOut = output<void>();

  protected readonly groups = computed<NavGroup[]>(() =>
    NAV.map((group) => ({
      key: group.key,
      entries: group.entries.filter(
        (entry) => entry.enabled && this.session.accessTo(entry.area) !== null,
      ),
    })).filter((group) => group.entries.length > 0),
  );
}
