/* ===== רשימת קניות – לוגיקה ראשית ===== */
'use strict';

const APP_VERSION = '1.0.0';          // להעלות בכל עדכון (יחד עם version.json)
const STORE_KEY = 'shoppingList.v1';
const MAX_HISTORY = 60;

const $ = id => document.getElementById(id);
const native = window.Android || null;
const { parseVoice, sameItem } = window.Voice;

/* ---------- State ---------- */
let state = loadState();

function newList(items) {
  return { id: uid(), created: Date.now(), items: items || [] };
}
function uid() { return Date.now().toString(36) + Math.random().toString(36).slice(2, 7); }

function loadState() {
  try {
    const s = JSON.parse(localStorage.getItem(STORE_KEY));
    if (s && s.current && Array.isArray(s.history)) return s;
  } catch (e) { /* ignore */ }
  return { current: newList(), history: [], hideSuggest: false };
}
function save() {
  try { localStorage.setItem(STORE_KEY, JSON.stringify(state)); } catch (e) { /* ignore */ }
  if (native && native.updateWidget) {
    try { native.updateWidget(state.current.items.filter(i => !i.checked).length); } catch (e) { /* ignore */ }
  }
}

/* ---------- Item operations ---------- */
function findItem(name) {
  return state.current.items.find(i => sameItem(i.name, name));
}

function addItems(names) {
  const added = [], existing = [];
  names.forEach(raw => {
    const name = window.Voice.clean(raw);
    if (!name) return;
    const ex = findItem(name);
    if (ex) {
      if (ex.checked) { ex.checked = false; added.push(ex.name); } else existing.push(ex.name);
      return;
    }
    state.current.items.push({ id: uid(), name, checked: false, carried: false, added: Date.now() });
    added.push(name);
  });
  save();
  render();
  return { added, existing };
}

function removeItem(id, withUndo) {
  const idx = state.current.items.findIndex(i => i.id === id);
  if (idx === -1) return;
  const [item] = state.current.items.splice(idx, 1);
  save();
  render();
  if (withUndo) {
    toast(`"${item.name}" הוסר`, 'בטל', () => {
      state.current.items.splice(Math.min(idx, state.current.items.length), 0, item);
      save(); render();
    });
  }
}

function toggleItem(id) {
  const it = state.current.items.find(i => i.id === id);
  if (!it) return;
  it.checked = !it.checked;
  if (navigator.vibrate) { try { navigator.vibrate(15); } catch (e) { /* ignore */ } }
  save();
  render();
}

/* ---------- Suggestions ---------- */
function getSuggestions() {
  const counts = new Map();
  state.history.slice(0, 12).forEach((list, li) => {
    const seen = new Set();
    list.items.forEach(it => {
      const key = window.Voice.itemKey(it.name);
      if (seen.has(key)) return;
      seen.add(key);
      const c = counts.get(key) || { name: it.name, count: 0, recent: 99 };
      c.count += 1;
      c.recent = Math.min(c.recent, li);
      counts.set(key, c);
    });
  });
  return [...counts.values()]
    .filter(c => !findItem(c.name))
    .sort((a, b) => b.count - a.count || a.recent - b.recent)
    .slice(0, 16);
}

/* ---------- Finish shopping ---------- */
function finishShopping() {
  const items = state.current.items;
  const missing = items.filter(i => !i.checked);
  const bought = items.filter(i => i.checked);
  let body = `<p>נקנו <b>${bought.length}</b> פריטים.</p>`;
  if (missing.length) {
    body += `<p>לא סומנו (לא נכנסו לעגלה) – <b>יעברו אוטומטית לרשימה הבאה</b>:</p>
      <ul class="modal-list">${missing.map(i => `<li>✗ ${esc(i.name)}</li>`).join('')}</ul>`;
  } else {
    body += '<p>כל הפריטים נקנו 🎉</p>';
  }
  openModal('לסיים את הקנייה?', body, 'סיים ושמור', () => {
    state.history.unshift({
      id: state.current.id,
      created: state.current.created,
      finished: Date.now(),
      items: items.map(i => ({ name: i.name, checked: i.checked }))
    });
    state.history = state.history.slice(0, MAX_HISTORY);
    state.current = newList(missing.map(i => ({
      id: uid(), name: i.name, checked: false, carried: true, added: Date.now()
    })));
    save();
    render();
    toast(missing.length ? `נשמר! ${missing.length} פריטים חסרים הועברו לרשימה החדשה` : 'נשמר! רשימה חדשה נפתחה');
  });
}

