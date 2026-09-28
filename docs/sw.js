/* Service worker: תמיד מנסה להביא את הגרסה העדכנית מהרשת,
   ואם אין קליטה (למשל בסופר) – משתמש בעותק השמור. */
const CACHE = 'shopping-cache';
const FILES = ['./', 'index.html', 'style.css', 'voice.js', 'app.js', 'manifest.json', 'icon.svg'];

self.addEventListener('install', e => {
  e.waitUntil(caches.open(CACHE).then(c => c.addAll(FILES)).catch(() => {}));
  self.skipWaiting();
});
self.addEventListener('activate', e => e.waitUntil(self.clients.claim()));

self.addEventListener('fetch', e => {
  const req = e.request;
  if (req.method !== 'GET' || new URL(req.url).origin !== location.origin) return;
  if (req.url.includes('version.json')) return; // תמיד מהרשת

  e.respondWith((async () => {
    const cache = await caches.open(CACHE);
    const network = fetch(req, { cache: 'no-cache' }).then(res => {
      if (res && res.ok) cache.put(req, res.clone());
      return res;
    });
    const timeout = new Promise(resolve => setTimeout(resolve, 3500));
    try {
      const res = await Promise.race([network, timeout]);
      if (res) return res;
    } catch (err) { /* offline */ }
    const cached = await cache.match(req, { ignoreSearch: true });
    if (cached) return cached;
    return network; // אין עותק שמור – מחכים לרשת
  })());
});
