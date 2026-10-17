import { TestBed } from '@angular/core/testing';
import { provideRouter, Router } from '@angular/router';
import { TranslocoTestingModule } from '@jsverse/transloco';
import Keycloak from 'keycloak-js';
import en from '@solace/i18n/en.json';
import fr from '@solace/i18n/fr.json';
import { beforeEach, describe, expect, it } from 'vitest';

import { routes } from '../app.routes';
import { StaffSession } from '../auth/staff-session';
import { Sidebar } from './sidebar';

function setUp(roles: string[], name = 'Nadine Ebai', language: 'en' | 'fr' = 'en') {
  TestBed.configureTestingModule({
    imports: [
      Sidebar,
      TranslocoTestingModule.forRoot({
        langs: { en, fr },
        translocoConfig: { availableLangs: ['en', 'fr'], defaultLang: language },
      }),
    ],
    // The shell signs out through Keycloak; tests never reach a real server.
    providers: [provideRouter(routes), { provide: Keycloak, useValue: { logout: () => undefined } }],
  });
  TestBed.inject(StaffSession).fromToken({
    given_name: name.split(' ')[0],
    family_name: name.split(' ')[1],
    realm_access: { roles },
  });
  const fixture = TestBed.createComponent(Sidebar);
  fixture.detectChanges();
  return fixture;
}

const labels = (root: HTMLElement) =>
  [...root.querySelectorAll('.item-label')].map((element) => element.textContent?.trim());

describe('Sidebar', () => {
  beforeEach(() => TestBed.resetTestingModule());

  it('shows a Super admin every Phase 1 entry, and no later-phase ones', () => {
    const root = setUp(['SUPER_ADMIN']).nativeElement as HTMLElement;

    expect(labels(root)).toEqual([
      'Overview',
      'Crisis queue',
      'Applications',
      'Specialist records',
      'People',
      'Bookings',
      'Reviews',
      'Payments',
      'Payout runs',
      'Catalogue',
      'Content',
      'Analytics',
      'Audit log',
      'Team and roles',
      'Settings and flags',
    ]);
  });

  it('shows Finance only its areas, with empty groups removed', () => {
    const root = setUp(['FINANCE']).nativeElement as HTMLElement;

    expect(labels(root)).toEqual([
      'Overview',
      'Applications',
      'Specialist records',
      'Bookings',
      'Payments',
      'Payout runs',
      'Catalogue',
      'Analytics',
    ]);
    expect(root.textContent).not.toContain('Crisis queue');
    expect(root.textContent).not.toContain('Team and roles');
  });

  it('shows the person, their role and a labelled sign-out button', () => {
    const root = setUp(['OPERATIONS']).nativeElement as HTMLElement;

    expect(root.querySelector('.avatar')?.textContent?.trim()).toBe('NE');
    expect(root.querySelector('.name')?.textContent?.trim()).toBe('Nadine Ebai');
    expect(root.querySelector('.role')?.textContent?.trim()).toBe('Operations');
    expect(root.querySelector('.sign-out')?.getAttribute('aria-label')).toBe('Sign out');
    expect(root.querySelector('img')?.getAttribute('alt')).toBe('Solace Care');
  });

  it('labels the crisis count for screen readers', () => {
    const fixture = setUp(['CRISIS_RESPONDER']);
    fixture.componentRef.setInput('crisisCount', 2);
    fixture.detectChanges();

    const badge = (fixture.nativeElement as HTMLElement).querySelector('.count-badge');
    expect(badge?.textContent?.trim()).toBe('2');
    expect(badge?.getAttribute('aria-label')).toBe('2 unacknowledged');
  });

  it('is in French when the portal is', () => {
    const root = setUp(['SUPER_ADMIN'], 'Nadine Ebai', 'fr').nativeElement as HTMLElement;

    expect(labels(root)).toContain('File de crise');
    expect(labels(root)).toContain('Lots de versements');
    expect(root.querySelector('.sign-out')?.getAttribute('aria-label')).toBe('Se déconnecter');
  });

  it('sends a direct link to a hidden area to No access', async () => {
    setUp(['SUPPORT']);
    const router = TestBed.inject(Router);

    await router.navigateByUrl('/team');

    expect(router.url).toBe('/no-access');
  });
});
