/* ---------- Gegevens terugzetten na herinstallatie ---------- */
let hsScanRes = null, hsName = '', hsManual = false, hsWarn = false, hsStaged = false;
function hsState(){ try { return JSON.parse(Android.herstelState()); } catch(e){ return {}; } }
function hsStep(id){ document.querySelectorAll('#s-herstel .hs-step').forEach(e => { e.style.display = e.id === id ? '' : 'none'; }); }
/* Naam van een archief (backup-JJJJ-MM-DD_UUMM[ss][_deels].rtb) als leesbare datum */
function hsWhen(name){
  const m = /^backup-(\d{4})-(\d{2})-(\d{2})_(\d{2})(\d{2})/.exec(name || '');
  if (!m) return name || '';
  const d = new Date(+m[1], +m[2] - 1, +m[3], +m[4], +m[5]);
  return fmtDT(d, {weekday:'short', day:'numeric', month:'long', year:'numeric', hour:'2-digit', minute:'2-digit'});
}
function enterHerstel(){
  const s = hsState();
  if (s.after) { hsList(); return; }
  hsStep('hs-welkom');
  hsWarn = hsManual || !s.pending || !!s.hasData; // staat er al iets: altijd eerst "Alles vervangen?"
  $('#hs-warn-top').style.display = hsWarn ? '' : 'none';
  $('#hs-same').style.display = s.dest ? '' : 'none';
  $('#hs-skip').textContent = s.pending ? 'Overslaan' : 'Annuleren';
}
/* Vanuit Alles back-uppen → Terugzetten (kan ook op een telefoon met gegevens: dan eerst een duidelijke waarschuwing).
   Terug of Annuleren brengt je daar weer; bij een nieuwe installatie blijft terug eerst vragen. */
