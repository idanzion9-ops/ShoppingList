/* ===== ניתוח פקודות קוליות בעברית ===== */
(function (global) {
  'use strict';

  const ADD = ['תוסיף', 'תוסיפי', 'תוסיפו', 'הוסף', 'הוסיפי', 'הוסיפו', 'להוסיף', 'תכניס', 'תכניסי',
    'הכנס', 'הכניסי', 'להכניס', 'תרשום', 'תרשמי', 'רשום', 'רשמי', 'לרשום', 'תשים', 'תשימי', 'שים', 'שימי',
    'צריך', 'צריכה', 'צריכים', 'חסר', 'חסרה', 'חסרים', 'לקנות', 'תקנה', 'קנה', 'תזכיר', 'תזכירי', 'להזכיר'];
  const REMOVE = ['תמחק', 'תמחקי', 'תמחקו', 'מחק', 'מחקי', 'למחוק', 'תסיר', 'תסירי', 'הסר', 'הסירי', 'להסיר',
    'תוריד', 'תורידי', 'הורד', 'הורידי', 'להוריד', 'בטל', 'תבטל', 'תבטלי'];
  const CHECK = ['סימנתי', 'סמן', 'סמני', 'תסמן', 'תסמני', 'לסמן', 'לקחתי', 'קניתי', 'שמתי', 'הכנסתי'];
  const FILLER = ['בבקשה', 'גם', 'את', 'לי', 'לנו', 'אפשר', 'תוכל', 'תוכלי', 'בוא', 'בואי', 'נא', 'היי', 'הי',
    'רק', 'עוד', 'לרשימה', 'לרשימת', 'ברשימה', 'מהרשימה', 'הקניות', 'קניות', 'לעגלה', 'בעגלה', 'לסל', 'בסל',
    'של', 'ש', 'אז', 'טוב', 'אוקיי', 'ok', 'ו'];
  const TAIL = ['בבקשה', 'תודה', 'לרשימה', 'ברשימה', 'מהרשימה', 'לרשימת', 'הקניות', 'קניות', 'לעגלה', 'בעגלה', 'לסל', 'בסל', 'גם'];
  // מילים שמתחילות ב-ו ואינן "ו" החיבור
  const VAV_WORDS = ['וניל', 'ויסקי', 'ורדים', 'ורד', 'ויטמין', 'ויטמינים', 'ופל', 'ופלים', 'וופל', 'וופלים',
    'וודקה', 'ואפל', 'וסלין', 'ווקמן', 'ויאגרה', 'וורצ\'סטר', 'ויניגרט', 'ווק'];

  function clean(text) {
    return String(text || '')
      .replace(/[.!?;:"״“”]/g, ' ')
      .replace(/\s+/g, ' ')
      .trim();
  }

  function splitItems(rest) {
    const out = [];
    rest.split(/\s*[,،]\s*|\s+וגם\s+|\s+גם\s+/).forEach(part => {
      part = part.trim();
      if (!part) return;
      const words = part.split(' ');
      let cur = [];
      words.forEach((w, i) => {
        const isConj = i > 0 && w.length > 2 && w.startsWith('ו') &&
          !w.startsWith('וו') && VAV_WORDS.indexOf(w) === -1;
        if (isConj) {
          // "ו" החיבור: "לחם וביצים" → "לחם", "ביצים"
          if (cur.length) out.push(cur.join(' '));
          cur = [w.slice(1)];
        } else {
          cur.push(w);
        }
      });
      if (cur.length) out.push(cur.join(' '));
    });
    return out.map(s => s.trim()).filter(s => s.length > 0);
  }

  function parseVoice(text) {
    const words = clean(text).split(' ').filter(Boolean);
    let action = 'add';
    let afterEt = false;
    // הסרת מילות פקודה ומילוי מתחילת המשפט
    while (words.length) {
      const w = words[0];
      if (ADD.indexOf(w) !== -1) { action = 'add'; words.shift(); afterEt = false; continue; }
      if (REMOVE.indexOf(w) !== -1) { action = 'remove'; words.shift(); afterEt = false; continue; }
      if (CHECK.indexOf(w) !== -1) { action = 'check'; words.shift(); afterEt = false; continue; }
      if (FILLER.indexOf(w) !== -1) { afterEt = (w === 'את'); words.shift(); continue; }
      break;
    }
    // "את החלב" → "חלב"
    if (afterEt && words.length && words[0].length > 3 && words[0].startsWith('ה')) {
      words[0] = words[0].slice(1);
    }
    while (words.length && TAIL.indexOf(words[words.length - 1]) !== -1) words.pop();
    const rest = words.join(' ');
    // "את" באמצע רשימה: "חלב ואת הלחם"
    const items = splitItems(rest).map(s => s.replace(/^את\s+ה?/, '').trim()).filter(Boolean);
    return { action, items };
  }

  // מפתח להשוואה בין שמות פריטים
  function itemKey(name) {
    return clean(name).replace(/['׳`]/g, '').toLowerCase();
  }
  function sameItem(a, b) {
    const ka = itemKey(a), kb = itemKey(b);
    if (ka === kb) return true;
    const strip = k => (k.length > 3 && k.startsWith('ה')) ? k.slice(1) : k;
    return strip(ka) === strip(kb) || strip(ka) === kb || ka === strip(kb);
  }

  global.Voice = { parseVoice, itemKey, sameItem, clean };
  if (typeof module !== 'undefined') module.exports = global.Voice;
})(typeof window !== 'undefined' ? window : globalThis);
