/* ---------- Gebruik: schermtijd, ontgrendelingen, telefoonfuncties, meldingen en opladen ---------- */
let gbTabV = 'overzicht', gbPeriodV = 'today', gbShotPoll = null, gbShotWait = false, gbPhotoGen = 0;

function gbState(){ return rdJson(Android.gebruikState(), {}) || {}; }
function gbSet(){ return gbState().set || {}; }
function gbSaveOne(k, v){ try { Android.gebruikSave(JSON.stringify({ [k]: v })); } catch (e) {} }

function gbDur(ms){ ms = Math.max(0, ms || 0); const m = Math.round(ms / 60000); if (m < 1) return Math.round(ms / 1000) + ' s'; return m < 60 ? m + ' min' : Math.floor(m / 60) + ' u' + (m % 60 ? ' ' + (m % 60) + ' min' : ''); }
function gbTime(t){ try { return new Date(t).toLocaleTimeString('nl-NL', { hour: '2-digit', minute: '2-digit' }); } catch (e) { return ''; } }
function gbDay(t){ try { return new Date(t).toLocaleDateString('nl-NL', { weekday: 'short', day: 'numeric', month: 'short' }); } catch (e) { return ''; } }
const GB_FUNC = { camera: ['camera', '📷'], mic: ['microfoon', '🎙'], loc: ['locatie', '📍'], bt: ['Bluetooth', '🔵'], wifi: ['wifi', '📶'], data: ['mobiele data', '📡'] };

function enterGebruik(){
  const s = gbState();
  const off = s.set && s.set.collect === false;
  $('#gb-start').style.display = off ? '' : 'none';
  const perm = $('#gb-perm');
  if (!off && !s.access) {
    perm.style.display = '';
    slHtml(perm, '<h2>Toegang tot gebruik</h2><p class="note">Gebruik heeft de bijzondere toegang "Toegang tot gebruik" nodig om te zien welke apps je gebruikt en wanneer. Die geef je zelf in Android.</p><button class="big" onclick="Android.gebruikOpenAccess()">Toegang geven</button>');
  } else perm.style.display = 'none';
  gbTab(gbTabV);
}
function gbStart(){ gbSaveOne('collect', true); enterGebruik(); }

function gbTab(name){
  gbTabV = name;
  document.querySelectorAll('#gb-tabs .gb-tab').forEach(b => { const on = b.dataset.tab === name; b.classList.toggle('on', on); b.setAttribute('aria-selected', on ? 'true' : 'false'); });
  ['overzicht', 'ontgrendel', 'functies', 'meldingen', 'fotos'].forEach(p => { const el = $('#gb-' + p); if (el) el.style.display = p === name ? '' : 'none'; });
  if (name === 'overzicht') gbRenderOverview();
  else if (name === 'ontgrendel') gbRenderUnlocks();
  else if (name === 'functies') gbRenderFuncs();
  else if (name === 'meldingen') gbRenderNotifs();
  else if (name === 'fotos') gbRenderPhotoTab();
}

function gbPeriod(p){ gbPeriodV = p; document.querySelectorAll('#gb-period button').forEach(b => { const on = b.dataset.p === p; b.classList.toggle('on', on); b.setAttribute('aria-pressed', on ? 'true' : 'false'); }); gbRenderOverview(); }

function gbRenderOverview(){
  const o = rdJson(Android.gebruikOverview(gbPeriodV), {}) || {};
  $('#gb-total').textContent = gbDur(o.total || 0);
  $('#gb-unlocks').textContent = (o.unlocks || 0);
  slHtml($('#gb-chart'), gbChart(o.chart || [], gbPeriodV));
  const apps = o.apps || [];
  const longP = gbPeriodV === 'maand' || gbPeriodV === 'jaar';
  if (!apps.length) { slHtml($('#gb-apps'), '<p class="note">' + (longP ? 'Per app zie je bij Vandaag of Week; hier gaat het om de totale schermtijd per dag of week.' : 'Nog niets te zien voor deze periode.') + '</p>'); return; }
  gbImgs = [];
  slHtml($('#gb-apps'), apps.map(a =>
    '<div class="gb-app">' + gbIcon(a) + '<div class="gb-app-t"><b>' + esc(a.name || a.pkg) + '</b><small>' + (a.opens || 0) + '× geopend</small></div><span class="gb-app-d">' + gbDur(a.ms) + '</span></div>'
  ).join(''));
  gbPaint('#gb-apps');
}

