import { provideZonelessChangeDetection, type Type } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter, type Routes } from '@angular/router';
import { TranslocoTestingModule } from '@jsverse/transloco';
import en from '@solace/i18n/en.json';
import fr from '@solace/i18n/fr.json';
import { vi } from 'vitest';

import { CoreApi } from '../api/core-api';
import { AiApi } from '../api/ai-api';
import { TrackingApi } from '../api/tracking-api';
import { StaffSession } from '../auth/staff-session';

/** Shared set-up for the admin screen specs: a stubbed Core client and a signed-in staff member. */

// eslint-disable-next-line @typescript-eslint/no-explicit-any
export type AnyFn = (...args: any[]) => any;

/** A stub for one method of the generated client; tests read its calls. */
export const fn = (impl: AnyFn = () => undefined) => vi.fn<AnyFn>(impl);

export const ok = <T>(data: T, status = 200) =>
  Promise.resolve({ data, response: new Response(null, { status }) });

export const problem = (status: number, code: string, title: string) =>
  Promise.resolve({
    error: { code, title, status },
    response: new Response(null, { status }),
  });

export type Client = Record<string, ReturnType<typeof fn>>;

/** Answers by path: `route({ '/admin/v1/x': () => ok(...) })`. Unknown paths answer 404. */
export function route(answers: Record<string, AnyFn>): AnyFn {
  return (path: string, init: unknown) =>
    answers[path] ? answers[path]!(init) : problem(404, 'not-found', 'Not found');
}

export function setUp(
  roles: string[],
  client: Client,
  options: {
    sub?: string;
    routes?: Routes;
    extra?: Partial<CoreApi>;
    tracking?: Client;
    ai?: Client;
  } = {},
) {
  TestBed.configureTestingModule({
    imports: [
      TranslocoTestingModule.forRoot({
        langs: { en, fr },
        translocoConfig: { availableLangs: ['en', 'fr'], defaultLang: 'en' },
      }),
    ],
    providers: [
      provideZonelessChangeDetection(),
      provideRouter(options.routes ?? []),
      { provide: CoreApi, useValue: { client, ...options.extra } },
      { provide: AiApi, useValue: { client: options.ai ?? {} } },
      {
        provide: TrackingApi,
        useValue: { client: options.tracking ?? {}, imageUrl: (id: string) => `/images/${id}` },
      },
    ],
  });
  TestBed.inject(StaffSession).fromToken({
    sub: options.sub ?? 'staff-1',
    name: 'Nadine Ebai',
    realm_access: { roles },
  });
}

export async function render<T>(component: Type<T>, inputs: Record<string, unknown> = {}) {
  const fixture = TestBed.createComponent(component);
  for (const [key, value] of Object.entries(inputs)) fixture.componentRef.setInput(key, value);
  await settle(fixture);
  return {
    root: fixture.nativeElement as HTMLElement,
    fixture,
    settle: () => settle(fixture),
  };
}

async function settle(fixture: { whenStable(): Promise<unknown>; detectChanges(): void }) {
  await fixture.whenStable();
  fixture.detectChanges();
  await fixture.whenStable();
}

export const button = (root: HTMLElement, label: string) =>
  [...root.querySelectorAll<HTMLButtonElement>('button')].find(
    (b) => b.textContent?.trim() === label,
  );

/** Waits until the element contains the text (async loads and re-renders). */
export async function sees(root: HTMLElement, text: string) {
  await vi.waitFor(() => {
    if (!root.textContent?.includes(text)) throw new Error(`"${text}" not on screen`);
  });
}

/** Native dialogs have no implementation under jsdom. */
export function stubDialogs(root: HTMLElement) {
  for (const dialog of root.querySelectorAll('dialog')) {
    dialog.showModal = vi.fn();
    dialog.close = vi.fn();
  }
}
