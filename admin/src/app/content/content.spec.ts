import { TestBed } from '@angular/core/testing';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import type { CoreSchemas } from '../api/core-api';
import { button, fn, ok, render, route, sees, setUp } from '../testing/harness';
import { ContentEditorPage } from './content-editor.page';
import { ContentPage } from './content.page';

const crisis: CoreSchemas['ContentItem'] = {
  id: 'c1',
  slug: 'emergency-numbers-cm',
  type: 'CRISIS_RESOURCE',
  country: 'CM',
  draft: {
    titleEn: 'Emergency numbers, Cameroon',
    bodyEn: 'Call 117.',
    titleFr: "Numéros d'urgence, Cameroun",
    bodyFr: 'Appelez le 117.',
    numbers: [{ service: 'police', number: '117' }],
  },
  enDone: true,
  frDone: true,
  clinical: 'NEEDED',
  status: 'EDITED',
  version: 1,
  canPublish: false,
  canApprove: false,
  updatedAt: '2026-10-18T09:00:00Z',
};

describe('Content', () => {
  beforeEach(() => TestBed.resetTestingModule());

  it('lists items with each language and the clinical check', async () => {
    const rows: CoreSchemas['ContentRow'][] = [
      {
        id: 'c1',
        slug: 'emergency-numbers-cm',
        type: 'CRISIS_RESOURCE',
        title: 'Emergency numbers, Cameroon',
        enDone: true,
        frDone: true,
        clinical: 'APPROVED',
        status: 'EDITED',
        version: 1,
        updatedAt: '2026-10-18T09:00:00Z',
      },
      {
        id: 'a1',
        slug: 'first-session',
        type: 'ARTICLE',
        title: 'What happens in a first session',
        enDone: true,
        frDone: false,
        clinical: 'NOT_NEEDED',
        status: 'DRAFT',
        version: 0,
        updatedAt: '2026-10-14T09:00:00Z',
      },
    ];
    setUp(['CONTENT_EDITOR'], { GET: fn(route({ '/admin/v1/content': () => ok(rows) })) });
    const { root } = await render(ContentPage);
    await sees(root, 'Emergency numbers, Cameroon');
    expect(root.textContent).toContain('Edited, not published');
    expect(root.textContent).toContain('Missing');
    expect(root.textContent).toContain('Not needed');
  });

  it('blocks publishing a crisis resource until its clinical check, and sends it for one', async () => {
    const client = {
      GET: fn(route({ '/admin/v1/content/{contentId}': () => ok(crisis) })),
      POST: fn(() => ok({ ...crisis, clinical: 'REQUESTED' })),
      PUT: fn(() => ok(crisis)),
    };
    setUp(['CONTENT_EDITOR'], client);
    const { root } = await render(ContentEditorPage, { id: 'c1' });
    await sees(root, 'Clinical check needed again after edits');
    expect(button(root, 'Publish')!.disabled).toBe(true);
    button(root, 'Send for clinical check')!.click();
    await vi.waitFor(() => expect(client.POST).toHaveBeenCalled());
    expect(client.POST.mock.calls[0]![0]).toBe('/admin/v1/content/{contentId}/clinical-check');
    await sees(root, 'Waiting for a clinical reviewer');
  });

  it('lets a clinical reviewer approve but not edit', async () => {
    setUp(['CLINICAL_REVIEWER'], {
      GET: fn(
        route({
          '/admin/v1/content/{contentId}': () =>
            ok({ ...crisis, clinical: 'REQUESTED', canApprove: true }),
        }),
      ),
    });
    const { root } = await render(ContentEditorPage, { id: 'c1' });
    await sees(root, 'Approve clinical check');
    expect(button(root, 'Save')).toBeUndefined();
    expect(root.querySelector<HTMLInputElement>('input.sl-input')!.disabled).toBe(true);
  });
});
