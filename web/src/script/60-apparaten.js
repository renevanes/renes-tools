/* ---------- Apparaten in de buurt: Bluetooth, wifi-netwerken, apparaten op je wifi, trackers, bekend ----------
 * (De toestand staat in var, niet let: show() roept apLeave al aan voordat dit bestand geladen is.)
 * Java zoekt (ApparatenScan, ApparatenLan, ApparatenJob) en rekent (Apparaten: getest); hier de schermen. Alles blijft
 * op de telefoon. Zoeken gebeurt alleen op aanvraag en stopt vanzelf; weg van deze schermen stopt alles. */
var apSt = {}, apTabNow = store.get('apTab', 'bt'), apPollT = null, apBtData = null, apWifiData = null, apLanData = null, apTrData = null, apHistData = null;
var apDev = null, apFindT = null, apFinding = false, apSpark = [], apAudio = null, apBeepT = null, apLastTrend = 0, apFindLevel = 0;
const AP_SCREENS = ['apparaten', 'apdev', 'apset'];
const AP_TYPES = { telefoon: ['Telefoon', 'smartphone'], oortjes: ['Oortjes', 'headphones'], koptelefoon: ['Koptelefoon', 'headphones'], speaker: ['Speaker', 'volume-high'],
  horloge: ['Horloge', 'clock'], tv: ['Tv of mediaspeler', 'cast'], auto: ['Auto', 'car'], computer: ['Computer', 'grid'], tracker: ['Tracker', 'pin'], invoer: ['Toetsenbord of muis', 'grid'],
  sensor: ['Sensor', 'info'], gezondheid: ['Gezondheid', 'info'], sport: ['Sport', 'route'], printer: ['Printer', 'document'], lamp: ['Lamp', 'sun'], baken: ['Baken', 'pin'],
  spel: ['Spelcomputer', 'play'], hoortoestel: ['Hoortoestel', 'volume'], afstandsbediening: ['Afstandsbediening', 'grid'], apple: ['Apple-apparaat', 'smartphone'],
  router: ['Router', 'wifi'], nas: ['NAS (opslag)', 'folder'], camera: ['Camera', 'camera'], slim: ['Slim apparaat', 'automation'], apparaat: ['Apparaat', 'nearby'] };
Object.assign(BACK, { apparaten: 'home', apdev: 'apparaten', apset: 'apparaten' });

function apLoad(){ apSt = rdJson(Android.apState(), {}) || {}; apSt.set = apSt.set || {}; apSt.perms = apSt.perms || {}; return apSt; }
function setAp(){ return apLoad().set; }
function apType(t){ return AP_TYPES[t] || AP_TYPES.apparaat; }
function apIcon(t, tr){ return '<span class="ic' + (tr ? ' tr' : '') + '" aria-hidden="true">' + uiIcon(apType(t)[1]) + '</span>'; }
/* Afstand in woorden: altijd "ongeveer" */
function apDist(m){ if (m == null || m < 0) return ''; return m < 1 ? 'dichtbij (minder dan 1 m)' : 'ongeveer ' + ( m < 10 ? String(Math.round(m * 2) / 2).replace('.', ',') + ' m' : Math.round(m) + ' m'); }
function apBars(rssi){ const n = rssi >= -60 ? 4 : rssi >= -70 ? 3 : rssi >= -80 ? 2 : 1; return '<span class="sig" role="img" aria-label="Signaal ' + n + ' van 4">' + [1, 2, 3, 4].map(i => '<i' + (i <= n ? ' class="on"' : '') + '></i>').join('') + '</span>'; }
function apBadge(t, cls){ return '<span class="ap-badge' + (cls ? ' ' + cls : '') + '">' + esc(t) + '</span>'; }
function apAgo(t){
  if (!t) return '';
  const s = Math.max(0, (Date.now() - t) / 1000), d = new Date(t);
  if (s < 90) return 'net';
  if (s < 3600) return Math.round(s / 60) + ' min geleden';
  const today = new Date(); today.setHours(0, 0, 0, 0);
  const hm = d.toLocaleTimeString('nl-NL', { hour: '2-digit', minute: '2-digit' });
  if (t >= today.getTime()) return 'vandaag ' + hm;
  if (t >= today.getTime() - 86400000) return 'gisteren ' + hm;
  return d.toLocaleDateString('nl-NL', { day: 'numeric', month: 'short' }) + ' ' + hm;
}
/* Plek uit de geschiedenis: "thuis", of een vakje van ~1 km */
function apPlace(p){
  if (!p) return '';
  if (p === 'thuis') return 'thuis';
  const m = /^(-?\d+\.\d+),(-?\d+\.\d+)$/.exec(p); if (!m) return 'elders';
  return 'elders (rond ' + m[1].replace('.', ',') + ' N, ' + m[2].replace('.', ',') + ' O, ±1 km)';
}

/* ---------- in en uit ---------- */
function enterAp(name){
  apLoad();
  if (name === 'apparaten') { if (apFinding) apdFindStop(); apTab(apTabNow, true); }
  if (name === 'apdev') apdRender();
  if (name === 'apset') { apStopPoll(); apsRender(); }
}
/* Weg van Apparaten: alles stoppen (zoeken, toon, peilen) */
function apLeave(){
  if (typeof Android.apStop !== 'function') return;
  apStopPoll(); apdFindStop(); Android.apStop();
}
function apStopPoll(){ clearTimeout(apPollT); apPollT = null; }
window.onApChanged = function(){ apLoad(); if (current === 'apparaten') apTab(apTabNow, true); if (current === 'apset') apsRender(); if (current === 'apdev') apdRender(); };
(function(){
  const prevOpen = window.openTool;
  window.openTool = function(n){ if (typeof n === 'string' && (n === 'apparaten' || n.startsWith('apparaten:'))) { apOpenFrom(n); return; } return prevOpen(n); };
  const prevResume = window.onResumeApp;
  window.onResumeApp = function(){ if (prevResume) prevResume(); if (AP_SCREENS.includes(current)) enterAp(current); };
  const prevPause = window.onPauseApp;
  window.onPauseApp = function(){ if (prevPause) prevPause(); if (AP_SCREENS.includes(current)) apLeave(); };
})();
/* Vanuit een melding of snelkoppeling: "apparaten", "apparaten:tracker:ID" of "apparaten:zoek:ID" */
function apOpenFrom(n){
  const m = /^apparaten:(tracker|zoek):([0-9A-F:]{17})$/.exec(n || '');
  if (current !== 'apparaten') show('apparaten');
  if (!m) return;
  apTab('tr');
  apDev = { src: 'tr', key: 'a:' + m[2], id: m[2] };
  show('apdev');
  if (m[1] === 'zoek') apdFindStart();
}