/* ---------- Voice ---------- */
function handleVoiceText(text, quiet) {
  const { action, items } = parseVoice(text);
  if (!items.length) { if (!quiet) toast(`לא הבנתי: "${text}"`); return ''; }
  let msg = '';
  if (action === 'remove') {
    const done = [];
    items.forEach(n => {
      const it = findItem(n);
      if (it) { state.current.items = state.current.items.filter(x => x !== it); done.push(it.name); }
    });
    save(); render();
    msg = done.length ? `הוסר: ${done.join(', ')}` : `לא נמצא ברשימה: ${items.join(', ')}`;
  } else if (action === 'check') {
    const done = [];
    items.forEach(n => {
      const it = findItem(n);
      if (it) { it.checked = true; done.push(it.name); }
    });
    save(); render();
    msg = done.length ? `סומן בעגלה: ${done.join(', ')}` : `לא נמצא ברשימה: ${items.join(', ')}`;
  } else {
    const r = addItems(items);
    const parts = [];
    if (r.added.length) parts.push(`נוסף: ${r.added.join(', ')}`);
    if (r.existing.length) parts.push(`כבר ברשימה: ${r.existing.join(', ')}`);
    msg = parts.join(' · ');
  }
  if (!quiet) toast(msg);
  return msg;
}

function startVoice() {
  if (native && native.startVoice) { native.startVoice(); return; }
  const SR = window.SpeechRecognition || window.webkitSpeechRecognition;
  if (!SR) { toast('הזנה קולית זמינה באפליקציה או בדפדפן כרום'); return; }
  const rec = new SR();
  rec.lang = 'he-IL';
  rec.interimResults = false;
  rec.maxAlternatives = 1;
  $('micBtn').classList.add('listening');
  rec.onresult = e => handleVoiceText(e.results[0][0].transcript);
  rec.onerror = () => toast('לא נקלט קול, נסו שוב');
  rec.onend = () => $('micBtn').classList.remove('listening');
  rec.start();
}
// נקרא מהאפליקציה (אנדרואיד) אחרי זיהוי דיבור
window.onVoiceResult = text => handleVoiceText(text);

function pullPendingFromWidget() {
  if (!native || !native.takePendingItems) return;
  let arr = [];
  try { arr = JSON.parse(native.takePendingItems() || '[]'); } catch (e) { arr = []; }
  if (!arr.length) return;
  const msgs = arr.map(t => handleVoiceText(t, true)).filter(Boolean);
  if (msgs.length) toast('מהווידג׳ט – ' + msgs.join(' · '));
}