function herstelStart(){ hsManual = true; if (hsState().pending && SOLO !== 'backup') show('herstel'); else jumpTo('herstel'); }
function hsPick(){ Android.herstelPickFolder(); }
function hsScanNow(){ hsStep('hs-bezig'); $('#hs-bezig-t').textContent = 'Backups zoeken…'; $('#hs-bezig-s').textContent = 'In je backup-map, map Versleuteld.'; $('#hs-prog').removeAttribute('value'); Android.herstelScan(); }
window.onHerstelFolder = function(){ if (current === 'herstel') hsScanNow(); };
function hsSkip(){
  const s = hsState();
  if (s.pending) { Android.herstelSkip(); toast('Overgeslagen. Terugzetten kan later bij Alles back-uppen.'); }
  const terug = !s.pending && hsManual && jumpStack.length && jumpStack[jumpStack.length - 1].to === 'herstel';
  Android.herstelCancel(); hsManual = false;
  if (terug) goBack(); else hsEnd();
}
window.onHerstelScan = function(r){
  hsScanRes = r || {};
  hsStep('hs-kies');
  if (r.error) { $('#hs-map').textContent = r.error; $('#hs-list').innerHTML = ''; $('#hs-next').style.display = 'none'; return; }
  const l = r.archives || [];
  $('#hs-map').textContent = t('In {map} → Versleuteld: {wat}.', { map: r.destName || t('je backup-map'), wat: l.length ? tn(l.length, '{n} backup', '{n} backups') : t('geen versleutelde backups') });
  $('#hs-next').style.display = l.length ? '' : 'none';
  if (!l.length) {
    const p = r.plain || {}, kinds = p.kinds || [], any = kinds.length || p.notes || p.settings || p.contacts;
    const wat = kinds.length ? kinds.map(k => bkLosLabel(k).toLowerCase()) : [p.notes ? 'notities' : '', p.settings ? 'instellingen' : '', p.contacts ? 'contacten' : ''].filter(Boolean).map(x => t(x));
    $('#hs-list').innerHTML = '<p class="note" id="hs-none">Geen versleutelde backup gevonden in deze map. Alles terugzetten (met je wachtwoorden, VPN en sleutels) kan alleen uit een versleutelde backup.</p>' +
      (any ? '<p class="note" id="hs-plain">' + esc(t('Er staan wel gewone onderdelen in deze map ({wat}). Die zet je in één keer terug; ze worden toegevoegd aan wat er staat. Wachtwoorden en sleutels staan daar nooit in.', { wat: wat.join(', ') })) + '</p>' +
        '<button class="big" id="hs-plain-btn" onclick="hsPlain()">Alles uit de backup-map terugzetten</button>' : '');
    return;
  }
  const rec = r.recommended || l[0].name;
  $('#hs-list').innerHTML = l.map((a, i) => '<label class=chk><input type=radio name=hs-arc value="' + esc(a.name) + '"' + (a.name === rec ? ' checked' : '') + '> ' +
    esc(hsWhen(a.name)) + ' <small class=note style="display:inline;margin:0">· ' + fmtB(a.size || 0) + (a.deels ? ' · onvolledig (gestopt halverwege)' : '') +
    (a.app ? ' · ' + t('met alle appgegevens') : '') + (a.name === rec ? ' · ' + t('voorgesteld') : '') + '</small></label>').join('');
};
/* Losse onderdelen staan op één plek: Alles back-uppen → Terugzetten */
function hsPlain(){ hsManual = false; jumpTo('bkrestore'); bkLosScan(); }
function hsSelected(){ const r = document.querySelector('input[name=hs-arc]:checked'); return r ? r.value : ''; }
function hsOpen(){ hsName = hsSelected(); if (!hsName) { toast('Kies een backup'); return; } hsBusyStep(); Android.herstelOpen(hsName, ''); }
function hsBusyStep(){ hsStep('hs-bezig'); $('#hs-bezig-t').textContent = 'Bezig met controleren…'; $('#hs-bezig-s').textContent = 'De hele backup wordt gelezen en gecontroleerd. Er verandert nog niets.'; $('#hs-prog').value = 0; }
let hsPwOld = '';
function hsAskPw(again){
  hsStep('hs-kies');
  askPassword(again ? 'Verkeerd wachtwoord' : 'Wachtwoord', (again ? 'Dat wachtwoord past niet bij deze backup. Probeer het nog eens.' : t('Het wachtwoord van je backup ({wanneer}).', { wanneer: hsWhen(hsName) })), 'Openen', pw => {
    if (!pw) return;
    hsPwOld = pw; hsBusyStep(); Android.herstelOpen(hsName, pw);
  });
}
window.onHerstelProgress = function(s){
  const [done, total, step] = String(s).split(',').map(Number);
  if (!(total > 0)) return;
  $('#hs-prog').value = Math.min(100, Math.round(done * 100 / total));
  $('#hs-bezig-t').textContent = step === 2 ? 'Stap 2 van 2: kluis en Slaappraat ophalen…' : 'Stap 1: controleren…';
  $('#hs-bezig-s').textContent = t(step === 2 ? 'Wat al bij Kluis en Slaappraat in de backup stond: {a} van {b}. Er verandert nog niets.' : 'Gecontroleerd: {a} van {b}. Er verandert nog niets.', { a: fmtB(done), b: fmtB(total) });
};
window.onHerstelOpened = function(r){
  // Intussen weggegaan: niets klaar laten staan (en de sleutel vergeten)
  if (current !== 'herstel') { if (r.ok) Android.herstelCancel(); hsStaged = false; hsPwOld = ''; return; }
  if (r.needPw) { hsAskPw(false); return; }
  if (r.error) { if (r.wrongPw) { hsAskPw(true); return; } hsPwOld = ''; hsStep('hs-kies'); toast(r.error); return; }
  if (r.old) { hsStep('hs-oud'); return; }
  hsPwOld = ''; hsStaged = true;
  const parts = [r.files ? t('{n} bestanden', { n: fmtNum(r.files) }) : '', r.kluis ? tn(r.kluis, '{n} kluisdocument', '{n} kluisdocumenten') : '', r.history ? t('meldingsgeschiedenis') : ''].filter(Boolean);
  $('#hs-sum').innerHTML = '<div class=bkres><b>Backup</b><span>' + esc(hsWhen(r.name || hsName)) + '</span></div>' +
    (r.app ? '<div class=bkres><b>Gemaakt met</b><span>Tools ' + esc(r.app) + '</span></div>' : '') +
    '<div class=bkres><b>Inhoud</b><span>' + esc(parts.join(', ') || 'instellingen') + ' · ' + fmtB(r.bytes || 0) + '</span></div>' +
    '<div class=bkres><b>Gecontroleerd</b><span class=good>✓ compleet en onbeschadigd</span></div>' +
    ((r.protected || []).length ? '<p class="note warn">' + esc(t('Deze backup bevat gegevens met een hoog risico: {namen}. Ze vervangen bij terugzetten de huidige gegevens van die tools. Na terugzetten moet je iedere tool zelf ontgrendelen.', {namen:r.protected.map(id => t(TOOLS[id]?.[0] || id)).join(', ')})) + '</p>' : '') +
    ((r.missing || []).length ? '<p class="note warn" id="hs-missing">' + esc(t('Niet in deze backup: {wat}.', { wat: r.missing.map(x => t(x)).join('; ') })) + '</p>' : '');
  hsWarn = hsManual || !!r.warn;
  hsMissing = r.missing || [];
  $('#hs-warn').style.display = hsWarn ? '' : 'none';
  hsStep('hs-klaar');
};
let hsMissing = [];
function hsBackToList(){ Android.herstelCancel(); hsStaged = false; hsPwOld = ''; if (hsScanRes) onHerstelScan(hsScanRes); else enterHerstel(); }
function hsOld(){ const pw = hsPwOld; hsPwOld = ''; toast('Bezig met openen…'); Android.herstelOldArchive(hsName, pw); }
function hsApply(){
  const go = () => { hsStaged = false; hsStep('hs-bezig'); $('#hs-bezig-t').textContent = 'Bezig met terugzetten…'; $('#hs-bezig-s').textContent = 'Even geduld; daarna start de app opnieuw.'; $('#hs-prog').removeAttribute('value'); Android.herstelApply(); };
  if (hsWarn) askConfirm('Alles vervangen?', t('Alles wat er nu in Tools op deze telefoon staat (notities, instellingen, opnames, kluis …) wordt vervangen door wat in de backup van {wanneer} staat. Dit kan niet ongedaan worden gemaakt.', { wanneer: hsWhen(hsName) }) +
    '\n\n' + t('Ook het wachtwoord van je backups wordt dat van deze backup, en je backup-map wordt de map die je net koos.') +
    (hsMissing.length ? '\n\n' + t('Niet in deze backup (blijft zoals het nu is of komt niet terug): {wat}.', { wat: hsMissing.map(x => t(x)).join('; ') }) : ''), 'Vervangen', go);
  else go();
}
window.onHerstelDone = function(err){
  if (err) {
    hsStaged = !/niets veranderd/.test(err); // bij "bezig" of "er loopt een backup" staat de backup nog klaar
    if (current !== 'herstel') { if (hsStaged) Android.herstelCancel(); hsStaged = false; return; }
    hsStep('hs-klaar'); toast(err); return;
  }
  $('#hs-bezig-t').textContent = '✓ Teruggezet'; $('#hs-bezig-s').textContent = 'De app start opnieuw…';
};
/* Na de herstart: wat Android niet terugzet */
const HS_FIX_LABEL = { bio: 'Openen', password: 'Instellen', vpn: 'Openen', wa: 'Openen' };
function hsNeedsApp(id){ return SOLO === 'backup' && ['bio', 'vpn', 'wa'].includes(id); }
function hsEnd(){ if (SOLO === 'backup') jumpStack.length = 0; show(SOLO === 'backup' ? 'backup' : 'home'); }
function hsList(){
  hsStep('hs-lijst');
  let r; try { r = JSON.parse(Android.herstelChecklist()); } catch(e){ r = {items: []}; }
  const it = r.items || [], todo = it.filter(i => !i.ok && !i.info).length;
  $('#hs-lijst-t').textContent = todo ? t('Android zet een paar dingen niet terug. Sta ze hieronder opnieuw toe ({n} te doen); wat al in orde is, staat er met een vinkje bij.', { n: todo }) : t('Alles staat weer goed. ✓');
  $('#hs-items').innerHTML = it.map(i => '<div class="row setrow" data-id="' + esc(i.id) + '"><span style="flex:1"><b>' + esc(i.name) + '</b><small class=note style="display:block;margin:0">' + esc(i.used) + '</small></span>' +
    (i.info ? '' : i.ok ? '<span class=permok>✓ Aan</span>' : hsNeedsApp(i.id) ? '<small class=note>' + esc(t('Stel dit onderdeel opnieuw in via de gewone Tools-app.')) + '</small>' : '<button class="mini" style="float:none" data-p="' + esc(i.id) + '" onclick="hsFix(this.dataset.p)">' + (HS_FIX_LABEL[i.id] || 'Toestaan') + '</button>') + '</div>').join('');
  const m = r.models || {}, names = m.names || [];
  $('#hs-models').style.display = names.length ? '' : 'none';
  if (names.length) $('#hs-models-t').textContent = t('Deze modellen worden opnieuw gedownload ({grootte}): {namen}.', { grootte: fmtB(m.bytes || 0), namen: names.map(x => t(x)).join(', ') }) + ' ' +
    (m.err ? t(m.err) + '.' : m.wait ? t('{wat}; daarna gaat het vanzelf.', { wat: t(m.wait) }) : t('Dat gaat vanzelf, met de voortgang in de meldingenbalk.'));
}
function hsFix(id){
  if (hsNeedsApp(id)) { toast(t('Stel dit onderdeel opnieuw in via de gewone Tools-app.')); return; }
  if (id === 'bio') { show('setlock'); return; }
  if (id === 'password') { jumpTo('bkset'); return; }
  if (id === 'vpn') { show('vpn'); return; }
  if (id === 'wa') { jumpTo('chats'); return; }
  Android.herstelFix(id);
}
function hsDone(){ Android.herstelChecklistDone(); hsManual = false; hsEnd(); toast('Welkom terug'); }
/* Terug op het welkomscherm van een nieuwe installatie: vragen of je wilt overslaan (dan stoppen de backups niet meer) */
function hsBack(){
  if ($('#hs-welkom').style.display === 'none' || !hsState().pending) return false;
  askConfirm('Terugzetten overslaan?', 'Je kunt je gegevens later nog terugzetten bij Alles back-uppen → Terugzetten. Tot je kiest, maakt de app geen backups.', 'Overslaan', hsSkip);
  return true;
}
/* Weg van dit scherm met een klaargezette backup: die meteen weggooien (en de sleutel vergeten) */
function hsLeave(){ if (hsStaged) { hsStaged = false; Android.herstelCancel(); } hsPwOld = ''; }
(function(){
  const prev = window.onResumeApp;
  window.onResumeApp = function(){ if (typeof prev === 'function') prev(); if (current === 'herstel' && document.querySelector('#hs-lijst').style.display !== 'none') hsList(); };
  const prevS = window.onSettingsChanged;
  window.onSettingsChanged = function(msg){ if (typeof prevS === 'function') prevS(msg); if (current === 'herstel' && document.querySelector('#hs-lijst').style.display !== 'none') hsList(); };
})();