/* ---------- tabs ---------- */
function apTab(t, again){
  if (!['bt', 'wifi', 'lan', 'tr', 'known'].includes(t)) t = 'bt';
  if (apTabNow !== t || again) apStopPoll();
  apTabNow = t; store.set('apTab', t);
  document.querySelectorAll('#ap-tabs [role=tab]').forEach(b => { const on = b.dataset.p === t; b.setAttribute('aria-selected', on ? 'true' : 'false'); b.tabIndex = on ? 0 : -1; if (on && b.scrollIntoView) try { b.scrollIntoView({ block: 'nearest', inline: 'nearest' }); } catch (e) { } });
  ['bt', 'wifi', 'lan', 'tr', 'known'].forEach(p => { $('#ap-p-' + p).hidden = p !== t; });
  apPermCard(t);
  if (t === 'bt') { apBtRender(); if (apSt.perms.bt && apSt.perms.btOn) apBtPoll(); }
  if (t === 'wifi') apWifiLoad();
  if (t === 'lan') apLanPoll();
  if (t === 'tr') apTrRender();
  if (t === 'known') apKnownRender();
}
/* Pijltjestoetsen in de tabrij (toegankelijkheid) */
document.addEventListener('keydown', e => {
  if (!e.target.closest || !e.target.closest('#ap-tabs') || !['ArrowLeft', 'ArrowRight'].includes(e.key)) return;
  const tabs = [...document.querySelectorAll('#ap-tabs [role=tab]')], i = tabs.indexOf(e.target.closest('[role=tab]'));
  const n = tabs[(i + (e.key === 'ArrowRight' ? 1 : tabs.length - 1)) % tabs.length]; apTab(n.dataset.p); n.focus();
});

/* Uitleg en knop als er iets ontbreekt (in context, met de reden) */
function apPermCard(t){
  const c = $('#ap-perm'), P = apSt.perms; let h = '';
  const nearby = '<p>Om ' + (t === 'wifi' ? 'wifi-netwerken' : 'Bluetooth-apparaten') + ' in de buurt te zien, heeft de app de toestemming <b>Apparaten in de buurt</b> en <b>Locatie</b> nodig' +
    (P.loc && !P.fine ? '. Kies bij locatie <b>Precies</b>: met alleen "geschatte locatie" geeft Android geen apparaten door' : ' (kies <b>Precies</b>)') +
    '. Android vraagt locatie omdat je hieruit je plek kunt afleiden; de app gebruikt die alleen grof, voor trackers en "vaak in de buurt". Er gaat niets naar internet.</p><button class="big" onclick="Android.apPerm(\'nearby\')">Toestaan</button>';
  if ((t === 'bt' || t === 'tr') && P.btHw === false) h = '<p>Deze telefoon heeft geen Bluetooth.</p>';
  else if ((t === 'bt' || t === 'tr') && !P.bt) h = nearby;
  else if ((t === 'bt' || t === 'tr') && !P.btOn) h = '<p>Bluetooth staat uit. Zet het aan om apparaten in de buurt te zien.</p><button class="big" onclick="Android.apOpenSettings(\'bt\')">Bluetooth aanzetten</button>';
  else if (t === 'wifi' && !P.wifi) h = nearby;
  else if ((t === 'bt' || t === 'wifi' || t === 'tr') && P.locOn === false) h = '<p>Locatie staat uit op je telefoon. Dan geeft Android geen ' + (t === 'wifi' ? 'wifi-netwerken' : 'Bluetooth-apparaten') + ' door.</p><button class="big" onclick="Android.apOpenSettings(\'loc\')">Locatie aanzetten</button>';
  c.innerHTML = h; c.style.display = h ? '' : 'none';
}

/* ---------- Bluetooth ---------- */
function apBtGo(){
  const e = Android.apBtStart(true);
  if (e === 'perm') { Android.apPerm('nearby'); return; }
  if (e === 'uit') { toast('Bluetooth staat uit'); apLoad(); apPermCard('bt'); return; }
  if (e === 'geen') { toast('Deze telefoon heeft geen Bluetooth'); return; }
  if (e) { toast(e); return; }
  apBtPoll();
}
function apBtPoll(){
  apStopPoll();
  apBtData = rdJson(Android.apBt(), {}) || {};
  apBtRender();
  if (current === 'apparaten' && (apTabNow === 'bt' || apTabNow === 'tr')) apPollT = setTimeout(apBtPoll, apBtData.scanning ? 1000 : 4000);
  if (apTabNow === 'tr') apTrRender(true);
}
function apSortDevs(a, b){
  const w = d => (d.tracker && d.tracker.warn ? 0 : 1);
  return w(a) - w(b) || (b.known ? 1 : 0) - (a.known ? 1 : 0) || (b.avg || -127) - (a.avg || -127);
}
function apDevTitle(d){
  if (d.nick) return d.nick;
  if (d.tracker) return d.tracker.label;
  if (d.name) return d.name;
  const t = apType(d.type)[0];
  const mt = d.maker && d.type === 'apparaat' ? d.maker + '-apparaat' : d.maker && !t.includes(d.maker) ? d.maker + ' (' + t.toLowerCase() + ')' : t;
  return d.n > 1 ? d.n + '× ' + mt : (d.maker && d.type === 'apparaat' ? d.maker + '-apparaat' : t);
}
function apBtRow(d){
  const sub = [];
  if (d.nick && d.name) sub.push(d.name);
  if (d.type && d.type !== 'apparaat' && !(d.tracker && d.tracker.warn)) sub.push(apType(d.type)[0]);
  if (d.maker && !apDevTitle(d).includes(d.maker)) sub.push(d.maker);
  if (d.near !== false) sub.push(apDist(d.dist)); else sub.push('niet in de buurt');
  let b = '';
  if (d.known) b += apBadge('Mijn apparaat', 'ok');
  if (d.bonded) b += apBadge('gekoppeld');
  if (d.tracker && d.tracker.warn) b += apBadge('tracker', 'bad');
  if (d.rotating && !d.name) b += apBadge('wisselend adres');
  return '<div role="listitem"><button class="ap-row" data-k="' + esc(d.key) + '" onclick="apOpenBt(this.dataset.k)">' + apIcon(d.type, d.tracker && d.tracker.warn) +
    '<span class="tx"><b>' + esc(apDevTitle(d)) + '</b><small>' + esc(sub.filter(Boolean).join(' · ')) + '</small>' + (b ? '<span>' + b + '</span>' : '') + '</span>' + (d.near !== false ? apBars(d.avg) : '') + '</button></div>';
}
function apBtRender(){
  const d = apBtData || {}, list = (d.devices || []).slice().sort(apSortDevs), st = $('#ap-bt-st');
  $('#ap-bt-go').innerHTML = uiIcon('search') + (d.scanning ? 'Zoeken…' : list.length ? 'Opnieuw zoeken' : 'Zoeken');
  $('#ap-bt-go').disabled = !!d.scanning;
  st.textContent = d.err ? d.err : d.scanning ? 'Zoeken naar Bluetooth-apparaten… (' + list.length + ' gevonden)' : apBtData ? list.length + (list.length === 1 ? ' apparaat' : ' apparaten') + ' in de buurt' : 'Tik op Zoeken om te kijken welke Bluetooth-apparaten er in de buurt zijn.';
  $('#ap-bt-list').innerHTML = list.map(apBtRow).join('');
  const p = d.paired || [];
  $('#ap-bt-paired').innerHTML = p.length ? '<h2 class="ap-h">Gekoppeld, nu niet in de buurt</h2><div role="list" aria-label="Gekoppelde apparaten">' + p.map(apBtRow).join('') + '</div>' : '';
}
function apFindDev(key){
  const all = ((apBtData || {}).devices || []).concat((apBtData || {}).paired || []);
  return all.find(x => x.key === key);
}
function apOpenBt(key){ const d = apFindDev(key); apDev = { src: d && d.near === false ? 'paired' : 'bt', key, d: d || null }; show('apdev'); }

