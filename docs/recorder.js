/* ===== מנוע הקלטה קולית =====
   כל ההיגיון כאן (זמני המתנה, חידוש הקשבה, בחירת שירות, תצוגה) –
   ולכן מתעדכן אוטומטית מגיטהאב בלי להתקין מחדש את האפליקציה.
   האנדרואיד רק מעביר אירועים: ready / begin / rms / partial / end / results / error */
(function () {
  'use strict';
  const A = window.Android || null;
  const hasNative = !!(A && A.voiceStart);
  const $ = id => document.getElementById(id);

  const ERR = {
    1: 'תקלת רשת (זמן)', 2: 'תקלת רשת', 3: 'המיקרופון תפוס או לא זמין', 4: 'שגיאת שרת',
    5: 'שגיאת לקוח', 6: 'לא זוהה דיבור', 7: 'לא זוהו מילים', 8: 'השירות עסוק',
    9: 'אין הרשאת מיקרופון', 10: 'יותר מדי בקשות', 11: 'השירות התנתק',
    12: 'השירות לא תומך בעברית', 13: 'העברית לא זמינה בשירות', '-2': 'לא ניתן להפעיל את השירות',
    '-3': 'אין מסך דיבור מובנה בטלפון'
  };

  /* ---------- הגדרות ---------- */
  function settings() {
    const d = { service: 'auto', startWait: 2, silence: 3 };
    return Object.assign(d, (state.settings && state.settings.voice) || {});
  }
  function saveSettings(patch) {
    state.settings = state.settings || {};
    state.settings.voice = Object.assign(settings(), patch);
    save();
  }

  let servicesCache = null;
  function services() {
    if (servicesCache) return servicesCache;
    let list = [];
    try { list = JSON.parse(A.listServices() || '[]'); } catch (e) { list = []; }
    servicesCache = list;
    return list;
  }
  const GQSB = 'com.google.android.googlequicksearchbox';
  /* "אוטומטי": רשימת שירותים לניסיון לפי הסדר. השירות הראשון שעובד נזכר (autoPick).
     סדר: מה שעבד בפעם הקודמת › אפליקציית Google › שאר שירותי Google › ברירת המחדל › השאר */
  function candidates(id) {
    if (id && id !== 'auto') return [id];
    const l = services();
    const out = [];
    const add = x => { if (x && out.indexOf(x) === -1) out.push(x); };
    const pick = settings().autoPick;
    if (pick && (pick === 'default' || l.some(s => s.id === pick))) add(pick);
    l.filter(s => s.pkg === GQSB).forEach(s => add(s.id));
    l.filter(s => s.pkg.includes('google')).forEach(s => add(s.id));
    add('default');
    l.forEach(s => add(s.id));
    return out;
  }
  function resolveService(id) { return candidates(id)[0]; }
  function serviceLabel(id) {
    if (id === 'default') return 'ברירת המחדל של הטלפון';
    if (id === 'system') return 'מסך הדיבור של הטלפון';
    const s = services().find(x => x.id === id);
    return s ? `${s.name} (${s.pkg.split('.').slice(-1)[0]})` : id;
  }

  /* ---------- סשן ---------- */
  let S = null;          // הסשן הנוכחי
  let tickTimer = null;

  function newSession(test) {
    const st = settings();
    const cands = candidates(st.service);
    return {
      test, auto: st.service === 'auto', cands, ci: 0, service: cands[0], retried10: false,
      startWait: st.startWait * 1000, silence: st.silence * 1000,
      openedAt: Date.now(), lastStart: 0, readyOnce: false,
      deadline: 0, speaking: false, active: false,
      collected: [], partial: '', restarts: 0,
      gotSound: false, gotSpeech: false, peak: -99, log: []
    };
  }

  function open(opts) {
    opts = opts || {};
    if (!hasNative) {
      // אפליקציה בגרסה ישנה – משתמשים במסך ההקלטה הישן שלה
      if (A && A.startVoice) { A.startVoice(); return; }
      return browserVoice();
    }
    close(true);
    S = newSession(!!opts.test);
    $('vpTitle').textContent = S.test ? '🎤 בדיקת הקלטה' : '🛒 מה להוסיף לרשימה?';
    $('vpDone').textContent = S.test ? 'סיום בדיקה' : 'סיום והוספה';
    $('vpHeard').textContent = '';
    setLevel(0);
    $('voicePanel').classList.remove('hidden');
    diag();
    if (!A.hasMicPermission()) {
      status('צריך אישור להשתמש במיקרופון…');
      S.waitingPermission = true;
      A.requestMicPermission();
      return;
    }
    begin();
  }

  function begin() {
    if (!S) return;
    if (S.service === 'system') {
      status('פותח את מסך הדיבור של הטלפון…');
      S.active = true;
      A.voiceSystemDialog(JSON.stringify({ lang: 'he-IL' }));
      return;
    }
    S.active = true;
    S.openedAt = Date.now();
    listen();
    clearInterval(tickTimer);
    tickTimer = setInterval(tick, 200);
  }

  function listen() {
    if (!S || !S.active) return;
    S.lastStart = Date.now();
    if (!S.readyOnce) status('מתחבר…');
    $('vpMic').classList.remove('idle');
    A.voiceStart(S.service, JSON.stringify({
      lang: 'he-IL', partial: true, silenceMs: S.silence, minMs: 3000
    }));
  }

  // מעבר לשירות הבא ברשימה (במצב אוטומטי). מחזיר false אם אין עוד
  function nextCandidate() {
    if (!S || !S.auto || S.ci + 1 >= S.cands.length) return false;
    A.voiceDestroy();
    S.ci++;
    S.service = S.cands[S.ci];
    S.readyOnce = false; S.deadline = 0; S.restarts = 0; S.retried10 = false;
    S.openedAt = Date.now();
    log('› שירות הבא');
    status('מנסה שירות דיבור אחר…');
    diag();
    setTimeout(() => { if (S && S.active) listen(); }, 900);
    return true;
  }

  // חידוש הקשבה, במרווח של שנייה לפחות מהקודם (אחרת Google חוסם – שגיאה 10)
  function restart(extraDelay) {
    if (!S || !S.active) return;
    if (S.restarts >= 8) { finish(); return; }
    S.restarts++;
    const wait = Math.max(0, 1000 - (Date.now() - S.lastStart)) + (extraDelay || 250);
    setTimeout(() => { if (S && S.active) listen(); }, wait);
  }

  function tick() {
    if (!S || !S.active) return;
    const now = Date.now();
    // השירות לא הגיב בכלל
    if (!S.readyOnce && now - S.openedAt > 5000) {
      log('אין תגובה');
      if (!nextCandidate()) stopWith('שירות הדיבור לא הגיב. נסו שירות אחר בהגדרות');
      return;
    }
    if (S.speaking) {
      if (now - S.speakStart > 25000) finish();   // הגבלת בטיחות
      return;
    }
    if (S.deadline && now >= S.deadline) { finish(); return; }
    if (S.deadline) {
      const left = Math.ceil((S.deadline - now) / 1000);
      status((fullText() ? 'ממשיך להקשיב… ' : 'מקשיב… דברו עכשיו ') + `(${left})`);
    }
  }

  function fullText() {
    return S.collected.concat(S.partial ? [S.partial] : []).join(' ').trim();
  }

  function finish() {
    if (!S) return;
    const text = fullText();
    S.active = false;
    clearInterval(tickTimer);
    A.voiceCancel();
    setLevel(0);
    $('vpMic').classList.add('idle');
    if (!text) { status('לא נקלט דיבור. לחצו על 🎤 לנסות שוב'); diag(); return; }
    if (S.test) {
      $('vpHeard').textContent = text;
      status('✓ נקלט בהצלחה. לחצו על 🎤 לבדיקה נוספת');
      diag();
      return;
    }
    close();
    window.handleVoiceText(text);
  }

  function stopWith(msg) {
    if (!S) return;
    S.active = false;
    clearInterval(tickTimer);
    A.voiceCancel();
    setLevel(0);
    $('vpMic').classList.add('idle');
    status(msg);
    diag();
  }

  function close(silent) {
    clearInterval(tickTimer);
    if (S && S.active && hasNative) A.voiceCancel();
    S = null;
    $('voicePanel').classList.add('hidden');
  }

  /* ---------- אירועים מהאנדרואיד ---------- */
  window.onVoiceEvent = function (ev) {
    if (!S) return;
    const t = ev.type, v = ev.value, now = Date.now();
    if (t !== 'rms') log(t === 'error' ? 'שגיאה ' + v : t);

    if (t === 'permission') {
      S.waitingPermission = false;
      if (v) begin(); else status('בלי אישור מיקרופון אי אפשר להקליט. אפשר לאשר בהגדרות הטלפון');
      return;
    }
    if (t === 'sysresult') {
      S.active = false;
      if (v) { S.collected = [v]; finish(); } else status('בוטל. לחצו על 🎤 לנסות שוב');
      return;
    }
    if (!S.active) return;

    switch (t) {
      case 'ready':
        if (!S.readyOnce) {
          S.readyOnce = true;
          // זוכרים את השירות שעובד
          if (S.auto && settings().autoPick !== S.service) saveSettings({ autoPick: S.service });
        }
        if (!S.deadline) S.deadline = now + S.startWait;
        break;
      case 'begin':
        S.speaking = true; S.speakStart = now; S.gotSpeech = true;
        status('מקשיב…');
        break;
      case 'rms':
        setLevel(v);
        if (v > S.peak) S.peak = v;
        if (!S.gotSound && v > 3) { S.gotSound = true; }
        break;
      case 'partial':
        if (v && v.trim() && v.trim() !== S.partial) {
          S.partial = v.trim();
          S.deadline = now + S.silence;
          $('vpHeard').textContent = fullText();
        }
        break;
      case 'end':
        S.speaking = false;
        S.deadline = now + S.silence;
        status('מעבד…');
        break;
      case 'results': {
        S.speaking = false;
        const txt = (v || '').trim() || S.partial;
        S.partial = '';
        if (txt) { S.collected.push(txt); $('vpHeard').textContent = fullText(); }
        S.deadline = now + S.silence;
        restart(300);          // ממשיכים להקשיב למקרה שיש המשך
        break;
      }
      case 'error': {
        S.speaking = false;
        setLevel(0);
        if (S.partial) { S.collected.push(S.partial); S.partial = ''; }
        // השירות נפל לפני שהתחיל להקשיב – מנסים שוב פעם אחת, ואז עוברים לשירות הבא
        if (!S.readyOnce && [3, 4, 5, 8, 10, 11, 12, 13, -2].indexOf(v) !== -1) {
          if ([10, 11, 5, 8].indexOf(v) !== -1 && !S.retried10) {
            S.retried10 = true;
            A.voiceDestroy();
            restart(v === 10 ? 2500 : 800);
            break;
          }
          if (nextCandidate()) break;
        }
        const soft = [5, 6, 7, 8, 11].indexOf(v) !== -1;
        const timeLeft = !S.deadline || now < S.deadline;
        if ([5, 8, 11].indexOf(v) !== -1) A.voiceDestroy();   // שירות תקוע – יוצרים חדש
        if (v === 10 && S.restarts < 8) { restart(2500); break; }
        if (soft && timeLeft) { if (!S.deadline) S.deadline = now + S.startWait; restart(300); break; }
        if (soft || S.collected.length) { finish(); break; }
        let msg = 'שגיאה: ' + (ERR[v] || v);
        if (v === 12 || v === 13 || v === -2) msg += '. בחרו שירות אחר בהגדרות';
        if (v === 1 || v === 2) msg = 'אין חיבור לאינטרנט. לחצו על 🎤 לנסות שוב';
        stopWith(msg);
        break;
      }
      case 'cancelled':
        stopWith('ההקשבה נעצרה. לחצו על 🎤 לנסות שוב');
        break;
    }
    if (t !== 'rms') diag();
    else if (S.gotSound && !S.shownSound) { S.shownSound = true; diag(); }
  };

  /* ---------- תצוגה ---------- */
  function status(t) { $('vpStatus').textContent = t; }
  function setLevel(rms) {
    const p = Math.max(0, Math.min(100, (rms + 2) * 8.5));
    $('vpLevel').style.width = p + '%';
    $('vpMic').style.transform = `scale(${1 + p / 400})`;
  }
  function log(e) {
    if (!S) return;
    S.log.push(e);
    if (S.log.length > 6) S.log.shift();
  }
  function diag() {
    if (!S) return;
    const ok = b => (b ? '✓' : '—');
    $('vpDiag').innerHTML =
      `שירות: ${esc(serviceLabel(S.service))}<br>` +
      `מוכן ${ok(S.readyOnce)} · קול נקלט ${ok(S.gotSound)} · דיבור זוהה ${ok(S.gotSpeech)}<br>` +
      `<span style="opacity:.7">${esc(S.log.join(' › '))}</span>`;
  }
  function esc(s) { return String(s).replace(/[&<>]/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;' }[c])); }

  /* ---------- כפתורים ---------- */
  $('vpMic').addEventListener('click', () => {
    if (!S) return;
    if (S.active) {
      // סיום מיידי: אם כבר נשמע משהו – מסיימים, אחרת מבקשים תוצאה סופית
      if (fullText()) finish();
      else { A.voiceStop(); S.deadline = Date.now() + 1500; }
    } else {
      const test = S.test;
      open({ test });
    }
  });
  $('vpDone').addEventListener('click', () => { if (S && S.active) finish(); else close(); });
  $('vpCancel').addEventListener('click', () => close());

  /* ---------- מסך הגדרות ---------- */
  function fillSettings() {
    const sel = $('voiceService');
    if (!sel) return;
    const st = settings();
    let html = '';
    if (hasNative) {
      const auto = resolveService('auto');
      html += `<option value="auto">אוטומטי – מנסה עד שמוצא שירות שעובד${settings().autoPick ? ' (כרגע: ' + esc(serviceLabel(auto)) + ')' : ''}</option>`;
      html += `<option value="default">ברירת המחדל של הטלפון</option>`;
      services().forEach(s => { html += `<option value="${esc(s.id)}">${esc(s.name)} – ${esc(s.pkg)}</option>`; });
      html += `<option value="system">מסך הדיבור של הטלפון</option>`;
    } else {
      html = '<option value="auto">דפדפן</option>';
    }
    sel.innerHTML = html;
    sel.value = st.service;
    if (sel.value !== st.service) sel.value = 'auto';
    $('voiceStartWait').value = String(st.startWait);
    $('voiceSilence').value = String(st.silence);
  }
  $('voiceService').addEventListener('change', e => saveSettings({ service: e.target.value }));
  $('voiceStartWait').addEventListener('change', e => saveSettings({ startWait: +e.target.value }));
  $('voiceSilence').addEventListener('change', e => saveSettings({ silence: +e.target.value }));
  $('voiceTestBtn').addEventListener('click', () => open({ test: true }));

  /* ---------- דפדפן רגיל (לא באפליקציה) ---------- */
  function browserVoice() {
    const SR = window.SpeechRecognition || window.webkitSpeechRecognition;
    if (!SR) { toast('הזנה קולית זמינה באפליקציה או בדפדפן כרום'); return; }
    const rec = new SR();
    rec.lang = 'he-IL';
    rec.onresult = e => window.handleVoiceText(e.results[0][0].transcript);
    rec.onerror = () => toast('לא נקלט קול, נסו שוב');
    rec.start();
  }

  // פתיחה מהווידג'ט
  window.onNativeAction = function (a) { if (a === 'voice') open(); };
  if (hasNative && A.getLaunchAction) {
    const a = A.getLaunchAction();
    if (a) setTimeout(() => window.onNativeAction(a), 300);
  }

  // מעבר חד-פעמי למצב אוטומטי (גרסה 2.0.1)
  try {
    if (!localStorage.getItem('voiceAuto201')) {
      localStorage.setItem('voiceAuto201', '1');
      if (hasNative && settings().service !== 'auto') saveSettings({ service: 'auto' });
    }
  } catch (e) { /* ignore */ }

  window.Recorder = {
    open, close, fillSettings,
    isOpen: () => !$('voicePanel').classList.contains('hidden')
  };
  fillSettings();
})();
