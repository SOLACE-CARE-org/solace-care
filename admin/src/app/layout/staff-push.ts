import { inject, Injectable, signal } from '@angular/core';
import { TranslocoService } from '@jsverse/transloco';

import { CoreApi, orThrow } from '../api/core-api';

type State = 'unsupported' | 'unavailable' | 'off' | 'on' | 'blocked';

/** "BEl..." base64url as the bytes PushManager wants. */
function bytes(base64url: string): Uint8Array {
  const base64 = base64url.replace(/-/g, '+').replace(/_/g, '/');
  const raw = atob(base64 + '='.repeat((4 - (base64.length % 4)) % 4));
  return Uint8Array.from(raw, (c) => c.charCodeAt(0));
}

function base64url(buffer: ArrayBuffer | null): string {
  if (!buffer) return '';
  return btoa(String.fromCharCode(...new Uint8Array(buffer)))
    .replace(/\+/g, '-')
    .replace(/\//g, '_')
    .replace(/=+$/, '');
}

/**
 * This browser's staff notifications (D38, D47): crisis alerts for whoever is on call, refunds
 * and payout runs for Finance. Off until the staff member turns it on; the crisis SMS stays the
 * one that counts.
 */
@Injectable({ providedIn: 'root' })
export class StaffPush {
  private readonly api = inject(CoreApi);
  private readonly transloco = inject(TranslocoService);
  readonly state = signal<State>('off');

  private supported(): boolean {
    return (
      typeof navigator !== 'undefined' && 'serviceWorker' in navigator && 'PushManager' in window
    );
  }

  async refresh() {
    if (!this.supported()) {
      this.state.set('unsupported');
      return;
    }
    try {
      const setup = orThrow(await this.api.client.GET('/admin/v1/me/push'));
      if (!setup.publicKey) {
        this.state.set('unavailable');
        return;
      }
      if (Notification.permission === 'denied') {
        this.state.set('blocked');
        return;
      }
      const registration = await navigator.serviceWorker.getRegistration('staff-push-sw.js');
      const subscription = await registration?.pushManager.getSubscription();
      this.state.set(subscription ? 'on' : 'off');
    } catch {
      this.state.set('unavailable');
    }
  }

  async turnOn() {
    const setup = orThrow(await this.api.client.GET('/admin/v1/me/push'));
    if (!setup.publicKey) return;
    const permission = await Notification.requestPermission();
    if (permission !== 'granted') {
      this.state.set(permission === 'denied' ? 'blocked' : 'off');
      return;
    }
    const registration = await navigator.serviceWorker.register('staff-push-sw.js');
    const subscription = await registration.pushManager.subscribe({
      userVisibleOnly: true,
      applicationServerKey: bytes(setup.publicKey) as BufferSource,
    });
    orThrow(
      await this.api.client.PUT('/admin/v1/me/push-subscription', {
        body: {
          endpoint: subscription.endpoint,
          p256dh: base64url(subscription.getKey('p256dh')),
          auth: base64url(subscription.getKey('auth')),
          language: this.transloco.getActiveLang() === 'fr' ? 'fr' : 'en',
        },
      }),
    );
    this.state.set('on');
  }

  async turnOff() {
    const registration = await navigator.serviceWorker.getRegistration('staff-push-sw.js');
    const subscription = await registration?.pushManager.getSubscription();
    if (subscription) {
      await this.api.client.DELETE('/admin/v1/me/push-subscription', {
        params: { query: { endpoint: subscription.endpoint } },
      });
      await subscription.unsubscribe();
    }
    this.state.set('off');
  }
}
