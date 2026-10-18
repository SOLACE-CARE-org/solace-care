/*
 * Staff browser notifications (D47). The push service delivers Core's message encrypted to this
 * browser; the browser decrypts it before this worker sees it. Staff messages carry no personal
 * details: opening one opens the portal, where the person signs in to see who and what.
 */
self.addEventListener('push', (event) => {
  let message = { title: 'Solace', body: '' };
  try {
    message = event.data ? event.data.json() : message;
  } catch {
    // Not JSON: show nothing rather than something wrong.
    return;
  }
  event.waitUntil(
    self.registration.showNotification(message.title || 'Solace', {
      body: message.body,
      icon: 'favicon.png',
      tag: 'solace-staff',
      renotify: true,
    }),
  );
});

self.addEventListener('notificationclick', (event) => {
  event.notification.close();
  event.waitUntil(
    self.clients.matchAll({ type: 'window', includeUncontrolled: true }).then((windows) => {
      for (const w of windows) {
        if ('focus' in w) return w.focus();
      }
      return self.clients.openWindow('./');
    }),
  );
});