/* ---------- Updates ---------- */
let lastCheck = 0;
async function checkForUpdate(manual) {
  if (!manual && Date.now() - lastCheck < 60000) return;
  lastCheck = Date.now();
  if (manual) $('updateStatus').textContent = 'בודק…';
  try {
    const res = await fetch('version.json?t=' + Date.now(), { cache: 'no-store' });
    const v = await res.json();
    // עדכון מעטפת האפליקציה (נדיר)
    if (native && native.getNativeVersion && v.nativeVersion > native.getNativeVersion()) {
      $('apkUpdateBtn').classList.remove('hidden');
      $('apkUpdateBtn').dataset.url = v.apkUrl;
      if (!manual) toast('יש עדכון לאפליקציה – ראו בהגדרות');
    }
    if (v.version !== APP_VERSION) {
      const key = 'reloadedFor';
      if (!manual && sessionStorage.getItem(key) === v.version) return; // מניעת לולאה
      sessionStorage.setItem(key, v.version);
      if (manual) $('updateStatus').textContent = `נמצאה גרסה ${v.version}, מעדכן…`;
      await clearCaches();
      location.reload();
    } else if (manual) {
      $('updateStatus').textContent = 'יש לך את הגרסה האחרונה ✓';
    }
  } catch (e) {
    if (manual) $('updateStatus').textContent = 'אין חיבור לאינטרנט, נסו שוב מאוחר יותר';
  }
}
async function clearCaches() {
  try {
    if (window.caches) {
      const keys = await caches.keys();
      await Promise.all(keys.map(k => caches.delete(k)));
    }
    if (navigator.serviceWorker) {
      const regs = await navigator.serviceWorker.getRegistrations();
      await Promise.all(regs.map(r => r.update().catch(() => {})));
    }
  } catch (e) { /* ignore */ }
}

/* ---------- Share / backup ---------- */
function shareText(text, title) {
  if (native && native.shareText) { native.shareText(text); return; }
  if (navigator.share) { navigator.share({ title, text }).catch(() => {}); return; }
  if (navigator.clipboard) navigator.clipboard.writeText(text).then(() => toast('הועתק ללוח'));
}
function shareList() {
  const todo = state.current.items.filter(i => !i.checked);
  if (!todo.length) { toast('אין פריטים לשתף'); return; }
  shareText('🛒 רשימת קניות\n' + todo.map(i => '• ' + i.name).join('\n'), 'רשימת קניות');
}
function exportBackup() {
  const data = JSON.stringify({ app: 'ShoppingList', v: 1, date: new Date().toISOString(), state });
  shareText(data, 'גיבוי רשימת קניות');
}
function importBackup() {
  openModal('שחזור מגיבוי',
    '<p class="muted">הדביקו כאן את טקסט הגיבוי:</p><textarea id="importText" rows="6"></textarea>',
    'שחזר', () => {
      try {
        const obj = JSON.parse($('importText').value);
        const s = obj.state || obj;
        if (!s.current || !Array.isArray(s.history)) throw new Error();
        state = s; save(); render(); toast('הגיבוי שוחזר ✓');
      } catch (e) { toast('טקסט הגיבוי לא תקין'); }
    });
}