/* De bronnen van pictogrammen zet JS na het renderen (geen letterlijke bron-attributen in de code: de voorlees-scanner
   van de browser probeert zo'n letterlijke tekst anders als bestand op te halen). */
let gbImgs = [];
function gbIcon(a){ if (a && a.icon) { gbImgs.push(a.icon); return '<img class="gb-icon" alt="" data-gi="' + (gbImgs.length - 1) + '">'; } return '<span class="gb-icon gb-icon-x" aria-hidden="true"></span>'; }
function gbPaint(sel){ const el = $(sel); if (!el) return; el.querySelectorAll('img[data-gi]').forEach(im => { const i = +im.dataset.gi; if (gbImgs[i]) im.src = gbImgs[i]; im.removeAttribute('data-gi'); }); }

function gbChart(data, period){
  if (!data.length) return '<p class="note">Geen gegevens.</p>';
  const W = 320, H = 110, B = 18, n = data.length, bw = (W) / n, max = Math.max(1, ...data);
  let bars = '', labs = '';
  for (let i = 0; i < n; i++) {
    const h = Math.max(1, (data[i] / max) * (H - B - 4));
    bars += '<rect class="gb-bar" x="' + (i * bw + bw * 0.12).toFixed(1) + '" y="' + (H - B - h).toFixed(1) + '" width="' + (bw * 0.76).toFixed(1) + '" height="' + h.toFixed(1) + '" rx="2"/>';
  }
  const lab = (i, txt) => { labs += '<text class="gb-lab" x="' + (i * bw + bw / 2).toFixed(1) + '" y="' + (H - 5) + '">' + txt + '</text>'; };
  let aria = 'per uur';
  if (period === 'week') { const now = new Date(); aria = 'per dag'; for (let i = 0; i < n; i++) { const d = new Date(now.getTime() - (n - 1 - i) * 86400000); lab(i, ['zo','ma','di','wo','do','vr','za'][d.getDay()]); } }
  else if (period === 'maand') { aria = 'per dag'; const now = new Date(); for (let i = 0; i < n; i += 7) { const d = new Date(now.getTime() - (n - 1 - i) * 86400000); lab(i, d.getDate() + '/' + (d.getMonth() + 1)); } }
  else if (period === 'jaar') { aria = 'per week'; for (let i = 0; i < n; i += 8) lab(i, (n - i) + 'w'); }
  else { for (let i = 0; i < n; i += 6) lab(i, i); }
  return '<svg viewBox="0 0 ' + W + ' ' + H + '" role="img" aria-label="Schermtijd ' + aria + '">' + bars + labs + '</svg>';
}

function gbBadge(b){
  const fail = b.fails ? ' <span class="gb-badge gb-fail">na ' + b.fails + '× mis</span>' : '';
  if (b.how === 'pincode') return '<span class="gb-badge gb-pin">met pincode</span>' + fail;
  if (b.how === 'mislukt') return '<span class="gb-badge gb-fail">mislukt ×' + (b.fails || 1) + '</span>';
  if (b.how === 'onbekend') return '<span class="gb-badge">ontgrendeld (manier onbekend)</span>' + fail;
  return '<span class="gb-badge">anders</span>' + fail;
}

