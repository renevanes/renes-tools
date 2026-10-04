/* ---------- Zelftest ---------- */
const SF_ICON = { ok: '✅', warn: '⚠️', fail: '❌', skip: '➖' };
const SF_FIX = {
  transcripts: ['Naar gesprekken', () => jumpTo('transcripts')], dest: ['Map kiezen', () => { sfRecheck = true; Android.waPickFolder(); }],
  audd: ['Sleutel invullen', () => { jumpTo('music'); muChangeToken(); }], alarms: ['Toestaan', () => { sfRecheck = true; Android.alarmExactSettings(); }],
  notif: ['Meldingen aanzetten', () => { sfRecheck = true; Android.notificationSettings(); }], battery: ['Uitzondering geven', () => { sfRecheck = true; Android.batterySettings(); }],
  settings: ['Naar toestemmingen', () => jumpTo('settings')]
};
let sfRecheck = false;
function enterSelfTest(){ const l = rdJson(Android.selfTestLast(), {}); if (l.items) sfRender(l.items, l.t); }
function sfRun(){ const e = Android.selfTestRun(); if (e) { toast(e); return; } $('#sf-run').disabled = true; $('#sf-run').textContent = 'Bezig met testen…'; }
window.onSelfTest = function(items){
  $('#sf-run').disabled = false; $('#sf-run').textContent = 'Opnieuw testen';
  if (!items || !items.length) { toast('De zelftest is mislukt; deel het foutrapport'); return; }
  sfRender(items, Date.now()); renderDash();
  const bad = items.filter(i => i.status === 'fail').length, warn = items.filter(i => i.status === 'warn').length;
  toast(bad ? bad + ' probleem' + (bad > 1 ? 'en' : '') + ' gevonden' : warn ? 'Werkt, met ' + warn + ' aandachtspunt' + (warn > 1 ? 'en' : '') : '✓ Alles werkt');
};
function sfRender(items, t){
  $('#sf-res-card').style.display = 'block';
  $('#sf-when').textContent = t ? 'Laatste test: ' + fmtD(t) : '';
  $('#sf-res').innerHTML = (items || []).map((x, i) => '<div class=sfrow><span class=ico>' + (SF_ICON[x.status] || '•') + '</span><div style="flex:1"><b>' + esc(x.name) + '</b><small>' + esc(x.detail) + '</small>' +
    (x.fix && SF_FIX[x.fix] && x.status !== 'ok' ? '<button class=mini data-f="' + esc(x.fix) + '" onclick="SF_FIX[this.dataset.f][1]()">' + SF_FIX[x.fix][0] + '</button>' : '') + '</div></div>').join('');
}