/* ---------- wifi-netwerken ---------- */
function apWifiGo(){
  const r = rdJson(Android.apWifiScan(), {}) || {};
  if (r.error === 'perm') { Android.apPerm('nearby'); return; }
  if (r.error === 'uit') { $('#ap-wifi-st').innerHTML = 'Wifi staat uit. <button class="linkish" onclick="Android.apOpenSettings(\'wifi\')">Wifi aanzetten</button>'; return; }
  if (r.error) { $('#ap-wifi-st').textContent = r.error; return; }
  if (r.started) { $('#ap-wifi-st').textContent = 'Zoeken naar wifi-netwerken…'; let n = 0; const tick = () => { apWifiLoad(true); if (++n < 6 && current === 'apparaten' && apTabNow === 'wifi') apPollT = setTimeout(tick, 1000); else apWifiLoad(); }; apStopPoll(); apPollT = setTimeout(tick, 1000); }
  else $('#ap-wifi-st').textContent = 'Android laat nog ' + (r.wait || 30) + ' seconden niet opnieuw zoeken (4 keer per 2 minuten). Dit zijn de laatst gevonden netwerken.';
}
function apWifiLoad(busy){
  apWifiData = rdJson(Android.apWifi(), {}) || {};
  const d = apWifiData;
  if (d.error === 'perm') { $('#ap-wifi-list').innerHTML = ''; $('#ap-crowd-card').style.display = 'none'; $('#ap-wifi-st').textContent = ''; return; }
  const nets = d.networks || [];
  if (!busy) $('#ap-wifi-st').textContent = nets.length ? nets.length + ' toegangspunten' + (d.newest >= 0 ? ', laatst gezocht ' + (d.newest < 60 ? 'net' : Math.round(d.newest / 60) + ' min geleden') : '') : 'Nog geen netwerken. Tik op Opnieuw zoeken.';
  // Per netwerknaam samen (een router met 2,4 en 5 GHz, of meerdere toegangspunten)
  const by = {};
  nets.forEach(n => { const k = n.ssid || '\u0000' + n.bssid; (by[k] = by[k] || []).push(n); });
  const groups = Object.values(by).map(g => g.sort((a, b) => b.rssi - a.rssi)).sort((a, b) => (b.some(x => x.connected) - a.some(x => x.connected)) || b[0].rssi - a[0].rssi);
  $('#ap-wifi-list').innerHTML = groups.map(g => {
    const n = g[0], name = n.ssid || 'Verborgen netwerk', bands = [...new Set(g.map(x => x.band))].join(' en ');
    const sec = { open: 'Open (geen wachtwoord)', wep: 'WEP', wpa: 'WPA', wpa2: 'WPA2', wpa3: 'WPA3', owe: 'Open, wel versleuteld (OWE)', zakelijk: 'Zakelijk (WPA2/3-Enterprise)' }[n.sec] || n.sec;
    const sub = ['kanaal ' + g.map(x => x.ch).filter((v, i, a) => a.indexOf(v) === i).join(', '), bands, n.width + ' MHz', sec];
    if (n.maker) sub.push(n.maker);
    let b = '';
    if (g.some(x => x.connected)) b += apBadge('Verbonden', 'ok');
    if (g.some(x => x.unsafe)) b += apBadge(n.sec === 'wep' ? 'onveilig (WEP)' : 'onveilig: open', 'bad');
    if (g.length > 1) b += apBadge(g.length + ' toegangspunten');
    return '<div class="ap-row static" role="listitem">' + apIcon('router') + '<span class="tx"><b>' + esc(name) + '</b><small>' + esc(sub.join(' · ')) + ' · ' + n.rssi + ' dBm</small>' + (b ? '<span>' + b + '</span>' : '') + '</span>' + apBars(n.rssi) + '</div>';
  }).join('');
  apCrowdRender();
}
function apCrowdRender(){
  const d = apWifiData || {}, cr = d.crowd || {}, bands = Object.keys(cr);
  $('#ap-crowd-card').style.display = bands.length ? '' : 'none';
  if (!bands.length) return;
  const mine = (d.networks || []).find(n => n.connected);
  $('#ap-crowd').innerHTML = bands.map(band => {
    const chs = Object.keys(cr[band]).map(Number).sort((a, b) => a - b), max = Math.max(1, ...chs.map(c => cr[band][c]));
    const bars = chs.map(c => { const v = cr[band][c], cls = band === '2,4 GHz' && c === d.best24 ? 'best' : v >= max * .75 && v > .5 ? 'hot' : ''; return '<div class="' + cls + '" title="Kanaal ' + c + '"><i style="height:' + Math.max(3, Math.round(v / max * 100)) + '%"></i></div>'; }).join('');
    return '<div class="ap-crowd"><div class="band">' + esc(band) + '</div><div class="ap-bars" role="img" aria-label="Drukte per kanaal op ' + esc(band) + ': ' + chs.map(c => 'kanaal ' + c + ' ' + String(cr[band][c]).replace('.', ',')).join(', ') + '">' + bars + '</div><div class="ap-lbls" aria-hidden="true">' + chs.map(c => '<span>' + c + '</span>').join('') + '</div></div>';
  }).join('');
  let tip = '';
  if (d.best24) tip = 'Op 2,4 GHz is kanaal ' + d.best24 + ' nu het rustigst (van 1, 6 en 11, die elkaar niet overlappen).';
  if (mine) tip += ' Je eigen netwerk zit op kanaal ' + mine.ch + ' (' + mine.band + ').' + (mine.band === '2,4 GHz' && d.best24 && mine.ch !== d.best24 ? ' Je kunt dat in je router veranderen als het traag is.' : '');
  $('#ap-crowd-tip').textContent = tip.trim();
}