function gbRenderUnlocks(){
  const o = rdJson(Android.gebruikUnlocks('', ''), {}) || {};
  const blocks = o.blocks || [];
  if (!blocks.length) { slHtml($('#gb-blocks'), '<p class="note">Nog geen ontgrendelingen vastgelegd. Zet eventueel apparaatbeheer aan bij de instellingen om ook pincode-ontgrendelingen en mislukte pogingen te zien.</p>'); return; }
  gbImgs = [];
  slHtml($('#gb-blocks'), blocks.map(b => {
    const apps = (b.apps || []).map(a => '<div class="gb-step">' + gbIcon(a) + '<span>' + esc(a.name || a.pkg) + '</span><small>' + gbDur(a.dur) + '</small></div>').join('');
    const photo = b.photo ? '<button class="gb-thumb" onclick="gbShowPhoto(\'' + esc(b.photo) + '\')" aria-label="Foto bekijken"><span class="ui-icon" data-icon="camera" aria-hidden="true"></span> foto</button>' : '';
    return '<div class="card gb-block"><div class="gb-block-h"><span class="gb-when">' + gbDay(b.t) + ' ' + gbTime(b.t) + '</span>' + gbBadge(b) + photo + '</div>' +
      (apps || '<p class="note">Geen app-gebruik geregistreerd.</p>') + '</div>';
  }).join(''));
  gbPaint('#gb-blocks');
}

function gbRenderFuncs(){
  const o = rdJson(Android.gebruikFunctions('', ''), {}) || {};
  const evs = o.events || [];
  const hint = !gbState().notifOn ? '<div class="card perm"><p class="note">Zet Meldingsgeschiedenis (meldingentoegang) aan, dan houdt Tools camera, microfoon en verbindingen bij zolang die dienst leeft. Zonder dat wordt het alleen gezien terwijl de app zelf openstaat.</p><button class="big ghost" onclick="show(\'history\')">Naar Meldingsgeschiedenis</button></div>' : '';
  if (!evs.length) { slHtml($('#gb-funcs'), hint + '<div class="card"><p class="note">Nog niets vastgelegd. Camera, microfoon en verbindingen worden alleen gezien zolang een onderdeel van de app leeft (Android staat niet meer toe).</p></div>'); return; }
  slHtml($('#gb-funcs'), hint + '<div class="card">' + evs.map(e => {
    const f = GB_FUNC[e.kind] || [e.kind, '•'];
    return '<div class="gb-func"><span class="gb-func-i" aria-hidden="true">' + f[1] + '</span><div class="gb-func-t"><b>' + esc(f[0]) + '</b>' + (e.name ? '<small>waarschijnlijk via ' + esc(e.name) + '</small>' : '') + '</div><span class="gb-func-w">' + gbDay(e.t) + ' ' + gbTime(e.t) + '</span></div>';
  }).join('') + '</div>');
}

function gbRenderNotifs(){
  const o = rdJson(Android.gebruikNotifs('', ''), {}) || {};
  const perm = $('#gb-notif-perm');
  if (!o.notifOn) {
    perm.style.display = '';
    slHtml(perm, '<h2>Meldingen tellen</h2><p class="note">Om meldingen per app te tellen moet de meldingsgeschiedenis aanstaan (Meldingsgeschiedenis). Dan telt Gebruik alleen hoeveel meldingen per app, nooit de inhoud.</p><button class="big ghost" onclick="show(\'history\')">Naar Meldingsgeschiedenis</button>');
  } else perm.style.display = 'none';
  const apps = o.apps || [];
  gbImgs = [];
  slHtml($('#gb-notifs'), apps.length ? apps.map(a => '<div class="gb-app">' + gbIcon(a) + '<div class="gb-app-t"><b>' + esc(a.name || a.pkg) + '</b></div><span class="gb-app-d">' + (a.n || 0) + '</span></div>').join('') : '<p class="note">Geen meldingen in deze periode.</p>');
  gbPaint('#gb-notifs');
  const charge = o.charge || [], reboots = o.reboots || [];
  let h = '';
  if (reboots.length) h += '<p class="note"><b>Opnieuw opgestart:</b> ' + reboots.slice(0, 20).map(r => gbDay(r.t) + ' ' + gbTime(r.t)).join(', ') + '</p>';
  if (charge.length) h += '<div>' + charge.slice(0, 40).map(c => '<div class="gb-func"><span class="gb-func-i" aria-hidden="true">' + (c.on ? '🔌' : '🔋') + '</span><div class="gb-func-t"><b>' + (c.on ? 'Lader erin' : 'Lader eruit') + '</b></div><span class="gb-func-w">' + gbDay(c.t) + ' ' + gbTime(c.t) + '</span></div>').join('') + '</div>';
  slHtml($('#gb-charge'), h || '<p class="note">Nog niets over opladen of opnieuw opstarten.</p>');
}

