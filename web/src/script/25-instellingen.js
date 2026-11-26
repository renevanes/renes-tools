/* ---------- Instellingen ----------
 * Een overzicht in groepen (met per onderwerp een korte stand van zaken) en per onderwerp een eigen scherm. */
const ST_SUBS = ['setnotif', 'setlook', 'setregio', 'setskin', 'setlock', 'setslot', 'setsolo', 'setperm', 'setmusic', 'setcrash', 'setcrashdoel', 'setcrashlog', 'setcrashstat', 'setcrashdiag', 'setcrashrap', 'setcrashweg', 'spraak', 'setkopp'];
const stOn = () => current === 'settings' || ST_SUBS.includes(current);
function stJson(s){ try { return JSON.parse(s); } catch(e){ return {}; } }
function stSum(id, tt){ const e = $('#st-sum-' + id); if (e) e.textContent = tt; }
/* De regel onder elk onderwerp in het overzicht */
function stSummaries(i){
  if (current === 'settings') stSum('kopp', kpSamenvatting()); // alleen op het overzicht: de stand van Facturen ophalen is zwaar
  const lk = i.lock || {};
  if (typeof Android.notifState === 'function') { const n = stJson(Android.notifState()); stSum('notif', n.muted ? 'Alle meldingen uit' : !n.allowed ? 'Uit voor deze app' : 'Aan'); }
  const z = $('#st-zoom'); stSum('look', t('Thema: {thema}', { thema: t(thModeName(thCfg().mode)).replace(/ \(.*\)$/, '').toLowerCase() }) + (z && z.selectedIndex >= 0 ? ' · ' + t('tekst: {grootte}', { grootte: z.options[z.selectedIndex].text.toLowerCase() }) : '') + ($('#st-dyn').classList.contains('on') ? ' · ' + t('kleuren uit je achtergrond') : ''));
  if (typeof rgSummary === 'function') stSum('regio', rgSummary());
  stSum('skin', Android.homeIsDefault() ? '✓ Tools is je startscherm' : 'Tools als startscherm gebruiken');
  stSum('lock', lk.on && !(lk.secure || lk.code) ? 'Aan, maar werkt nog niet: stel een vergrendeling of app-code in' : lk.on ? t('Aan') + (lk.code ? ' · ' + t('eigen app-code') : '') : 'Uit');
  const so = typeof soloAllowedList === 'function' ? soloAllowedList().length : 0;
  stSum('solo', so ? tn(so, '{n} tool als losse app', '{n} tools als losse app') : t('Geen'));
  if (typeof tsStand === 'function') stSum('slot', tsStand());
  const ps = i.perms || [], miss = ps.filter(p => !p.ok).length;
  stSum('perm', !ps.length ? '' : miss ? t('{n} van {totaal} nog niet toegestaan', { n: miss, totaal: ps.length }) : '✓ Alles toegestaan');
  stSum('backup', bkSetSumText(bkState(true))); // Backup: map, nachtelijk en versleutelen staan bij Alles back-uppen → Automatisch en versleutelen
  // AI alleen in het overzicht (vraagt wat meer werk)
  if (current === 'settings' && typeof Android.aiState === 'function') { const a = stJson(Android.aiState()), m = (a.models || []).filter(x => x.state === 'ready');
    stSum('ai', m.length ? m.map(x => x.label).join(' · ') : 'Nog geen model gedownload'); }
  if (typeof Android.speechModels === 'function') { const v = stJson(Android.speechModels()), on = (v.voices || []).find(x => x.id === v.voice);
    stSum('spraak', on ? t('Stem: {stem}', { stem: on.label }) : t('Nog geen voorleesstem')); }
  stSum('music', i.audd ? 'AudD-sleutel ingesteld' : 'Nog geen AudD-sleutel');
  stSum('crash', i.crashes ? tn(i.crashes, '{n} fout vastgelegd', '{n} fouten vastgelegd') : t('✓ Geen fouten'));
  stSum('self', 'Controleer of alles werkt');
  stSum('info', 'Wat is er nieuw en updates');
}
/* Meldingen van deze app: één lijst met wat aan en uit staat; tikken = die soort in Android. */
const NLEVEL = ['Uit', 'Stil', 'Zacht', 'Normaal', 'Met geluid'];
function stNotifRender(){
  if (typeof Android.notifState !== 'function') return;
  const s = stJson(Android.notifState());
  const muted = !!s.muted, off = !s.allowed;
  const mb = $('#st-notif-mute'); if (mb) { mb.classList.toggle('on', muted); mb.setAttribute('aria-pressed', muted ? 'true' : 'false'); }
  $('#st-notif-state').textContent = muted ? 'Alle meldingen staan uit. Je krijgt geen herinneringen, backup- of automatiseringsmeldingen. Draait de radio of een route-opname, dan toont Android alleen een stille regel onderaan je meldingen.'
    : off ? 'Meldingen staan uit voor deze app: je mist herinneringen, de radiowekker en backup-meldingen.' : '✓ Meldingen staan aan.';
  $('#st-notif-ask').style.display = off && !muted ? 'block' : 'none';
  $('#st-notif-list').style.opacity = muted ? '.45' : '';
  $('#st-notif-list').innerHTML = (s.kinds || []).map(k => '<button class=nkind data-id="' + esc(k.id) + '" onclick="Android.notifOpen(this.dataset.id)"><span><b>' + esc(t(k.name)) + '</b><small>' + esc(t(k.desc)) + '</small></span>' +
    '<span class="nst' + (off || !k.on ? ' off' : '') + '">' + t(off ? 'Uit' : NLEVEL[k.level] || 'Aan') + '</span><span aria-hidden=true>›</span></button>').join('');
}
function stNotifMute(on){
  if (typeof Android.notifMute !== 'function') return;
  Android.notifMute(!!on);
  toast(on ? 'Alle meldingen van de app staan uit' : 'Meldingen staan weer aan');
  stNotifRender();
}
function enterSettings(){
  if (current === 'setkopp') kpRender();
  stNotifRender();
  stSoloRender();
  if (typeof tsRender === 'function') tsRender();
  $('#st-zoom').value = String(Android.textZoomGet());
  { const can = typeof Android.themeColors === 'function' && !!Android.themeColors(), on = can && store.get('dynColor', false);
    $('#st-dyn').classList.toggle('on', on); $('#st-dyn').setAttribute('aria-checked', on ? 'true' : 'false'); $('#st-dyn').disabled = !can;
    $('#st-dyn-sub').textContent = can ? 'Net als Android: de app neemt de kleuren van je achtergrond over' : 'Kan vanaf Android 12; nu vaste kleuren'; }
  stHomeRender();
  thRender();
  if (typeof rgRender === 'function') rgRender();
  const i = stJson(Android.settingsInfo());
  const lk = i.lock || {};
  $('#st-lock-btn').textContent = lk.on ? 'Uitzetten' : 'Aanzetten';
  $('#st-lock-tw').style.display = lk.on ? 'flex' : 'none';
  $('#st-lock-timeout').value = String(lk.timeout != null ? lk.timeout : 0);
  $('#st-lock-state').textContent = lk.on && (lk.secure || lk.code) ? 'Aan' : lk.on ? 'Aan, maar werkt pas met een schermvergrendeling of een app-code' : (lk.secure || lk.code ? 'Uit' : 'Uit · stel eerst een schermvergrendeling of een app-code in');
  $('#st-code-state').textContent = lk.code ? 'Eigen app-code: aan' : 'Eigen app-code: uit';
  $('#st-code-btn').textContent = lk.code ? 'Wijzigen' : 'Instellen';
  $('#st-code-more').style.display = lk.code ? 'block' : 'none';
  $('#st-decoy').checked = !!lk.decoy; $('#st-codebio').checked = !!lk.codeBio;
  $('#st-dest').textContent = i.dest ? t('Huidige map: {map}', { map: i.destName || t('gekozen') }) : t('Nog niet gekozen');
  $('#st-audd').textContent = i.audd ? t('Sleutel ingesteld ({sleutel})', { sleutel: i.audd }) : t('Nog geen sleutel ingesteld');
  $('#st-perms').innerHTML = (i.perms || []).map(p =>
    '<div class="row setrow"><span style="flex:1"><b>' + esc(t(p.name)) + '</b><small class=note style="display:block;margin:0">' + esc(t(p.used)) + '</small></span>' +
    (p.ok ? '<span class=permok>✓ Aan</span>' : '<button class="mini" style="float:none" data-p="' + esc(p.id) + '" onclick="Android.permRequest(this.dataset.p)">Toestaan</button>') + '</div>').join('');
  $('#st-crash').textContent = i.crashes ? tn(i.crashes, '{n} fout vastgelegd.', '{n} fouten vastgelegd.') : t('Er zijn geen fouten vastgelegd. ✓');
  stStatRender();
  stLogRender();
  if (current !== 'setcrashdiag' || !dg.vrij) { dg.vrij = ''; dg.open = ''; } // een vrijgave na het slotscherm geldt tot je Diagnose verlaat
  dgRender();
  fcRender(i);
  stSummaries(i);
}
function stHomeRender(){
  const on = Android.homeIsDefault();
  $('#st-home-state').textContent = on ? '✓ Tools is nu je startscherm.' : '';
  $('#st-home-btn').style.display = on ? 'none' : 'block';
  $('#st-home-back').style.display = on ? 'block' : 'none';
}
window.onHomeRole = function(){ if (stOn()) { stHomeRender(); stSum('skin', Android.homeIsDefault() ? '✓ Tools is je startscherm' : 'Tools als startscherm gebruiken'); } if (Android.homeIsDefault()) toast('✓ Startscherm ingesteld; druk op de home-knop'); };
window.onSettingsChanged = function(msg){ if (msg) toast(msg); if (stOn()) enterSettings(); };
function stLockToggle(){
  const lk = stJson(Android.lockState());
  if (!lk.secure && lk.code) { stAskCode(t(lk.on ? 'App-slot uitzetten' : 'App-slot aanzetten'), t('Bevestig met je app-code'), c => Android.lockSetCode(!lk.on, c)); return; }
  if (!lk.secure && !lk.on) { toast('Stel eerst een eigen app-code in (hieronder) of een schermvergrendeling op je telefoon'); return; }
  Android.lockSet(!lk.on);
}
/* Cijfercode vragen (verborgen, cijfertoetsenbord) */
function stAskCode(title, text, onOk){
  askInput(title, text, '', 'OK', v => onOk(String(v || '').trim()));
  const i = $('#modal-input'); i.type = 'password'; i.inputMode = 'numeric'; i.autocomplete = 'off'; i.setAttribute('pattern', '[0-9]*');
}
function stCodeSet(){
  const lk = stJson(Android.lockState());
  const go = old => stAskCode(lk.code ? 'Nieuwe app-code' : 'App-code instellen', '4 tot 12 cijfers. Kies er een die je niet vergeet: zonder code (en zonder vingerafdruk) kom je niet meer in de app.', c => {
    if (!/^[0-9]{4,12}$/.test(c)) { toast('Gebruik 4 tot 12 cijfers'); return; }
    setTimeout(() => stAskCode('Nog een keer', 'Tik dezelfde code nog een keer in', c2 => {
      if (c2 !== c) { toast('De codes zijn niet gelijk'); return; }
      Android.lockCodeSet(c, old || '');
    }), 150);
  });
  if (lk.code && !lk.secure) stAskCode('Huidige app-code', 'Eerst je huidige code', old => setTimeout(() => go(old), 150));
  else go('');
}
function stCodeClear(){
  const lk = stJson(Android.lockState());
  const msg = lk.secure ? 'Daarna ontgrendel je weer met de vergrendeling van je telefoon.' : 'Je telefoon heeft geen schermvergrendeling: dan staat het app-slot ook uit.';
  askConfirm('App-code verwijderen?', t(msg) + ' ' + t('De neutrale versie werkt dan niet meer.'), 'Verwijderen', () => {
    if (!lk.secure) stAskCode('Huidige app-code', 'Bevestig met je huidige code', old => Android.lockCodeClear(old));
    else Android.lockCodeClear('');
  });
}
/* Statistieken voor verbeteringen: standaard aan, maar er gaat pas iets weg als er een repository en sleutel zijn */
function stStatRender(){
  if (typeof Android.statState !== 'function') return; // fcRender verbergt de rijen
  const s = stJson(Android.statState()), on = s.on !== false, b = $('#st-stat-on');
  b.classList.toggle('on', on); b.setAttribute('aria-checked', on ? 'true' : 'false');
  const r = $('#st-stat-repo'); if (document.activeElement !== r) r.value = s.repo || '';
  $('#st-stat-token').placeholder = s.token ? t('Sleutel is ingesteld (leeg laten = houden)') : t('Fine-grained GitHub-sleutel (github_pat_…)');
  $('#st-stat-sub').textContent = !on ? t('Uit: er wordt niets geteld of verstuurd') : !(s.repo && s.token) ? t('Aan, maar nog geen bestemming: er gaat niets weg') :
    s.lastResult && s.lastResult !== 'ok' ? t('Laatste poging: {uitkomst}', { uitkomst: t(s.lastResult) }) : s.lastSent ? t('Laatst verstuurd: {wanneer}', { wanneer: fmtD(s.lastSent) }) : t('Wordt binnenkort voor het eerst verstuurd');
  $('#st-stat-send').disabled = !(on && s.repo && s.token);
  $('#st-stat-preview').textContent = Android.statPreview();
}
function stStatOn(on){ Android.statOn(on); stStatRender(); fcRender(); toast(on ? 'Statistieken staan aan' : 'Statistieken staan uit; de tellingen zijn gewist'); }
function stStatSave(){
  const tt = $('#st-stat-token'), e = Android.statDest($('#st-stat-repo').value.trim(), tt.value.trim());
  if (e) { toast(e); return; }
  tt.value = ''; $('#fc-test-uit').textContent = ''; stStatRender(); dgRender(); fcRender(); toast($('#st-stat-repo').value.trim() ? 'Bestemming bewaard' : 'Bestemming verwijderd');
}
function stStatSend(){ const b = $('#st-stat-send'); b.disabled = true; b.textContent = 'Versturen…'; Android.statSend(); }
/* Diagnose: wat tools vastlegden om een probleem na te zoeken. Alleen in het geheugen; versturen of kopiëren per stuk. */
const dg = { open: '', vrij: '', bezig: '' };
function dgToolNaam(tool){ return tool === 'logboek' ? t('Foutlogboek') : tool === 'verbinding' ? t('Verbindingstest') : TOOLS[tool] ? t(TOOLS[tool][0]) : tool; }
function dgDicht(it){ return dg.vrij !== it.id && typeof tsVerborgen === 'function' && !!TOOLS[it.tool] && tsVerborgen(it.tool); }
function dgRender(){
  const kaart = $('#st-diag');
  if (typeof Android.diagnoseStaat !== 'function') { kaart.style.display = 'none'; return; }
  const s = stJson(Android.diagnoseStaat()), items = s.items || [], lijst = $('#st-diag-lijst');
  $('#st-diag-waar').textContent = s.klaar ? t('Versturen gaat naar je eigen privé repository {repo}, map diagnose.', { repo: s.repo }) : t('Nog geen bestemming: stel bij Bestemming je repository en sleutel in. Kopiëren kan wel.');
  if (!items.find(i => i.id === dg.open)) dg.open = '';
  lijst.innerHTML = items.length ? '' : '<p class="note">' + esc(t('Nog niets vastgelegd. Doe in de tool opnieuw wat niet goed ging en kom dan hier terug.')) + '</p>';
  items.forEach(it => {
    const rij = document.createElement('div'); rij.className = 'dg-item';
    const kop = document.createElement('p'); kop.className = 'dg-kop';
    const b = document.createElement('b'); b.textContent = dgToolNaam(it.tool) + ': ' + t(it.titel); kop.appendChild(b);
    const klein = document.createElement('small');
    klein.textContent = fmtDT(it.tijd) + ' · ' + tn(it.tekens, 'Eén teken', '{n} tekens') + (it.verstuurd ? ' · ' + t('verstuurd') : '');
    kop.appendChild(klein); rij.appendChild(kop);
    const knoppen = document.createElement('div'); knoppen.className = 'row';
    const knop = (tekst, klas, fn) => { const k = document.createElement('button'); k.type = 'button'; k.className = klas; k.style.flex = '1'; k.textContent = tekst; k.addEventListener('click', fn); knoppen.appendChild(k); return k; };
    if (dgDicht(it)) {
      knop(t('Ontgrendelen om te bekijken'), 'mini', () => { if (!tsVraag(it.tool, () => { dg.vrij = it.id; dg.open = it.id; dgRender(); }, () => {}, t('Ontgrendel om deze diagnose te zien.'))) { dg.vrij = it.id; dgRender(); } });
    } else {
      knop(dg.open === it.id ? t('Verbergen') : t('Bekijken'), 'mini', () => { dg.open = dg.open === it.id ? '' : it.id; dgRender(); });
      knop(t('Kopiëren'), 'mini', () => Android.diagnoseKopieer(it.id));
      const st = knop(it.verstuurd ? t('Verstuurd') : dg.bezig === it.id ? t('Versturen…') : t('Versturen'), 'mini', () => dgStuur(it, s));
      st.disabled = !s.klaar || !!it.verstuurd || !!dg.bezig;
      if (s.kan === false) st.hidden = true; // de Play-versie verstuurt niets
    }
    rij.appendChild(knoppen);
    if (dg.open === it.id && !dgDicht(it)) {
      const pre = document.createElement('pre'); pre.className = 'note dg-tekst'; pre.setAttribute('translate', 'no'); pre.textContent = Android.diagnoseTekst(it.id); rij.appendChild(pre);
    }
    lijst.appendChild(rij);
  });
  const au = $('#st-diag-auto'); au.closest('.sw').style.display = s.kan === false ? 'none' : '';
  if (s.kan === false) $('#st-diag-waar').textContent = t('Deze versie verstuurt geen diagnose. Kopiëren kan wel.');
  au.classList.toggle('on', !!s.auto); au.setAttribute('aria-checked', s.auto ? 'true' : 'false');
  $('#st-diag-sub').textContent = s.auto ? (s.klaar ? t('Aan: elke nieuwe diagnose gaat meteen naar {repo}', { repo: s.repo }) : t('Aan, maar nog geen bestemming: er gaat niets weg')) : t('Uit: er gaat niets weg zonder dat jij erop tikt');
  $('#st-diag-wis').disabled = !items.length;
  const weg = $('#st-diag-verstuurd'), v = s.verstuurd || [];
  weg.textContent = '';
  if (!v.length) weg.textContent = t('Nog niets. Hier staat straks wanneer wat verstuurd is.');
  v.forEach(x => { const p = document.createElement('p'); p.textContent = fmtDT(x.t) + ' · ' + dgToolNaam(x.tool) + (x.n ? ' · ' + tn(x.n, 'Eén teken', '{n} tekens') : ''); weg.appendChild(p); });
}
function dgStuur(it, s){
  askConfirm(t('Diagnose versturen?'), t('De hele tekst die je bij Bekijken ziet ({n} tekens) gaat naar je privé repository {repo}. Er kunnen namen, adressen en nummers in staan.', { n: it.tekens, repo: s.repo }), t('Versturen'), () => {
    dg.bezig = it.id; dgRender(); Android.diagnoseStuur(it.id);
  });
}
window.onDiagnose = function(e){ dg.bezig = ''; toast(e ? t(e) : t('Verstuurd ✓')); if (stOn()) { dgRender(); fcRender(); } };
function dgAuto(knop){
  const aan = !knop.classList.contains('on'), s = stJson(Android.diagnoseStaat());
  if (!aan) { Android.diagnoseAuto(false); dgRender(); fcRender(); return; }
  askConfirm(t('Diagnose vanzelf sturen?'), t('Elke nieuwe diagnose gaat dan zonder vraag naar je privé repository {repo}, met de inhoud erbij (zoals de gelezen tekst van een rekening). Je kunt het hier altijd weer uitzetten.', { repo: s.repo || '…' }), t('Aanzetten'), () => {
    Android.diagnoseAuto(true); dgRender(); fcRender();
  });
}
function dgWis(){ Android.diagnoseWis(); dg.open = ''; dg.vrij = ''; dgRender(); fcRender(); toast(t('Diagnose gewist')); }
window.onStatSent = function(e){ const b = $('#st-stat-send'); if (b) b.textContent = t('Nu versturen'); toast(e ? t(e) : t('Verstuurd ✓')); if (stOn()) { stStatRender(); fcRender(); } };
/* Foutlogboek vanzelf naar de eigen privé repository (Logboek.java): standaard aan zodra repository en sleutel er zijn */
function stLogRender(){
  const kaart = $('#st-log'); if (!kaart) return;
  if (typeof Android.logboekStaat !== 'function') { for (const id of ['#st-log', '#st-log-uitleg', '#st-log-nu', '#st-log-wat']) $(id).style.display = 'none'; return; }
  const s = stJson(Android.logboekStaat());
  for (const id of ['#st-log', '#st-log-uitleg', '#st-log-nu', '#st-log-wat']) $(id).style.display = s.kan === false ? 'none' : '';
  const knop = $('#st-log-aan'); knop.classList.toggle('on', !!s.aan); knop.setAttribute('aria-checked', s.aan ? 'true' : 'false');
  $('#st-log-sub').textContent = !s.aan ? t('Uit: het logboek blijft op de telefoon') : !s.klaar ? t('Aan, maar nog geen repository en sleutel (bij Bestemming): er gaat niets weg')
    : s.resultaat && s.resultaat !== 'ok' ? t('Laatste poging: {uitkomst}', { uitkomst: t(s.resultaat) }) : s.laatst ? t('Laatst bijgewerkt: {wanneer}', { wanneer: fmtD(s.laatst) }) : t('Aan: nieuwe meldingen gaan vanzelf mee');
  $('#st-log-nu').disabled = !(s.aan && s.klaar);
  $('#st-log-voorbeeld').textContent = Android.logboekVoorbeeld() || t('Niets nieuws sinds de vorige keer.');
}
function stLogAan(knop){ const aan = !knop.classList.contains('on'); Android.logboekAan(aan); stLogRender(); fcRender(); toast(aan ? t('Foutlogboek gaat vanzelf mee') : t('Foutlogboek blijft op de telefoon')); }
function stLogNu(){ const b = $('#st-log-nu'); b.disabled = true; uiWachtTekst($('#st-log-sub'), t('Versturen…')); Android.logboekNu(); }
window.onLogboek = function(e){ toast(e ? t(e) : t('Logboek verstuurd ✓')); if (stOn()) { stLogRender(); dgRender(); fcRender(); } };
/* ---------- Foutrapport en statistieken: het overzicht (wat er vanzelf gaat) en de keuzes (FoutInstellingen.java) ---------- */
function fcStaat(){ return typeof Android.foutInstellingen === 'function' ? stJson(Android.foutInstellingen()) : {}; }
const FC_ELKE = () => ({ 1: t('elke dag'), 7: t('elke week'), 30: t('elke maand') });
const FC_TEMPO = () => ({ snel: t('snel'), uur: t('hooguit eens per uur'), dag: t('hooguit eens per dag') });
function fcTg(el, aan){ el.classList.toggle('on', !!aan); el.setAttribute('aria-checked', aan ? 'true' : 'false'); }
function fcRender(i){
  const kaart = $('#fc-kaart'); if (!kaart) return;
  const heeft = f => typeof Android[f] === 'function', f = fcStaat(), keuzes = f.elke !== undefined; // een oudere app kent de keuzes nog niet
  const s = heeft('statState') ? stJson(Android.statState()) : {}, l = heeft('logboekStaat') ? stJson(Android.logboekStaat()) : {}, d = heeft('diagnoseStaat') ? stJson(Android.diagnoseStaat()) : {};
  const kan = heeft('statState') && l.kan !== false, doel = !!(s.repo && s.token);
  for (const id of ['setcrashdoel', 'setcrashlog', 'setcrashstat', 'setcrashdiag', 'setcrashweg']) { const r = $('#fc-lijst [onclick="show(\'' + id + '\')"]'); if (r) r.hidden = !kan && id !== 'setcrashdiag' || (id === 'setcrashdiag' && !heeft('diagnoseStaat')); }
  kaart.hidden = !kan;
  $('#fc-wifi-rij').style.display = keuzes ? '' : 'none';
  for (const [id, k] of [['#fc-tempo', 'tempo'], ['#fc-elke', 'elke'], ['#fc-bewaar', 'diagBewaar']]) { const e = $(id); e.style.display = keuzes ? '' : 'none'; const lb = e.previousElementSibling; if (lb && lb.tagName === 'LABEL') lb.style.display = e.style.display; if (keuzes) e.value = String(f[k]); }
  $('#fc-leg-rij').style.display = keuzes ? '' : 'none'; $('#fc-test').style.display = heeft('statTest') ? '' : 'none';
  if (keuzes) { fcTg($('#fc-wifi'), f.wifi); fcTg($('#fc-leg'), f.diagLeg); }
  $('#fc-wifi-sub').textContent = f.wifi ? t('Aan: vanzelf versturen wacht op wifi; met de hand kan altijd') : t('Uit: ook via mobiele data');
  $('#fc-leg-sub').textContent = f.diagLeg === false ? t('Uit: tools leggen niets vast') : t('Aan: tools leggen in het geheugen vast wat er misging');
  // De regels onder elk onderwerp
  const sum = (id, tekst) => { const e = $('#fc-sum-' + id); if (e) e.textContent = tekst; };
  sum('doel', !doel ? (s.repo ? t('{repo} · nog geen sleutel: er gaat niets weg', { repo: s.repo }) : t('Niet ingesteld: er gaat niets weg')) : t('{repo} · sleutel ingesteld', { repo: s.repo }));
  const logAan = l.aan !== false, statAan = s.on !== false, tempo = FC_TEMPO()[f.tempo] || t('snel'), elke = FC_ELKE()[f.elke] || t('elke week');
  sum('log', !logAan ? t('Uit: het logboek blijft op de telefoon') : [t('Aan, {tempo}', { tempo }), l.laatst ? t('laatst {wanneer}', { wanneer: fmtD(l.laatst) }) : '', l.resultaat && l.resultaat !== 'ok' ? t('laatste poging mislukt') : ''].filter(Boolean).join(' · '));
  sum('stat', !statAan ? t('Uit: er wordt niets geteld of verstuurd') : [t('Aan, {elke}', { elke }), s.lastSent ? t('laatst {wanneer}', { wanneer: fmtD(s.lastSent) }) : '', s.lastResult && s.lastResult !== 'ok' ? t('laatste poging mislukt') : ''].filter(Boolean).join(' · '));
  const n = (d.items || []).length;
  sum('diag', [f.diagLeg === false ? t('Vastleggen uit') : (n ? tn(n, 'Eén stuk in het geheugen', '{n} stukken in het geheugen') : t('Niets in het geheugen')), d.auto ? t('vanzelf sturen aan') : t('versturen alleen met de hand')].join(' · '));
  if (i && i.crashes !== undefined) sum('rap', i.crashes ? tn(i.crashes, '{n} fout vastgelegd', '{n} fouten vastgelegd') : t('Geen fouten vastgelegd ✓'));
  const v = (d.verstuurd || [])[0];
  sum('weg', v ? t('Laatst: {wanneer} · {wat}', { wanneer: fmtDT(v.t), wat: dgToolNaam(v.tool) }) : t('Nog niets'));
  // Wat er vanzelf gaat, in één zin per soort
  const regels = [];
  if (!doel) regels.push(t('Er gaat niets weg: stel eerst een bestemming in.'));
  else {
    const wat = [];
    if (logAan) wat.push(t('het foutlogboek ({tempo})', { tempo }));
    if (statAan) wat.push(t('statistieken ({elke})', { elke }));
    if (d.auto) wat.push(t('elke nieuwe diagnose'));
    regels.push(wat.length ? t('Vanzelf naar {repo}: {wat}.', { repo: s.repo, wat: wat.join(', ') }) : t('Er gaat niets vanzelf weg.'));
    if (!d.auto) regels.push(t('Een diagnose gaat alleen weg als jij op Versturen tikt.'));
    if (f.wifi && wat.length) regels.push(t('Alleen via wifi.'));
  }
  const samen = $('#fc-samen'); samen.setAttribute('translate', 'no'); samen.textContent = regels.join(' ');
  $('#fc-doel-stand').textContent = doel ? t('Ingesteld: {repo}. De sleutel staat versleuteld op de telefoon.', { repo: s.repo }) : t('Nog niet ingesteld: zonder repository en sleutel verlaat er niets de telefoon.');
}
function fcZet(sleutel, waarde){
  if (typeof Android.foutZet !== 'function') return;
  const e = Android.foutZet(sleutel, String(waarde));
  if (e) toast(t(e)); else if (sleutel === 'wifi') toast(waarde ? t('Vanzelf versturen alleen via wifi') : t('Vanzelf versturen ook via mobiele data'));
  stLogRender(); dgRender(); fcRender();
}
function fcLeg(knop){
  const aan = !knop.classList.contains('on'), n = (stJson(Android.diagnoseStaat()).items || []).length;
  if (aan || !n) { fcZet('diagLeg', aan); return; }
  askConfirm(t('Vastleggen uitzetten?'), tn(n, 'Het stuk dat nu in het geheugen staat, wordt gewist.', 'De {n} stukken die nu in het geheugen staan, worden gewist.'), t('Uitzetten'), () => { dg.open = ''; dg.vrij = ''; fcZet('diagLeg', false); });
}
function fcTest(){
  const s = stJson(Android.statState());
  if (!(s.repo && s.token)) { $('#fc-test-uit').textContent = t('Vul eerst de repository en de sleutel in en tik op Opslaan.'); return; }
  $('#fc-test').disabled = true; uiWachtTekst($('#fc-test-uit'), t('Verbinding testen…')); Android.statTest();
}
window.onStatTest = function(e){
  const b = $('#fc-test'); if (b) b.disabled = false;
  const uit = $('#fc-test-uit'); if (uit) uit.textContent = e ? t('Niet gelukt: {reden}', { reden: t(e) }) : t('✓ In orde: de repository is privé en de sleutel mag erin schrijven.');
  if (stOn()) { dgRender(); fcRender(); }
};
function stCrashClear(){ askConfirm('Foutrapport wissen?', 'De vastgelegde foutmeldingen worden verwijderd.', 'Wissen', () => { Android.crashClear(); enterSettings(); }); }


