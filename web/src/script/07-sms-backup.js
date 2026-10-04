/* ---------- SMS-backup ---------- */
let smsPoll = null, smsSearchTmr = null;
function smsInfo(){ try { return JSON.parse(Android.smsInfo()); } catch(e){ return {}; } }
function enterSms(){
  const i = smsInfo();
  $('#sms-perm').style.display = i.perm ? 'none' : 'block';
  $('#sms-perm-btn').textContent = store.get('smsAsked', false) && !i.perm ? 'Instellingen openen' : 'Toegang geven';
  $('#sms-home').style.display = i.perm ? 'block' : 'none';
  $('#sms-contacts').style.display = (i.perm && !i.contacts) ? 'block' : 'none';
  $('#sms-count-note').textContent = i.total ? i.total.toLocaleString('nl-NL') + ' berichten op deze telefoon' +
    (i.lastExport ? ' · laatste export ' + fmtD(i.lastExport) : '') : '';
  $('#sms-search').value = ''; $('#sms-results').innerHTML = '';
  if (i.perm) loadSmsList();
  smsPollOnce();
  stopSmsPoll(); smsPoll = setInterval(smsPollOnce, 600);
}
function stopSmsPoll(){ if (smsPoll) { clearInterval(smsPoll); smsPoll = null; } }
window.onSmsChanged = function(){ enterSms(); };
function smsAskPerm(){
  if (store.get('smsAsked', false) && !Android.smsHasPermission()) { Android.openAppSettings(); return; }
  store.set('smsAsked', true); Android.smsRequestPermission();
}
function loadSmsList(){
  let d; try { d = JSON.parse(Android.smsConversations()); } catch(e){ return; }
  if (d.error) { $('#sms-list').innerHTML = '<p class=note style="padding:14px">' + esc(d.error) + '</p>'; return; }
  const el = $('#sms-list');
  el.innerHTML = (d.conversations || []).map(cv =>
    '<button onclick="openSms(' + cv.thread + ',' + JSON.stringify(cv.name).replace(/"/g,'&quot;') + ')">' +
    '<span class=r1><b>' + esc(cv.name) + '</b><time>' + esc(fmtD(cv.last)) + '</time></span>' +
    '<span class=r2>' + esc(cv.lastText || '') + ' · ' + cv.count + '</span></button>').join('');
  if (!(d.conversations || []).length) el.innerHTML = '<p class=note style="padding:14px">Geen sms-berichten gevonden.</p>';
}
let smsChatCur = null;
function openSms(thread, name){ smsChatCur = thread; $('#sms-title').textContent = name; show('smschat'); loadSmsMsgs(); }
function loadSmsMsgs(){
  let msgs; try { msgs = JSON.parse(Android.smsMessages(String(smsChatCur))); } catch(e){ return; }
  const box = $('#sms-msgs');
  if (msgs.error) { box.innerHTML = '<p class=note>' + esc(msgs.error) + '</p>'; return; }
  box.innerHTML = smsRenderMsgs(msgs);
  window.scrollTo(0, document.body.scrollHeight);
}
function smsRenderMsgs(msgs){
  let out = '', lastDay = '';
  for (const m of msgs) {
    const d = new Date(m.t), dd = d.toLocaleDateString('nl-NL', {weekday:'long', day:'numeric', month:'long', year:'numeric'});
    if (dd !== lastDay) { out += '<div class=dsep>' + esc(dd) + '</div>'; lastDay = dd; }
    out += '<div class="bub' + (m.me ? ' me' : '') + '">' + esc(m.text || '') +
      '<span class=tm>' + d.toLocaleTimeString('nl-NL', {hour:'2-digit', minute:'2-digit'}) + '</span></div>';
  }
  return out || '<p class=note>Geen berichten.</p>';
}
function smsSearchDo(q){
  clearTimeout(smsSearchTmr);
  const sr = $('#sms-results'), cl = $('#sms-list');
  if (!q || q.trim().length < 2) { sr.innerHTML = ''; cl.style.display = 'block'; return; }
  smsSearchTmr = setTimeout(() => {
    let msgs; try { msgs = JSON.parse(Android.smsSearch(q.trim())); } catch(e){ return; }
    cl.style.display = 'none';
    if (msgs.error) { sr.innerHTML = '<p class=note>' + esc(msgs.error) + '</p>'; return; }
    if (!msgs.length) { sr.innerHTML = '<p class=note style="text-align:center">Niets gevonden</p>'; return; }
    sr.innerHTML = '<p class=note>' + msgs.length + ' resultaten</p><div class="msgs">' + msgs.map(m =>
      '<div class="bub' + (m.me ? ' me' : '') + '" onclick="openSms(' + m.thread + ',' + JSON.stringify(m.name||'').replace(/"/g,'&quot;') + ')">' +
      '<span class=snd>' + esc(m.name || '') + '</span>' + esc(m.text || '') +
      '<span class=tm>' + fmtD(m.t) + '</span></div>').join('') + '</div>';
  }, 250);
}
function smsExport(){
  const err = Android.smsExport();
  if (err) { toast(err); return; }
  $('#sms-result').className = 'result'; smsLastDone = false; setTimeout(smsPollOnce, 150);
}
let smsLastDone = false;
function smsPollOnce(){
  let s; try { s = JSON.parse(Android.smsStatus()); } catch(e){ return; }
  $('#sms-run').style.display = s.running ? 'block' : 'none';
  $('#sms-home').style.display = (s.running || !smsInfo().perm) ? 'none' : 'block';
  if (s.running) {
    const pct = s.total ? 100 * s.done / s.total : 0;
    $('#sms-bar').style.width = pct.toFixed(0) + '%';
    $('#sms-run-count').textContent = (s.done||0) + ' van ' + (s.total||0) + ' berichten';
  } else if (s.ok !== undefined && !smsLastDone) {
    smsLastDone = true;
    const r = $('#sms-result');
    r.textContent = s.ok ? '✓ ' + s.count + ' berichten geëxporteerd naar de map SMS backup' : 'Mislukt: ' + (s.error || '');
    r.className = 'result on ' + (s.ok ? 'answered' : 'error');
    enterSms();
  }
  refreshSmsTile(s);
}
function refreshSmsTile(s){
  const t = $('#tile-sms'); if (!t) return;
  t.classList.toggle('live', !!(s && s.running));
  const sub = $('#tile-sms-sub');
  if (sub) { const i = smsInfo(); sub.textContent = (s && s.running) ? 'Bezig met exporteren…' : (i.lastExport ? 'Laatste export: ' + fmtD(i.lastExport) : 'Bewaar je sms-berichten'); }
}