/* ---------- foto bekijken ---------- */
let gbLightKey = null;
function gbCloseLight(){ const ov = $('#gb-light'); if (ov) ov.remove(); if (gbLightKey) { document.removeEventListener('keydown', gbLightKey); gbLightKey = null; } }
function gbShowPhoto(id){
  const url = Android.gebruikPhoto(id);
  if (!url) { toast('Foto niet gevonden'); return; }
  gbCloseLight();
  const ov = document.createElement('div'); ov.id = 'gb-light'; ov.className = 'gb-light';
  ov.onclick = gbCloseLight;
  const img = document.createElement('img'); img.alt = 'Foto bij ontgrendeling'; img.src = url; ov.appendChild(img);
  const x = document.createElement('button'); x.className = 'gb-light-x'; x.setAttribute('aria-label', 'Sluiten'); x.textContent = '✕';
  x.onclick = e => { e.stopPropagation(); gbCloseLight(); };
  ov.appendChild(x);
  document.body.appendChild(ov);
  gbLightKey = e => { if (e.key === 'Escape') gbCloseLight(); };
  document.addEventListener('keydown', gbLightKey);
}
/* De hardware-terugknop sluit eerst de fotoweergave (goBack roept dit via window). */
window.gbBack = function(){ if ($('#gb-light')) { gbCloseLight(); return true; } return false; };

/* ---------- instellingen ---------- */
function enterGebruikSet(){
  const s = gbState(), set = s.set || {}, caps = s.photoCaps || {};
  gbTgEl('#gb-collect', set.collect !== false);
  gbChipSel('#gb-keep', set.keep || 365);
  gbChipSel('#gb-detail', set.detail || 60);
  gbChipSel('#gb-pafter', set.photoAfter || 3);
  gbChipSel('#gb-pkeep', set.photoKeep || 30);
  gbChipSel('#gb-punlock', set.punlock || 'uit');
  gbTgEl('#gb-admin', s.admin);
  gbTgEl('#gb-pfail', set.photoFail);
  $('#gb-admin-off').style.display = s.admin ? '' : 'none';
  $('#gb-access-note').textContent = s.access ? 'Toegang tot gebruik staat aan.' : 'Nog niet gegeven: zonder deze toegang ziet Gebruik geen schermtijd.';
  $('#gb-a11y-note').textContent = s.a11y ? 'De toegankelijkheidsdienst staat aan: "met pincode" wordt herkend.' : 'De toegankelijkheidsdienst staat uit: ontgrendelingen heten dan "onbekend", en "foto alleen met pincode" werkt niet. Zet hem aan bij Automatiseringen.';
  // Eerlijke status + knoppen om te repareren wat ontbreekt
  const proef = s.lastShot === 'bezig' ? ' Bezig met de proefopname…' : (s.lastShot && s.lastShot.indexOf('mislukt') === 0 ? ' Laatste proef: ' + s.lastShot.slice(9) : (s.lastShot && s.lastShot.indexOf('ok|') === 0 ? ' Laatste proef: gelukt.' : ''));
  const st = $('#gb-photo-status');
  st.textContent = (caps.works ? 'Werkt op deze telefoon. ' : 'Werkt mogelijk niet. ') + (caps.why || '') + proef;
  st.classList.toggle('bad', !caps.works);
  const fix = [];
  if (!caps.camera) fix.push(['Cameratoestemming geven', "Android.permRequest('camera')"]);
  if (!caps.overlay) fix.push(['Boven andere apps toestaan', 'Android.gebruikOpenOverlay()']);
  if (!s.a11y) fix.push(['Toegankelijkheid aanzetten (voor "alleen met pincode")', "show('auto')"]);
  slHtml($('#gb-photo-fix'), fix.map(f => '<button class="big ghost" onclick="' + f[1] + '">' + esc(f[0]) + '</button>').join(''));
  gbRenderPhotos();
}