/* ---------- op mijn wifi ---------- */
function apLanGo(){
  if (apLanData && apLanData.busy) { Android.apLanStop(); toast('Stoppen…'); return; }
  const d = rdJson(Android.apLan(), {}) || {};
  if (d.wifi && !d.trusted) {
    askConfirm('Dit netwerk onderzoeken?', (d.cur ? '"' + d.cur + '" ' : 'Dit netwerk ') + 'is niet je wifi thuis en is nog nooit onderzocht. Kijk alleen rond op een netwerk dat van jou is, of waar je toestemming voor hebt (dus niet zomaar in een hotel of op je werk).', 'Onderzoeken', apLanStartNow);
    return;
  }
  apLanStartNow();
}
function apLanStartNow(){
  const e = Android.apLanStart();
  const msg = { wifi: 'Je bent niet met wifi verbonden. Dit zoekt alleen op je eigen wifi, nooit via mobiele data.', prive: 'Dit netwerk heeft geen gewoon thuisadres; daar zoekt de app niet.', bezig: 'Er wordt al gezocht.' }[e];
  if (e) { $('#ap-lan-st').textContent = msg || e; return; }
  apLanPoll();
}
function apLanPoll(){
  apStopPoll();
  apLanData = rdJson(Android.apLan(), {}) || {};
  apLanRender();
  if (apLanData.busy && current === 'apparaten' && apTabNow === 'lan') apPollT = setTimeout(apLanPoll, 700);
}
function apIpNum(ip){ return String(ip || '').split('.').reduce((a, x) => a * 256 + (+x || 0), 0); }
function apLanTitle(d){ return d.nick || d.name || d.model || apType(d.kind)[0]; }
function apLanRender(){
  const d = apLanData || {}, list = (d.devices || []).slice().sort((a, b) => (b.self - a.self) || (b.gateway - a.gateway) || (b.nieuw - a.nieuw) || apIpNum(a.ip) - apIpNum(b.ip));
  const go = $('#ap-lan-go');
  go.innerHTML = d.busy ? uiIcon('stop') + 'Stoppen' : uiIcon('search') + (list.length ? 'Opnieuw zoeken' : 'Zoeken op mijn wifi');
  const prog = $('#ap-lan-prog'), pct = d.total ? Math.min(100, Math.round(d.done * 100 / d.total)) : 0;
  prog.style.display = d.busy ? '' : 'none'; $('#ap-lan-bar').style.width = pct + '%'; prog.setAttribute('aria-valuenow', pct);
  const nNew = list.filter(x => x.nieuw).length;
  $('#ap-lan-st').textContent = d.busy ? (d.phase || 'Zoeken') + '… ' + Math.max(0, list.length - 1) + ' gevonden' : d.error ? d.error
    : d.at ? Math.max(0, list.length - 1) + ' apparaten op ' + (d.net || 'je wifi') + (nNew ? ', waarvan ' + nNew + ' nieuw' : '') : d.wifi === false ? 'Je bent niet met wifi verbonden.' : 'Tik op Zoeken om te zien wat er op je wifi zit' + (d.net ? ' (' + d.net + ')' : '') + '.';
  $('#ap-lan-list').innerHTML = list.map(x => {
    const sub = [x.self ? 'deze telefoon' : apType(x.kind)[0]];
    if (x.maker) sub.push(x.maker); if (x.model && x.model !== apLanTitle(x)) sub.push(x.model); sub.push(x.ip);
    let b = '';
    if (x.nieuw) b += apBadge('nieuw', 'new');
    if (x.known) b += apBadge('Mijn apparaat', 'ok');
    if (x.gateway) b += apBadge('router');
    const svc = (x.services || []).concat((x.ports || []).map(p => p.label)).filter((v, i, a) => a.indexOf(v) === i).slice(0, 4);
    return '<div role="listitem"><button class="ap-row" data-k="' + esc(x.key) + '" onclick="apOpenLan(this.dataset.k)">' + apIcon(x.self ? 'telefoon' : x.kind) + '<span class="tx"><b>' + esc(apLanTitle(x) || x.ip) + '</b><small>' + esc(sub.join(' · ')) + '</small>' +
      (svc.length ? '<small>' + esc(svc.join(', ')) + '</small>' : '') + (b ? '<span>' + b + '</span>' : '') + '</span></button></div>';
  }).join('');
  $('#ap-lan-foot').textContent = 'Alleen je eigen netwerk' + (d.own ? ' (' + d.own + ')' : '') + ', hooguit 254 adressen, alleen via wifi en niets naar internet. De app kijkt alleen wat apparaten zelf aankondigen en welke gangbare diensten openstaan; hij logt nergens in.' +
    (d.macHidden ? ' Het MAC-adres (en daarmee de maker) houdt Android vanaf versie 10 verborgen; de maker komt dan uit wat het apparaat zelf zegt.' : '') + ' Nieuw = de eerste keer gezien op dit netwerk (de eerste keer zoeken is de basis).';
}
function apOpenLan(key){ const d = ((apLanData || {}).devices || []).find(x => x.key === key); apDev = { src: 'lan', key, d: d || null }; show('apdev'); }

