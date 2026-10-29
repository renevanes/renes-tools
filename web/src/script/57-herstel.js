/* ---------- Gegevens terugzetten na herinstallatie ---------- */
let hsScanRes = null, hsName = '', hsManual = false, hsWarn = false, hsStaged = false;
function hsState(){ try { return JSON.parse(Android.herstelState()); } catch(e){ return {}; } }
function hsStep(id){ document.querySelectorAll('#s-herstel .hs-step').forEach(e => { e.style.display = e.id === id ? '' : 'none'; }); }
/* Naam van een archief (backup-JJJJ-MM-DD_UUMM[ss][_deels].rtb) als leesbare datum */
function hsWhen(name){
  const m = /^backup-(\d{4})-(\d{2})-(\d{2})_(\d{2})(\d{2})/.exec(name || '');
  if (!m) return name || '';
  const d = new Date(+m[1], +m[2] - 1, +m[3], +m[4], +m[5]);
  return d.toLocaleString('nl-NL', {weekday:'short', day:'numeric', month:'long', year:'numeric', hour:'2-digit', minute:'2-digit'});
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
/* Vanuit Alles back-uppen (kan ook op een telefoon met gegevens: dan eerst een duidelijke waarschuwing) */
function herstelStart(){ hsManual = true; show('herstel'); }
function hsPick(){ Android.herstelPickFolder(); }
function hsScanNow(){ hsStep('hs-bezig'); $('#hs-bezig-t').textContent = 'Backups zoeken…'; $('#hs-bezig-s').textContent = 'In je backup-map, map Versleuteld.'; $('#hs-prog').removeAttribute('value'); Android.herstelScan(); }
window.onHerstelFolder = function(){ if (current === 'herstel') hsScanNow(); };
function hsSkip(){
  const s = hsState();
  if (s.pending) { Android.herstelSkip(); toast('Overgeslagen. Terugzetten kan later bij Alles back-uppen.'); }
  Android.herstelCancel(); hsManual = false; show('home');
}
window.onHerstelScan = function(r){
  hsScanRes = r || {};
  hsStep('hs-kies');
  if (r.error) { $('#hs-map').textContent = r.error; $('#hs-list').innerHTML = ''; $('#hs-next').style.display = 'none'; return; }
  const l = r.archives || [];
  $('#hs-map').textContent = 'In ' + (r.destName || 'je backup-map') + ' → Versleuteld: ' + (l.length ? l.length + (l.length === 1 ? ' backup' : ' backups') : 'geen versleutelde backups') + '.';
  $('#hs-next').style.display = l.length ? '' : 'none';
  if (!l.length) {
    const p = r.plain || {}, any = p.notes || p.settings || p.contacts;
    $('#hs-list').innerHTML = '<p class="note" id="hs-none">Geen versleutelde backup gevonden in deze map. Alles terugzetten (met je wachtwoorden, VPN en sleutels) kan alleen uit een versleutelde backup.</p>' +
      (any ? '<p class="note" id="hs-plain">Er staan wel gewone onderdelen in deze map (' + [p.notes ? 'notities' : '', p.settings ? 'instellingen' : '', p.contacts ? 'contacten' : ''].filter(Boolean).join(', ') + '). Die kun je los terugzetten; ze worden toegevoegd aan wat er staat. Wachtwoorden en sleutels staan daar nooit in.</p>' +
        '<button class="big ghost" id="hs-plain-btn" onclick="hsPlain()">Losse onderdelen terugzetten</button>' : '');
    return;
  }
  const rec = r.recommended || l[0].name;
  $('#hs-list').innerHTML = l.map((a, i) => '<label class=chk><input type=radio name=hs-arc value="' + esc(a.name) + '"' + (a.name === rec ? ' checked' : '') + '> ' +
    esc(hsWhen(a.name)) + ' <small class=note style="display:inline;margin:0">· ' + fmtB(a.size || 0) + (a.deels ? ' · onvolledig (gestopt halverwege)' : '') +
    (a.app ? ' · met alle appgegevens' : '') + (a.name === rec ? ' · voorgesteld' : '') + '</small></label>').join('');
};
function hsPlain(){ hsManual = false; show('backup'); setTimeout(() => { const b = document.querySelector('#s-backup button[onclick*="restorePick(\'notes\')"]'); if (b) b.scrollIntoView({block: 'center'}); }, 50); }
function hsSelected(){ const r = document.querySelector('input[name=hs-arc]:checked'); return r ? r.value : ''; }
function hsOpen(){ hsName = hsSelected(); if (!hsName) { toast('Kies een backup'); return; } hsBusyStep(); Android.herstelOpen(hsName, ''); }
function hsBusyStep(){ hsStep('hs-bezig'); $('#hs-bezig-t').textContent = 'Bezig met controleren…'; $('#hs-bezig-s').textContent = 'De hele backup wordt gelezen en gecontroleerd. Er verandert nog niets.'; $('#hs-prog').value = 0; }
let hsPwOld = '';
function hsAskPw(again){
  hsStep('hs-kies');
  askPassword(again ? 'Verkeerd wachtwoord' : 'Wachtwoord', (again ? 'Dat wachtwoord past niet bij deze backup. Probeer het nog eens.' : 'Het wachtwoord van je backup (' + hsWhen(hsName) + ').'), 'Openen', pw => {
    if (!pw) return;
    hsPwOld = pw; hsBusyStep(); Android.herstelOpen(hsName, pw);
  });
}
window.onHerstelProgress = function(s){
  const [done, total, step] = String(s).split(',').map(Number);
  if (!(total > 0)) return;
  $('#hs-prog').value = Math.min(100, Math.round(done * 100 / total));
  $('#hs-bezig-t').textContent = step === 2 ? 'Stap 2 van 2: kluis en Slaappraat ophalen…' : 'Stap 1: controleren…';
  $('#hs-bezig-s').textContent = (step === 2 ? 'Wat al bij Kluis en Slaappraat in de backup stond: ' : 'Gecontroleerd: ') + fmtB(done) + ' van ' + fmtB(total) + '. Er verandert nog niets.';
};
window.onHerstelOpened = function(r){
  // Intussen weggegaan: niets klaar laten staan (en de sleutel vergeten)
  if (current !== 'herstel') { if (r.ok) Android.herstelCancel(); hsStaged = false; hsPwOld = ''; return; }
  if (r.needPw) { hsAskPw(false); return; }
  if (r.error) { if (r.wrongPw) { hsAskPw(true); return; } hsPwOld = ''; hsStep('hs-kies'); toast(r.error); return; }
  if (r.old) { hsStep('hs-oud'); return; }
  hsPwOld = ''; hsStaged = true;
  const parts = [r.files ? r.files.toLocaleString('nl-NL') + ' bestanden' : '', r.kluis ? r.kluis + (r.kluis === 1 ? ' kluisdocument' : ' kluisdocumenten') : '', r.history ? 'meldingsgeschiedenis' : ''].filter(Boolean);
  $('#hs-sum').innerHTML = '<div class=bkres><b>Backup</b><span>' + esc(hsWhen(r.name || hsName)) + '</span></div>' +
    (r.app ? '<div class=bkres><b>Gemaakt met</b><span>Tools ' + esc(r.app) + '</span></div>' : '') +
    '<div class=bkres><b>Inhoud</b><span>' + esc(parts.join(', ') || 'instellingen') + ' · ' + fmtB(r.bytes || 0) + '</span></div>' +
    '<div class=bkres><b>Gecontroleerd</b><span class=good>✓ compleet en onbeschadigd</span></div>' +
    ((r.missing || []).length ? '<p class="note warn" id="hs-missing">Niet in deze backup: ' + esc(r.missing.join('; ')) + '.</p>' : '');
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
  if (hsWarn) askConfirm('Alles vervangen?', 'Alles wat er nu in Tools op deze telefoon staat (notities, instellingen, opnames, kluis …) wordt vervangen door wat in de backup van ' + hsWhen(hsName) + ' staat. Dit kan niet ongedaan worden gemaakt.' +
    '\n\nOok het wachtwoord van je backups wordt dat van deze backup, en je backup-map wordt de map die je net koos.' +
    (hsMissing.length ? '\n\nNiet in deze backup (blijft zoals het nu is of komt niet terug): ' + hsMissing.join('; ') + '.' : ''), 'Vervangen', go);
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
function hsList(){
  hsStep('hs-lijst');
  let r; try { r = JSON.parse(Android.herstelChecklist()); } catch(e){ r = {items: []}; }
  const it = r.items || [], todo = it.filter(i => !i.ok && !i.info).length;
  $('#hs-lijst-t').textContent = todo ? 'Android zet een paar dingen niet terug. Sta ze hieronder opnieuw toe (' + todo + ' te doen); wat al in orde is, staat er met een vinkje bij.' : 'Alles staat weer goed. ✓';
  $('#hs-items').innerHTML = it.map(i => '<div class="row setrow" data-id="' + esc(i.id) + '"><span style="flex:1"><b>' + esc(i.name) + '</b><small class=note style="display:block;margin:0">' + esc(i.used) + '</small></span>' +
    (i.info ? '' : i.ok ? '<span class=permok>✓ Aan</span>' : '<button class="mini" style="float:none" data-p="' + esc(i.id) + '" onclick="hsFix(this.dataset.p)">' + (HS_FIX_LABEL[i.id] || 'Toestaan') + '</button>') + '</div>').join('');
  const m = r.models || {}, names = m.names || [];
  $('#hs-models').style.display = names.length ? '' : 'none';
  if (names.length) $('#hs-models-t').textContent = 'Deze modellen worden opnieuw gedownload (' + fmtB(m.bytes || 0) + '): ' + names.join(', ') + '. ' +
    (m.err ? m.err + '.' : m.wait ? m.wait + '; daarna gaat het vanzelf.' : 'Dat gaat vanzelf, met de voortgang in de meldingenbalk.');
}
function hsFix(id){
  if (id === 'bio') { show('setlock'); return; }
  if (id === 'password') { show('backup'); return; }
  if (id === 'vpn') { show('vpn'); return; }
  if (id === 'wa') { show('wa'); return; }
  Android.herstelFix(id);
}
function hsDone(){ Android.herstelChecklistDone(); hsManual = false; show('home'); toast('Welkom terug'); }
/* Terug op het welkomscherm van een nieuwe installatie: vragen of je wilt overslaan (dan stoppen de backups niet meer) */
function hsBack(){
  if ($('#hs-welkom').style.display === 'none' || !hsState().pending) return false;
  askConfirm('Terugzetten overslaan?', 'Je kunt je gegevens later nog terugzetten bij Alles back-uppen → Alles terugzetten na herinstallatie. Tot je kiest, maakt de app geen backups.', 'Overslaan', hsSkip);
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