function gbChipSel(sel, v){ document.querySelectorAll(sel + ' button').forEach(b => { const on = String(b.dataset.v) === String(v); b.classList.toggle('on', on); b.setAttribute('aria-checked', on ? 'true' : 'false'); }); }
function gbTgEl(sel, on){ const e = $(sel); if (!e) return; e.classList.toggle('on', !!on); e.setAttribute('aria-checked', on ? 'true' : 'false'); }

function gbTgCollect(){ const cur = gbSet().collect !== false; gbSaveOne('collect', !cur); enterGebruikSet(); }
function gbKeep(v){ gbSaveOne('keep', v); enterGebruikSet(); }
function gbDetail(v){ gbSaveOne('detail', v); enterGebruikSet(); }
function gbPhotoAfter(v){ gbSaveOne('photoAfter', v); enterGebruikSet(); }
function gbPhotoKeep(v){ gbSaveOne('photoKeep', v); enterGebruikSet(); }
function gbPhotoTg(k){ const cur = !!gbSet()[k]; gbSaveOne(k, !cur); enterGebruikSet(); }
function gbPunlock(v){ gbSaveOne('punlock', v); enterGebruikSet(); }
function gbAdmin(){ if (gbState().admin) gbAdminOff(); else Android.gebruikAdmin(true); }
function gbAdminOff(){ askConfirm('Apparaatbeheer uitzetten?', 'Daarna ziet Gebruik mislukte pogingen niet meer, en kan de app weer verwijderd worden.', 'Uitzetten', () => Android.gebruikAdmin(false)); }

/* Proefopname: eerst cameratoestemming (anders vraagt hij die en gaat daarna vanzelf verder), dan wachten op de uitkomst. */
function gbTestShot(){
  const caps = gbState().photoCaps || {};
  if (caps.camera === false) {
    gbShotWait = true;
    $('#gb-test-res').textContent = 'Geef de app toegang tot de camera; daarna start de proefopname vanzelf.';
    Android.permRequest('camera');
    return;
  }
  gbShotWait = false;
  Android.gebruikTestShot();
  $('#gb-test-res').textContent = 'Proefopname bezig…';
  clearTimeout(gbShotPoll);
  const t0 = Date.now();
  const poll = () => {
    const ls = gbState().lastShot || '';
    if (ls.indexOf('ok|') === 0) { $('#gb-test-res').textContent = 'Gelukt: de foto staat bij Gebruik → Foto\'s.'; toast('Proefopname gelukt'); if (current === 'gebruikset') enterGebruikSet(); return; }
    if (ls.indexOf('mislukt') === 0) { $('#gb-test-res').textContent = 'Mislukt: ' + (/time-?out/i.test(ls) ? 'de camera reageerde niet op tijd' : ls.slice(9)); if (current === 'gebruikset') enterGebruikSet(); return; }
    if (Date.now() - t0 > 12000) { $('#gb-test-res').textContent = 'Geen uitkomst: de camera reageerde niet. Probeer het opnieuw.'; return; }
    gbShotPoll = setTimeout(poll, 600);
  };
  gbShotPoll = setTimeout(poll, 600);
}
function gbOpenPhotos(){ gbTabV = 'fotos'; show('gebruik'); }
// Na het verlaten van Gebruik niet meer bij Foto's beginnen (dan zou elke keer alles ontsleuteld worden)
function gbLeave(){ try { if (gbTabV === 'fotos') gbTabV = 'overzicht'; gbPhotoGen++; } catch (e) { /* show() kan al lopen voordat dit script geladen is */ } }