/* ---------- trackers ---------- */
function apTrLoad(){ apTrData = rdJson(Android.apTrackers(), {}) || {}; return apTrData; }
/* Bewaarde trackers plus wat er nu in de buurt is (uit de Bluetooth-scan), samengevoegd op adres */
function apTrList(){
  const out = {}, now = ((apBtData || {}).devices || []).filter(d => d.tracker);
  ((apTrData || {}).list || []).forEach(t => { out[t.key] = Object.assign({}, t); });
  now.forEach(d => { const k = d.key.startsWith('a:') ? d.key : 'a:' + d.addr; out[k] = Object.assign(out[k] || { key: k, id: d.addr, label: d.tracker.label, kind: d.tracker.kind, places: 0, minutes: 0 }, { near: true, dist: d.dist, avg: d.avg, warn: d.tracker.warn, nick: d.nick, known: d.known }); });
  return Object.values(out).sort((a, b) => (b.follows ? 1 : 0) - (a.follows ? 1 : 0) || (b.near ? 1 : 0) - (a.near ? 1 : 0) || (b.last || 0) - (a.last || 0));
}
function apTrRender(fromPoll){
  if (!fromPoll) apTrLoad();
  const s = apSt.set, P = apSt.perms, box = $('#ap-tr-state');
  let h;
  if (!s.bg) h = '<h2>Op de achtergrond staat uit</h2><p class="note">Nu let de app alleen op trackers terwijl je hier kijkt. Voor een waarschuwing als een onbekende tracker met je meereist, moet zoeken op de achtergrond aan staan.</p><button class="big" onclick="show(\'apset\')">Instellen</button>';
  else if (!s.trackers) h = '<h2>Trackerwaarschuwing staat uit</h2><button class="big" onclick="apsSetVal(\'trackers\', true); apTrRender()">Aanzetten</button>';
  else if (!P.bg) h = '<h2>Nog één toestemming</h2><p class="note">Sta locatie <b>altijd</b> toe, anders laat Android de app op de achtergrond niet zoeken.</p><button class="big" onclick="Android.apPerm(\'bgloc\')">Toestaan</button>';
  else if (!P.notif || apSt.muted) h = '<h2>Meldingen staan uit</h2><p class="note">De app let op trackers, maar kan je niet waarschuwen.</p>' + (apSt.muted ? '' : '<button class="big" onclick="Android.apPerm(\'notif\')">Meldingen toestaan</button>');
  else h = '<h2>' + uiIcon('shield') + 'Aan</h2><p class="note">De app let op de achtergrond op onbekende trackers die met je meereizen' + ((apTrData || {}).passive ? ', ook tussen de rondes door (Android houdt zelf zuinig de wacht)' : '') + '.</p>';
  box.innerHTML = h;
  const l = apTrList();
  $('#ap-tr-list').innerHTML = l.length ? l.map(t => {
    const sub = [];
    if (t.status === 'mine' || t.known) sub.push('van jou');
    else if (t.status === 'ignored') sub.push('genegeerd');
    else if (t.follows) sub.push('reist mee: ' + t.places + ' plekken, ' + t.minutes + ' minuten');
    else if (t.places) sub.push('gezien op ' + t.places + (t.places === 1 ? ' plek' : ' plekken'));
    if (t.near) sub.push('nu in de buurt, ' + apDist(t.dist)); else if (t.last) sub.push('laatst ' + apAgo(t.last));
    if (t.warn === false) sub.push('de eigenaar is in de buurt');
    const b = t.follows && !t.status ? apBadge('let op', 'bad') : '';
    return '<div role="listitem"><button class="ap-row" data-k="' + esc(t.key) + '" onclick="apOpenTr(this.dataset.k)">' + apIcon('tracker', t.follows && !t.status) + '<span class="tx"><b>' + esc(t.nick || t.label || 'Tracker') + '</b><small>' + esc(sub.join(' · ')) + '</small>' + (b ? '<span>' + b + '</span>' : '') + '</span>' + (t.near ? apBars(t.avg) : '') + '</button></div>';
  }).join('') : '<p class="ap-empty">Geen trackers gezien.</p>';
}
function apTrScan(){ const e = Android.apBtStart(false); if (e === 'perm') { Android.apPerm('nearby'); return; } if (e === 'uit') { toast('Bluetooth staat uit'); return; } if (e) { toast(e); return; } apBtPoll(); }
function apOpenTr(key){ const t = apTrList().find(x => x.key === key); apDev = { src: 'tr', key, id: key.slice(2), d: t || null }; show('apdev'); }

/* ---------- bekend en vaak in de buurt ---------- */
function apKnownRender(){
  apHistData = rdJson(Android.apHistory(), {}) || {};
  const h = apHistData, s = apSt.set;
  const row = (x, mine) => {
    const sub = [];
    if (mine && x.nick && x.name) sub.push(x.name);
    if (x.type && x.type !== 'apparaat') sub.push(apType(x.type)[0]);
    if (x.last) sub.push('laatst gezien ' + apAgo(x.last) + (x.place ? ', ' + apPlace(x.place) : ''));
    else if (mine) sub.push('nog niet gezien');
    if (!mine && x.days) sub.push('op ' + x.days + (x.days === 1 ? ' dag' : ' dagen'));
    return '<div role="listitem"><button class="ap-row" data-k="' + esc(x.key) + '" onclick="apOpenHist(this.dataset.k)">' + apIcon(x.type) + '<span class="tx"><b>' + esc(x.nick || x.name || apType(x.type)[0]) + '</b><small>' + esc(sub.join(' · ')) + '</small></span></button></div>';
  };
  $('#ap-mine').innerHTML = (h.mine || []).length ? h.mine.map(x => row(x, true)).join('') : '<p class="ap-empty">Nog geen eigen apparaten. Open een apparaat en kies "Dit is mijn apparaat".</p>';
  const empty = s.hist === false ? 'Bijhouden staat uit (instellingen).' : 'Nog niets: een apparaat telt als het op minstens 2 dagen gezien is.';
  $('#ap-oft-home').innerHTML = (h.home || []).length ? h.home.map(x => row(x)).join('') : '<p class="ap-empty">' + esc(s.away && s.hist !== false ? 'Thuis wordt niets bijgehouden (alleen buiten huis).' : empty) + '</p>';
  $('#ap-oft-away').innerHTML = (h.away || []).length ? h.away.map(x => row(x)).join('') : '<p class="ap-empty">' + esc(empty) + '</p>';
  $('#ap-hist-foot').textContent = (s.home ? '' : 'Kies bij de instellingen waar thuis is, dan wordt thuis en elders uit elkaar gehouden. ') + 'Bewaard: ' + (h.count || 0) + ' apparaten, ' + (h.keep || s.keep || 30) + ' dagen. Alleen apparaten met een vaste naam of een vast adres zijn terug te herkennen; telefoons die van adres wisselen meestal niet. Alles blijft op je telefoon.';
}
function apOpenHist(key){
  const h = apHistData || {}, d = (h.mine || []).concat(h.home || [], h.away || []).find(x => x.key === key);
  apDev = { src: 'hist', key, d: d || null }; show('apdev');
}