/* ---------- Rendering ---------- */
function esc(s) {
  return String(s).replace(/[&<>"']/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
}
function fmtDate(ts, withDay) {
  const opts = withDay ? { weekday: 'long', day: 'numeric', month: 'numeric', year: '2-digit' } : { day: 'numeric', month: 'numeric' };
  return new Date(ts).toLocaleDateString('he-IL', opts);
}

function itemHtml(i) {
  return `<li class="item ${i.checked ? 'checked' : ''}" data-id="${i.id}">
    <span class="check">${i.checked ? '✓' : ''}</span>
    <span class="name">${esc(i.name)}${i.carried && !i.checked ? '<span class="badge">חסר בפעם הקודמת</span>' : ''}</span>
    <button class="remove" data-remove="${i.id}" aria-label="הסר">✕</button>
  </li>`;
}

function render() {
  const items = state.current.items;
  const todo = items.filter(i => !i.checked);
  const cart = items.filter(i => i.checked);

  $('listDate').textContent = fmtDate(state.current.created);
  $('todoList').innerHTML = todo.map(itemHtml).join('');
  $('cartList').innerHTML = cart.map(itemHtml).join('');
  $('cartTitle').classList.toggle('hidden', !cart.length);
  $('cartTitle').textContent = `✓ בעגלה (${cart.length})`;
  $('emptyState').classList.toggle('hidden', items.length > 0);
  $('finishBtn').classList.toggle('hidden', !items.length);
  $('shareBtn').classList.toggle('hidden', !todo.length);
  $('clearBtn').classList.toggle('hidden', !items.length);

  $('progressWrap').classList.toggle('hidden', !items.length);
  $('progressText').textContent = todo.length
    ? `${cart.length} מתוך ${items.length} בעגלה · נשארו ${todo.length}`
    : `הכל בעגלה! (${items.length}) 🎉`;
  $('progressBar').style.width = items.length ? (cart.length / items.length * 100) + '%' : '0';

  const sug = getSuggestions();
  $('suggestBox').classList.toggle('hidden', !sug.length);
  $('suggestChips').classList.toggle('hidden', !!state.hideSuggest);
  $('suggestToggle').textContent = state.hideSuggest ? 'הצג' : 'הסתר';
  $('suggestChips').innerHTML = sug.map(s =>
    `<button class="chip" data-suggest="${esc(s.name)}">＋ ${esc(s.name)}${s.count > 1 ? `<small>×${s.count}</small>` : ''}</button>`
  ).join('');

  renderHistory();
}

function renderHistory() {
  const h = state.history;
  $('historyEmpty').classList.toggle('hidden', h.length > 0);
  $('historyList').innerHTML = h.map(l => {
    const bought = l.items.filter(i => i.checked).length;
    const missing = l.items.length - bought;
    return `<li class="hist" data-hid="${l.id}">
      <div class="hist-head" data-open="${l.id}">
        <div><b>${fmtDate(l.finished || l.created, true)}</b>
        <div class="muted">${l.items.length} פריטים · נקנו ${bought}${missing ? ` · <span class="miss">חסרו ${missing}</span>` : ''}</div></div>
        <span class="chev">▾</span>
      </div>
      <div class="hist-body">
        <ul>${l.items.map(i => `<li class="${i.checked ? 'ok' : 'no'}">${i.checked ? '✓' : '✗'} ${esc(i.name)}</li>`).join('')}</ul>
        <div class="hist-actions">
          <button class="btn-secondary" data-copyall="${l.id}">הוסף הכל לרשימה</button>
          ${missing ? `<button class="btn-secondary" data-copymiss="${l.id}">הוסף רק מה שחסר</button>` : ''}
          <button class="btn-ghost danger" data-delhist="${l.id}">מחק</button>
        </div>
      </div>
    </li>`;
  }).join('');
}

/* ---------- UI helpers ---------- */
let toastTimer;
function toast(text, actionLabel, action) {
  $('toastText').textContent = text;
  const btn = $('toastAction');
  if (actionLabel) {
    btn.textContent = actionLabel;
    btn.classList.remove('hidden');
    btn.onclick = () => { action(); hideToast(); };
  } else btn.classList.add('hidden');
  $('toast').classList.remove('hidden');
  clearTimeout(toastTimer);
  toastTimer = setTimeout(hideToast, actionLabel ? 5000 : 3200);
}
function hideToast() { $('toast').classList.add('hidden'); }

let modalOk = null;
function openModal(title, bodyHtml, okLabel, onOk) {
  $('modalTitle').textContent = title;
  $('modalBody').innerHTML = bodyHtml;
  $('modalOk').textContent = okLabel || 'אישור';
  modalOk = onOk;
  $('modal').classList.remove('hidden');
}
function closeModal() { $('modal').classList.add('hidden'); modalOk = null; }

function showView(name) {
  document.querySelectorAll('.view').forEach(v => v.classList.toggle('active', v.id === 'view-' + name));
  document.querySelectorAll('.tab').forEach(t => t.classList.toggle('active', t.dataset.view === name));
  window.scrollTo(0, 0);
}

// נקרא מכפתור "חזור" של אנדרואיד. מחזיר true אם טופל
window.onBack = function () {
  if (!$('modal').classList.contains('hidden')) { closeModal(); return true; }
  if (!$('view-list').classList.contains('active')) { showView('list'); return true; }
  return false;
};
// נקרא כשהאפליקציה חוזרת לחזית
window.onNativeResume = function () {
  pullPendingFromWidget();
  checkForUpdate(false);
};

/* ---------- Events ---------- */
$('addForm').addEventListener('submit', e => {
  e.preventDefault();
  const v = $('addInput').value.trim();
  if (!v) return;
  const r = addItems(v.split(/\s*,\s*/));
  if (r.existing.length) toast(`כבר ברשימה: ${r.existing.join(', ')}`);
  $('addInput').value = '';
});
$('micBtn').addEventListener('click', startVoice);

['todoList', 'cartList'].forEach(id => $(id).addEventListener('click', e => {
  const rm = e.target.closest('[data-remove]');
  if (rm) { removeItem(rm.dataset.remove, true); return; }
  const li = e.target.closest('.item');
  if (li) toggleItem(li.dataset.id);
}));

$('suggestChips').addEventListener('click', e => {
  const c = e.target.closest('[data-suggest]');
  if (c) addItems([c.dataset.suggest]);
});
$('suggestToggle').addEventListener('click', () => { state.hideSuggest = !state.hideSuggest; save(); render(); });

$('finishBtn').addEventListener('click', finishShopping);
$('shareBtn').addEventListener('click', shareList);
$('clearBtn').addEventListener('click', () => openModal('לנקות את הרשימה?', '<p>כל הפריטים ברשימה הנוכחית יימחקו (הרשימות הקודמות נשארות).</p>', 'נקה', () => {
  state.current.items = []; save(); render();
}));

$('historyList').addEventListener('click', e => {
  const t = e.target;
  const find = id => state.history.find(l => l.id === id);
  if (t.closest('[data-open]')) { t.closest('.hist').classList.toggle('open'); return; }
  if (t.dataset.copyall) { const r = addItems(find(t.dataset.copyall).items.map(i => i.name)); toast(`נוספו ${r.added.length} פריטים לרשימה`); return; }
  if (t.dataset.copymiss) { const r = addItems(find(t.dataset.copymiss).items.filter(i => !i.checked).map(i => i.name)); toast(`נוספו ${r.added.length} פריטים לרשימה`); return; }
  if (t.dataset.delhist) {
    const id = t.dataset.delhist;
    openModal('למחוק רשימה זו?', '<p>הרשימה תימחק מההיסטוריה.</p>', 'מחק', () => {
      state.history = state.history.filter(l => l.id !== id); save(); render();
    });
  }
});

document.querySelectorAll('.tab').forEach(t => t.addEventListener('click', () => showView(t.dataset.view)));
$('modalOk').addEventListener('click', () => { const f = modalOk; closeModal(); if (f) f(); });
$('modalCancel').addEventListener('click', closeModal);
$('modal').addEventListener('click', e => { if (e.target.id === 'modal') closeModal(); });

$('checkUpdateBtn').addEventListener('click', () => checkForUpdate(true));
$('apkUpdateBtn').addEventListener('click', e => {
  const url = e.currentTarget.dataset.url;
  if (native && native.openUrl) native.openUrl(url); else window.open(url, '_blank');
});
$('exportBtn').addEventListener('click', exportBackup);
$('importBtn').addEventListener('click', importBackup);

document.addEventListener('visibilitychange', () => {
  if (document.visibilityState === 'visible') { pullPendingFromWidget(); checkForUpdate(false); }
});

/* ---------- Init ---------- */
$('webVersion').textContent = APP_VERSION;
if (native && native.getNativeVersion) {
  $('nativeRow').classList.remove('hidden');
  $('nativeVersion').textContent = native.getNativeVersion();
}
render();
save();
pullPendingFromWidget();
checkForUpdate(false);

if ('serviceWorker' in navigator) {
  navigator.serviceWorker.register('sw.js').catch(() => {});
}