/* Tabblad Foto's: echte miniaturen (ontsleuteld in de app), nieuwste eerst. */
function gbPhotoLabel(r){ return r === 'mislukt' ? 'Mislukte poging' : r === 'proef' ? 'Proefopname' : 'Ontgrendeld'; }
function gbRenderPhotoTab(){
  const o = rdJson(Android.gebruikPhotos(), {}) || {}, ph = (o.photos || []).slice(0, 60);
  const box = $('#gb-fotos-list');
  if (!ph.length) { slHtml(box, '<div class="card"><p class="note">Nog geen foto\'s. Zet bij Instellingen "Foto bij ontgrendelen" of "Foto bij een mislukte poging" aan, of maak een proefopname.</p></div>'); return; }
  slHtml(box, '<div class="gb-grid" role="list">' + ph.map(p => '<div role="listitem"><button class="gb-tile" data-id="' + esc(p.id) + '" onclick="gbShowPhoto(this.dataset.id)" aria-label="' + esc(gbPhotoLabel(p.reason) + ', ' + gbDay(p.t) + ' ' + gbTime(p.t)) + '"><span class="gb-tile-img"></span><small><b>' + esc(gbPhotoLabel(p.reason)) + '</b>' + esc(gbDay(p.t) + ' ' + gbTime(p.t)) + '</small></button></div>').join('') + '</div>' +
    ((o.photos || []).length > ph.length ? '<p class="note">De nieuwste ' + ph.length + ' van ' + o.photos.length + ' foto\'s.</p>' : ''));
  // Miniaturen één voor één ontsleutelen (het scherm blijft vlot)
  const tiles = Array.from(box.querySelectorAll('.gb-tile')), gen = ++gbPhotoGen;
  const next = () => { const t = tiles.shift(); if (!t || gen !== gbPhotoGen || !t.isConnected || current !== 'gebruik' || gbTabV !== 'fotos') return; const url = Android.gebruikPhoto(t.dataset.id); const sp = t.querySelector('.gb-tile-img'); if (url && sp) { const im = document.createElement('img'); im.alt = ''; im.decoding = 'async'; im.src = url; sp.appendChild(im); } setTimeout(next, 0); };
  setTimeout(next, 0);
}

function gbRenderPhotos(){
  const o = rdJson(Android.gebruikPhotos(), {}) || {}, ph = o.photos || [];
  $('#gb-photos-clear').style.display = ph.length ? '' : 'none';
  $('#gb-photos-open').textContent = ph.length ? 'Foto\'s bekijken (' + ph.length + ')' : 'Foto\'s bekijken';
  // De laatste drie als snelle link; alles staat bij Gebruik → Foto's
  slHtml($('#gb-photos'), ph.length ? '<div class="gb-photos">' + ph.slice(0, 3).map(p => '<button class="gb-thumb" data-id="' + esc(p.id) + '" onclick="gbShowPhoto(this.dataset.id)"><span class="ui-icon" data-icon="camera" aria-hidden="true"></span>' + esc(gbDay(p.t) + ' ' + gbTime(p.t) + ' · ' + gbPhotoLabel(p.reason)) + '</button>').join('') + '</div>' : '');
}

function gbClearPhotos(){ askConfirm('Foto\'s wissen?', 'Alle foto\'s van Gebruik verwijderen.', 'Wissen', () => { Android.gebruikClearPhotos(); gbRenderPhotos(); toast('Foto\'s gewist'); }); }
function gbClearAll(){ askConfirm('Alles wissen?', 'Alle gebeurtenissen, samenvattingen en foto\'s van Gebruik verwijderen.', 'Wissen', () => { Android.gebruikClearAll(); toast('Alles van Gebruik gewist'); if (current === 'gebruikset') enterGebruikSet(); }); }

// Na het geven van cameratoestemming: de proefopname waar je om vroeg alsnog maken
(function(){ const prev = window.onSettingsChanged; window.onSettingsChanged = function(msg){ if (typeof prev === 'function') prev(msg);
  if (current === 'gebruikset') { enterGebruikSet(); gbShotResume(); } }; })();
// Terug uit de app-instellingen (Android vroeg niets meer): ook dan verder of duidelijk zeggen wat er mis is
(function(){ const prev = window.onResumeApp; window.onResumeApp = function(){ if (typeof prev === 'function') prev(); if (current === 'gebruikset') gbShotResume(); }; })();
function gbShotResume(){
  if (!gbShotWait) return;
  if ((gbState().photoCaps || {}).camera) { gbTestShot(); return; }
  gbShotWait = false;
  $('#gb-test-res').textContent = 'Geen cameratoestemming gekregen. Tik nog eens op Proefopname; vraagt Android niets meer, dan opent de app-instellingen: zet daar Toestemmingen → Camera aan.';
}

window.onGebruik = function(){ if (current === 'gebruik') enterGebruik(); else if (current === 'gebruikset') enterGebruikSet(); };