/* ---------- detail en zoeken ---------- */
function apdData(){
  if (!apDev) return null;
  const k = apDev.key;
  if (apDev.src === 'lan') return ((apLanData || {}).devices || []).find(x => x.key === k) || apDev.d;
  if (apDev.src === 'tr') return apTrList().find(x => x.key === k) || apDev.d || { key: k, label: 'Tracker' };
  return apFindDev(k) || apDev.d;
}
function apdFindable(){ return apDev && apDev.src !== 'lan' && /^(a|n):/.test(apDev.key); }
function apdRender(){
  if (!apDev) { show('apparaten'); return; }
  const d = apdData() || {}, lan = apDev.src === 'lan', tr = apDev.src === 'tr' || !!(d.tracker && d.tracker.warn);
  const title = lan ? (apLanTitle(d) || d.ip || 'Apparaat') : apDev.src === 'tr' ? (d.nick || d.label || 'Tracker') : apDev.src === 'hist' ? (d.nick || d.name || apType(d.type)[0]) : apDevTitle(d.key ? d : { type: 'apparaat' });
  $('#apd-h').textContent = title;
  // Tracker: wat je kunt doen
  const tc = $('#apd-tracker');
  if (tr) {
    const st = d.status || '', label = d.label || (d.tracker && d.tracker.label) || 'tracker';
    const apple = /airtag|find my/i.test(label), samsung = /smarttag/i.test(label), google = /google/i.test(label), tile = /tile|chipolo/i.test(label);
    tc.innerHTML = '<h2>' + uiIcon('alert') + (st === 'mine' || d.known ? 'Je eigen ' + esc(label) : st === 'ignored' ? esc(label) + ' (genegeerd)' : 'Onbekende ' + esc(label)) + '</h2>' +
      (d.follows ? '<p>Deze tracker is <b>' + d.minutes + ' minuten</b> met je meegegaan, op <b>' + d.places + ' plekken</b>.</p>' : d.places ? '<p>Gezien op ' + d.places + (d.places === 1 ? ' plek' : ' plekken') + '.</p>' : '') +
      '<ol class="ap-tips"><li>Zoek hem met de meter hieronder: het signaal wordt sterker als je dichterbij komt. Kijk in je tas, jaszakken, auto (onder de stoelen, kofferbak) en fiets.</li>' +
      (apple ? '<li>AirTag: houd hem tegen de achterkant van een telefoon met NFC. Je ziet dan het serienummer en een deel van het telefoonnummer van de eigenaar. Uitzetten: druk het klepje in, draai het linksom en haal de batterij eruit.</li>' : '') +
      (samsung ? '<li>SmartTag: Samsung-telefoons vinden hem met SmartThings → Onbekende tags zoeken, en kunnen hem laten piepen. Uitzetten: open het klepje en haal de batterij eruit.</li>' : '') +
      (google || tile ? '<li>Op Android kun je hem laten piepen via Instellingen → Veiligheid en noodgevallen → Meldingen over onbekende trackers → Nu scannen.</li>' : '<li>Android heeft ook een eigen controle: Instellingen → Veiligheid en noodgevallen → Meldingen over onbekende trackers (daar kan hij ook geluid laten maken).</li>') +
      '<li>Voel je je niet veilig? Ga naar een drukke plek. Bel bij direct gevaar 112, anders de politie (0900-8844). Maak eerst een foto van de tracker voordat je de batterij eruit haalt: zo kan de politie de eigenaar achterhalen.</li></ol>' +
      '<div class="ap-acts">' + (st ? '<button class="mini" onclick="apdTr(\'\')">Weer waarschuwen</button>' : '<button class="mini" onclick="apdTr(\'mine\')">Dit is van mij</button><button class="mini" onclick="apdTr(\'ignored\')">Negeren</button>') + '</div>';
    tc.style.display = '';
  } else tc.style.display = 'none';
  // Zoeken (niet voor apparaten op het netwerk: die zie je niet via Bluetooth)
  $('#apd-find').style.display = apdFindable() ? '' : 'none';
  apdToggles();
  $('#apd-go').textContent = apFinding ? 'Stoppen' : 'Zoeken';
  // Gegevens
  const kv = (a, b) => b == null || b === '' ? '' : '<div class="kv"><span>' + esc(a) + '</span><span>' + esc(b) + '</span></div>';
  let info = '<h2>Gegevens</h2>';
  if (lan) {
    info += kv('Soort', d.self ? 'Deze telefoon' : apType(d.kind)[0]) + kv('Naam', d.name) + kv('Model', d.model) + kv('Maker', d.maker || 'niet bekend') + kv('IP-adres', d.ip) + kv('MAC-adres', d.mac) +
      kv('Diensten', (d.services || []).join(', ')) + kv('Open poorten', (d.ports || []).map(p => p.p + ' (' + p.label + ')').join(', ')) + kv('Eerst gezien', d.first ? apAgo(d.first) : '');
    if (!d.stable) info += '<p class="note">Dit apparaat zegt niet hoe het heet: het wordt herkend aan zijn IP-adres, en dat kan veranderen.</p>';
  } else if (apDev.src === 'tr') {
    info += kv('Soort', d.label) + kv('Adres', apDev.id) + kv('Eerst gezien', d.first ? apAgo(d.first) : '') + kv('Laatst gezien', d.near ? 'nu, ' + apDist(d.dist) : d.last ? apAgo(d.last) : '');
    info += '<p class="note">Trackers uit de buurt van hun eigenaar houden hun adres tot een dag; daarna krijgen ze een nieuw adres en staan ze hier opnieuw.</p>';
  } else {
    info += kv('Soort', apType(d.type)[0]) + kv('Naam', d.name) + kv('Maker', d.maker) + kv('Adres', d.n > 1 ? d.n + ' adressen' : d.addr) + kv('Signaal', d.rssi != null && d.near !== false ? d.rssi + ' dBm' : '') +
      kv('Afstand', d.near !== false && d.dist != null ? apDist(d.dist) : '') + kv('Bluetooth', [d.ble ? 'Bluetooth LE' : '', d.classic ? 'klassiek' : ''].filter(Boolean).join(' en ')) + kv('Gekoppeld', d.bonded ? 'ja' : '') +
      (apDev.src === 'hist' ? kv('Laatst gezien', d.last ? apAgo(d.last) + (d.place ? ', ' + apPlace(d.place) : '') : 'nog niet') + kv('Gezien op', d.days ? d.days + (d.days === 1 ? ' dag' : ' dagen') : '') : '');
    if (d.rotating) info += '<p class="note">Dit apparaat wisselt van adres (dat doen telefoons, horloges en oortjes om niet gevolgd te kunnen worden).' + (d.name ? ' Het wordt herkend aan zijn naam.' : ' Zonder naam is het niet terug te herkennen.') + '</p>';
  }
  $('#apd-info').innerHTML = info;
  // Bekend / bijnaam
  const stable = /^(a|n|l):/.test(apDev.key) && (lan ? d.stable !== false : true), known = d.known || (apDev.src === 'hist' && d.nick);
  $('#apd-acts').style.display = tr && !known ? 'none' : '';
  $('#apd-acts').innerHTML = '<h2>Mijn apparaat</h2>' + (!stable ? '<p class="note">Dit apparaat is niet terug te herkennen (het wisselt van adres en heeft geen vaste naam), dus een bijnaam kan niet.</p>'
    : known ? '<p class="note">Bijnaam: <b>' + esc(d.nick) + '</b>. ' + (lan ? 'Het valt niet meer op als nieuw.' : 'Je ziet bij Bekend waar het het laatst was.') + '</p><div class="ap-acts"><button class="mini" onclick="apdKnown(true)">Bijnaam wijzigen</button><button class="mini" onclick="apdKnown(false)">Niet meer mijn apparaat</button></div>'
    : '<p class="note">Geef het een bijnaam: dan herken je het terug' + (lan ? ' en valt het niet meer op als nieuw.' : ' en zie je bij Bekend waar het het laatst gezien is.') + '</p><div class="ap-acts"><button class="mini" onclick="apdKnown(true)">Dit is mijn apparaat</button></div>');
}
function apdKnown(on){
  const d = apdData() || {};
  if (!on) { Android.apKnown(apDev.key, '', '', ''); toast('Niet meer als jouw apparaat bewaard'); apdRefresh(); return; }
  const def = d.nick || d.name || d.label || (apDev.src === 'lan' ? apLanTitle(d) : apType(d.type)[0]);
  askInput('Bijnaam', 'Hoe noem je dit apparaat?', def, 'Bewaren', v => {
    v = String(v || '').trim(); if (!v) return;
    const e = Android.apKnown(apDev.key, v.slice(0, 40), (apDev.src === 'lan' ? d.kind : d.type) || '', d.name || d.label || '');
    if (e) { toast(e); return; }
    toast('Bewaard als ' + v); apdRefresh();
  });
}
function apdTr(st){
  const d = apdData() || {}, kind = d.kind || (d.tracker && d.tracker.kind) || '';
  // Tile houdt zijn adres; de andere trackers krijgen ongeveer dagelijks een nieuw adres (en zijn dan niet meer te herkennen)
  const rot = kind !== 'tile';
  Android.apTrackerSet(apDev.id || apDev.key.slice(2), st);
  toast(st === 'mine' ? 'Bewaard als jouw tracker.' + (rot ? ' Krijgt hij een nieuw adres (ongeveer elke dag), dan kan er opnieuw een waarschuwing komen.' : '')
    : st === 'ignored' ? 'Deze tracker wordt genegeerd' + (rot ? ' (tot hij een nieuw adres krijgt, ongeveer elke dag)' : '') : 'Je krijgt weer een waarschuwing');
  apdRefresh();
}
/* Na een wijziging: de bron opnieuw ophalen en het detail opnieuw tekenen */
function apdRefresh(){
  if (apDev.src === 'lan') apLanData = rdJson(Android.apLan(), {}) || apLanData;
  else if (apDev.src === 'hist') apHistData = rdJson(Android.apHistory(), {}) || apHistData;
  else if (apDev.src === 'tr') apTrLoad();
  else apBtData = rdJson(Android.apBt(), {}) || apBtData;
  if (apDev.src === 'hist') { const h = apHistData, d = (h.mine || []).concat(h.home || [], h.away || []).find(x => x.key === apDev.key); apDev.d = d || Object.assign({}, apDev.d || {}, { nick: '', known: false }); }
  apdRender();
}
function apdToggles(){ [['apd-tone', store.get('apTone', false)], ['apd-vib', store.get('apVib', true)]].forEach(([id, on]) => { const b = $('#' + id); b.classList.toggle('on', on); b.setAttribute('aria-checked', on ? 'true' : 'false'); }); }
function apdToggle(k){ const d = k === 'apVib'; store.set(k, !store.get(k, d)); apdToggles(); if (k === 'apTone' && !store.get(k)) apBeepStop(); }
function apdFindToggle(){ if (apFinding) apdFindStop(); else apdFindStart(); }
function apdFindStart(){
  if (!apdFindable()) return;
  const e = Android.apFind(apDev.key);
  if (e === 'perm') { Android.apPerm('nearby'); return; }
  if (e === 'uit') { toast('Bluetooth staat uit'); Android.apOpenSettings('bt'); return; }
  if (e) { toast(e === 'geen' ? 'Deze telefoon heeft geen Bluetooth' : e); return; }
  apFinding = true; apSpark = []; apLastTrend = 0;
  $('#apd-go').textContent = 'Stoppen';
  apdFindPoll();
}
function apdFindStop(){
  clearTimeout(apFindT); apFindT = null; apBeepStop();
  if (!apFinding) return;
  apFinding = false; Android.apFindStop();
  if ($('#apd-go')) $('#apd-go').textContent = 'Zoeken';
}
function apdFindPoll(){
  if (!apFinding || current !== 'apdev') return;
  const f = rdJson(Android.apFindState(apDev.key), {}) || {};
  const ring = $('#apd-ring'), m = $('#apd-meter'), tr = $('#apd-trend');
  if (!f.found || f.ago > 8000) {
    ring.style.setProperty('--p', 0); $('#apd-val').textContent = '–';
    $('#apd-unit').textContent = f.found ? 'Even niet gezien (' + Math.round(f.ago / 1000) + ' s)' : 'Nog niet gevonden';
    tr.textContent = f.found ? 'Kouder: hij is uit beeld. Loop terug.' : 'Zoeken… loop langzaam rond.'; tr.className = 'ap-trend' + (f.found ? ' down' : '');
    m.setAttribute('aria-valuenow', 0); apFindLevel = 0;
  } else {
    ring.style.setProperty('--p', f.meter); m.setAttribute('aria-valuenow', f.meter); apFindLevel = f.meter;
    $('#apd-val').textContent = f.dist < 1 ? '< 1 m' : '± ' + (f.dist < 10 ? String(Math.round(f.dist * 2) / 2).replace('.', ',') : Math.round(f.dist)) + ' m';
    $('#apd-unit').textContent = 'ongeveer · ' + Math.round(f.avg) + ' dBm';
    const t = f.trend;
    tr.textContent = f.meter >= 85 ? 'Heel dichtbij!' : t > 0 ? 'Warmer ↑' : t < 0 ? 'Kouder ↓' : 'Gelijk';
    tr.className = 'ap-trend' + (t > 0 || f.meter >= 85 ? ' up' : t < 0 ? ' down' : '');
    if (t > 0 && apLastTrend <= 0 && store.get('apVib', true)) Android.vibrate(30);
    apLastTrend = t;
    apSpark.push(f.meter); if (apSpark.length > 75) apSpark.shift();
  }
  if (apSpark.length > 1) $('#apd-spark-p').setAttribute('d', apSpark.map((v, i) => (i ? 'L' : 'M') + Math.round(i * 300 / 74) + ' ' + Math.round(58 - v * .56)).join(' '));
  apBeep();
  apFindT = setTimeout(apdFindPoll, 400);
}
/* Toon: een korte piep, sneller naarmate het signaal sterker is (zoals een metaaldetector) */
function apBeep(){
  if (!store.get('apTone', false) || !apFinding || apBeepT || !apFindLevel) return;
  try {
    apAudio = apAudio || new (window.AudioContext || window.webkitAudioContext)();
    const o = apAudio.createOscillator(), g = apAudio.createGain();
    o.frequency.value = 600 + apFindLevel * 6; g.gain.value = 0.08; o.connect(g); g.connect(apAudio.destination);
    o.start(); o.stop(apAudio.currentTime + 0.06);
  } catch (e) { }
  apBeepT = setTimeout(() => { apBeepT = null; apBeep(); }, Math.max(120, 1400 - apFindLevel * 13));
}
function apBeepStop(){ clearTimeout(apBeepT); apBeepT = null; }