/* ---------- Koppelingen: één overzicht van alle diensten buiten de telefoon, met de stand ----------
 * Elke rij gaat naar het scherm waar je de koppeling instelt (en waar de testknop staat, als die er is). Staat de tool
 * van een koppeling op slot (Extra ontgrendelen), dan toont de rij alleen "Op slot", geen accountnaam of adres. */
function kpLijst(){
  const heeft = f => typeof Android[f] === 'function', uit = [];
  const erbij = (id, icon, naam, naar, tool, stand) => {
    if (!document.getElementById('s-' + naar) || (typeof playWeg === 'function' && (playWeg(naar) || (tool && playWeg(tool))))) return;
    const slot = !!tool && typeof tsVerborgen === 'function' && tsVerborgen(tool);
    let s = { aan: false, tekst: '' };
    if (!slot) { try { s = stand(); } catch (e) { s = { aan: false, tekst: t('Stand onbekend') }; } }
    uit.push({ id, icon, naam, naar, slot, aan: !slot && !!s.aan, tekst: slot ? t('Op slot: tik om te ontgrendelen') : s.tekst, eigen: !slot && s.eigen ? s.eigen : '' });
  };
  if (heeft('spotifyState')) erbij('spotify', 'spotify', 'Spotify', 'spotify', 'spotify', () => { const s = rdJson(Android.spotifyState(), {}); return { aan: !!s.linked, tekst: s.linked ? t('Gekoppeld') : t('Niet gekoppeld'), eigen: s.linked ? (s.user || '') : '' }; });
  if (heeft('facturenState')) {
    let faS = null; const fa = () => faS || (faS = rdJson(Android.facturenState(), {})); // zwaar: hooguit één keer per lijst
    erbij('google', 'globe', t('Google (Gmail en Drive)'), 'facgoogle', 'facturen', () => { const g = fa().drive || {}; return { aan: !!g.gekoppeld, tekst: g.gekoppeld ? [g.rechtGmail ? t('Gmail lezen') : '', g.rechtDrive ? 'Drive' : '', g.rechtGmailSend ? t('mail versturen') : ''].filter(Boolean).join(', ') || t('Gekoppeld') : t('Niet gekoppeld'), eigen: g.gekoppeld ? (g.gebruiker || '') : '' }; });
    erbij('ec', 'mail', 'Eigen Cloud', 'facec', 'facturen', () => { const e = fa().ec || {}, aan = !!(e.url && e.heeftWachtwoord); return { aan, tekst: aan ? t('Gekoppeld') : t('Niet gekoppeld'), eigen: aan ? String(e.url).replace(/^https:\/\//, '') : '' }; });
    erbij('smtp', 'message', t('Eigen mailserver (SMTP)'), 'facsmtp', 'facturen', () => { const m = fa().smtp || {}; return { aan: !!m.host, tekst: m.host ? t('Ingesteld') : t('Niet ingesteld'), eigen: m.host || '' }; });
  }
  if (heeft('settingsInfo')) erbij('audd', 'music', t('Muziek herkennen (AudD)'), 'setmusic', '', () => { const i = stJson(Android.settingsInfo()); return { aan: !!i.audd, tekst: i.audd ? t('Sleutel ingesteld') : t('Nog geen sleutel ingesteld') }; });
  if (heeft('statState')) erbij('stat', 'upload', t('Statistieken (je eigen GitHub-repository)'), 'setcrash', '', () => { const s = stJson(Android.statState()), aan = s.on !== false && !!(s.repo && s.token);
    return { aan, tekst: s.on === false ? t('Uit: er wordt niets geteld of verstuurd') : !(s.repo && s.token) ? t('Aan, maar nog geen bestemming: er gaat niets weg') : t('Ingesteld'), eigen: aan ? s.repo : '' }; });
  return uit;
}
function kpNaar(naar){ jumpTo(naar); }
function kpRender(){
  const box = $('#kp-list'); if (!box) return;
  const l = kpLijst();
  box.innerHTML = l.map(k => '<button class="strow" data-naar="' + esc(k.naar) + '" data-id="' + esc(k.id) + '" data-aan="' + (k.aan ? 1 : 0) + '"><span class="stic">' + uiIcon(k.icon) + '</span><span class="sttxt"><b>' + esc(k.naam) + '</b><small>' + esc(k.tekst)
    + (k.eigen ? ' · <span translate="no">' + esc(k.eigen) + '</span>' : '') + '</small></span><span class="ui-icon stchev" data-icon="chevron-right" aria-hidden="true"></span></button>').join('');
  box.querySelectorAll('.strow').forEach(b => b.addEventListener('click', () => kpNaar(b.dataset.naar)));
}
function kpSamenvatting(){ try { const l = kpLijst(), n = l.filter(k => k.aan).length; if (l.some(k => k.slot)) return t('Een deel staat op slot'); return n ? tn(n, 'Eén koppeling in gebruik', '{n} koppelingen in gebruik') : t('Nog niets gekoppeld'); } catch (e) { return ''; } }