/* ---------- instellingen ---------- */
function apsRender(){
  const s = apLoad().set, P = apSt.perms;
  [['aps-bg', s.bg], ['aps-tr', s.trackers !== false], ['aps-hist', s.hist !== false], ['aps-away', !!s.away]].forEach(([id, on]) => { const b = $('#' + id); b.classList.toggle('on', !!on); b.setAttribute('aria-checked', on ? 'true' : 'false'); });
  [['aps-every', s.every || 30], ['aps-keep', s.keep || 30]].forEach(([id, v]) => document.querySelectorAll('#' + id + ' [role=radio]').forEach(b => { const on = +b.dataset.v === +v; b.classList.toggle('on', on); b.setAttribute('aria-checked', on ? 'true' : 'false'); }));
  const sdk = P.sdk || 0;
  $('#aps-bg-how').innerHTML = esc('Elke ronde zoekt de app ongeveer 10 seconden (Android plant dat in; als de telefoon slaapt of de batterijbesparing aan staat, schuift het op). ') +
    esc(sdk >= 26 || !sdk ? 'Daarnaast houdt Android zelf zuinig de wacht voor trackers, ook tussen de rondes door. ' : '') +
    esc('Er draait geen dienst met een vaste melding. Met het scherm uit ziet de app alleen trackers en je gekoppelde apparaten. Wifi zoekt Android op de achtergrond nauwelijks (1 keer per half uur); de app gebruikt dan wat de telefoon al vond.') +
    (s.bg && !P.bg ? '<br><b class="note warn">Sta locatie "altijd" toe, anders laat Android de app op de achtergrond niet zoeken.</b> <button class="mini" onclick="Android.apPerm(\'bgloc\')">Toestaan</button>' : '');
  $('#aps-home-st').textContent = s.home ? 'Thuis is ingesteld' + (s.homeSsid ? ' (en je wifi "' + s.homeSsid + '")' : '') + '. Binnen ~300 m daarvan telt als thuis.' : s.homeSsid ? 'Thuis = verbonden met je wifi "' + s.homeSsid + '".' : 'Nog niet ingesteld: alles telt als elders.';
  $('#aps-home-x').style.display = s.home || s.homeSsid ? '' : 'none';
  const row = (name, why, ok, kind) => '<div class="ap-perm-row"><span>' + esc(name) + '<small>' + esc(why) + '</small></span>' + (ok ? apBadge('✓ toegestaan', 'ok') : '<button class="mini" onclick="Android.apPerm(\'' + kind + '\')">Toestaan</button>') + '</div>';
  $('#aps-perms').innerHTML = row('Apparaten in de buurt en locatie', 'Bluetooth en wifi in de buurt zien' + (sdk && sdk < 31 ? ' (Android vraagt hiervoor locatie)' : ''), P.bt && P.wifi, 'nearby') +
    (sdk >= 29 || !sdk ? row('Locatie altijd', 'Alleen als je op de achtergrond zoekt', P.bg, 'bgloc') : '') +
    row('Meldingen', 'Voor de waarschuwing als een tracker met je meereist', P.notif, 'notif');
}
/* Eén instelling zetten, met dezelfde bijwerkingen als op het scherm. "" of een melding (ook voor de assistent). */
function apsSetVal(k, v){
  const P = apLoad().perms;
  Android.apSet(JSON.stringify({ [k]: v }));
  let msg = '';
  if (k === 'bg' && v) {
    if (!P.bt) { Android.apPerm('nearby'); msg = 'Sta eerst "Apparaten in de buurt" en locatie toe; daarna zoekt de app op de achtergrond.'; }
    else if (!P.bg) { Android.apPerm('bgloc'); msg = 'Sta locatie "altijd" toe (Android vraagt dat apart), anders kan de app op de achtergrond niet zoeken.'; }
    else if (!P.notif) Android.apPerm('notif');
  }
  apLoad();
  setIf('apset', apsRender);
  setIf('apparaten', () => { if (apTabNow === 'tr') apTrRender(); if (apTabNow === 'known') apKnownRender(); });
  if (msg && current === 'apset') toast(msg);
  return msg;
}
function apsTg(k){ const s = apLoad().set, cur = k === 'trackers' || k === 'hist' ? s[k] !== false : !!s[k]; apsSetVal(k, !cur); }
function apsSet(k, v){
  const s = apLoad().set;
  if (k === 'keep' && v < (s.keep || 30)) { askConfirm('Korter bewaren?', 'Wat ouder is dan ' + (v === 7 ? 'een week' : v + ' dagen') + ', wordt meteen gewist.', 'Korter bewaren', () => apsSetVal(k, v)); return; }
  apsSetVal(k, v);
}
function apsHome(clear){
  if (clear) { Android.apHome(true); toast('Thuis gewist'); apsRender(); return; }
  const e = Android.apHome(false);
  if (e === 'perm') { Android.apPerm('nearby'); return; }
  if (e) { toast(e); return; }
  toast('Thuis bewaard'); apsRender();
}
function apsWipe(all){
  askConfirm(all ? 'Alles wissen?' : 'Geschiedenis wissen?', all ? 'De geschiedenis, trackers, apparaten op je wifi, je bijnamen en thuis worden gewist.' : 'De geschiedenis (vaak in de buurt), de trackers en de apparaten op je wifi worden gewist. Je bijnamen blijven.', 'Wissen', () => {
    Android.apWipe(!!all); apBtData = null; apLanData = null; apHistData = null; toast('Gewist'); apsRender();
  });
}
