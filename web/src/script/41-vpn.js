/* ---------- VPN (WireGuard) ---------- */
let vpSt = {}, vpTun = [], vpTimer = null, vpBusy = false;

function enterVpn(){ vpRender(); clearInterval(vpTimer); vpTimer = setInterval(() => { if (current === 'vpn' && !appPaused) vpRender(true); else if (current !== 'vpn') { clearInterval(vpTimer); vpTimer = null; } }, 1500); }

function vpAgo(tt){
  if (!tt) return t('nog niet');
  const s = Math.max(0, Math.round((Date.now() - tt) / 1000));
  return s < 60 ? t('{n} s geleden', { n: s }) : s < 3600 ? t('{n} min geleden', { n: Math.round(s / 60) }) : fmtTime(tt, { hour: '2-digit', minute: '2-digit' });
}
function vpSize(b){ return fmtSize(b, { bytes: true, mb: 1, gb: 2 }); }
const VP_REASON = { app: '', tegel: 'via de tegel', snelkoppeling: 'via de snelkoppeling', wifi: 'vanzelf: onbekende wifi', mobiel: 'vanzelf: mobiel internet', auto: 'door Automatiseren', altijd: 'Altijd-aan VPN van Android', melding: 'via de melding' };

function vpRender(light){
  vpSt = rdJson(Android.vpnState(), {});
  if (!light) vpTun = rdJson(Android.vpnTunnels(), []);
  const has = vpTun.length > 0, st = vpSt, on = !!st.on;
  $('#vp-intro').style.display = has ? 'none' : 'block';
  ['#vp-main', '#vp-listwrap', '#vp-autocard', '#vp-quick', '#vp-kill'].forEach(s => $(s).style.display = has ? 'block' : 'none');
  if (!has) return;
  const act = vpTun.find(tt => tt.id === (on ? st.id : st.active)) || vpTun[0];
  const connected = on && st.status === 'connected';
  $('#vp-dot').className = 'vpdot' + (connected ? ' on' : on ? ' busy' : '');
  $('#vp-title').textContent = on ? t(connected ? 'Verbonden' : 'Verbinden…') + ' · ' + (st.name || act.name) : t('VPN uit');
  const viaReserve = on && st.activeId && st.activeId !== st.id;
  let sub = on ? [viaReserve ? t('via reserve: {naam}', { naam: st.activeName }) : '', st.kind || '', t(VP_REASON[st.reason] || '')].filter(Boolean).join(' · ') : (act ? t('Tunnel: {naam}', { naam: act.name }) : '');
  if (on && !connected && st.error) sub = t(st.error) + ' · ' + t('probeert het opnieuw');
  $('#vp-sub').textContent = sub;
  const tb = $('#vp-toggle');
  tb.textContent = vpBusy ? 'Even geduld…' : on ? 'Uitzetten' : 'Aanzetten';
  tb.classList.toggle('ghost', on);
  tb.disabled = vpBusy;
  const stats = $('#vp-stats');
  if (on) {
    stats.style.display = 'grid';
    stats.innerHTML = '<div><small>Ontvangen</small><b>' + vpSize(st.rx) + '</b></div><div><small>Verstuurd</small><b>' + vpSize(st.tx) + '</b></div>' +
      '<div><small>Laatste contact</small><b>' + esc(vpAgo(st.handshake)) + '</b></div><div><small>Server</small><b>' + esc(st.endpoint || (act && act.endpoint) || '') + '</b></div>';
  } else stats.style.display = 'none';
  $('#vp-err').style.display = !on && st.error ? 'block' : 'none';
  $('#vp-err').textContent = st.error || '';
  if (light) return;
  // tunnels
  $('#vp-list').innerHTML = vpTun.map(tt => {
    const cur = tt.id === (on ? st.id : st.active);
    return '<div class="srow"><button class="sbtn" data-id="' + esc(tt.id) + '" onclick="vpTunMenu(this.dataset.id)"><span class="vpic' + (cur ? ' cur' : '') + '" aria-hidden="true">' + uiIcon(cur ? 'check' : 'shield') + '</span>' +
      '<span class="stxt"><b>' + esc(tt.name) + (cur ? '<span class="sr-only"> (standaard)</span>' : '') + '</b><small>' + esc(vpTunSub(tt)) + '</small></span>' +
      '<span class="vpmore" aria-hidden="true">⋯</span></button></div>';
  }).join('');
  // vanzelf
  const a = st.auto || {};
  $('#vp-a-wifi').checked = !!a.wifi; $('#vp-a-home').checked = a.home !== false; $('#vp-a-mobile').checked = !!a.mobile;
  const tr = a.trusted || [];
  $('#vp-trusted').innerHTML = tr.length ? tr.map(s => '<div class="row vptr"><span style="flex:1">📶 ' + esc(s) + '</span><button class="mini" style="float:none" data-s="' + esc(s) + '" onclick="vpUntrust(this.dataset.s)" aria-label="' + t('{naam} niet meer vertrouwen', { naam: esc(s) }) + '">✕</button></div>').join('')
    : '<p class="note" style="margin:0">Nog geen. Voeg je wifi thuis en op je werk toe.</p>';
  $('#vp-trust').textContent = st.ssid ? t('＋ "{naam}" vertrouwen', { naam: st.ssid }) : t('＋ Deze wifi vertrouwen');
  $('#vp-trust').style.display = st.ssid && !tr.includes(st.ssid) ? 'block' : 'none';
  const ln = $('#vp-locnote');
  const needBg = (a.wifi || a.mobile) && !st.bgLocOk;
  if ((a.wifi || a.mobile || tr.length) && st.locOk && st.locOn === false) {
    ln.style.display = 'block';
    ln.textContent = 'Locatie staat uit op je telefoon: dan weet Android niet op welke wifi je zit, en telt elke wifi als onbekend. Zet locatie aan in de snelle instellingen.';
  } else if (!st.locOk || needBg) {
    ln.style.display = 'block';
    ln.innerHTML = t(!st.locOk ? 'Om te zien op welke wifi je zit, heeft Android toestemming voor je locatie nodig.' : 'Om dat ook te zien als de app dicht is: locatie <b>Altijd toestaan</b>.') +
      ' <a href="#" onclick="Android.autoPerm(\'' + (st.locOk ? 'bgloc' : 'loc') + '\');return false">' + t('Toestaan') + '</a>';
  } else ln.style.display = 'none';
  // kill switch
  const ks = $('#vp-killstate');
  if (on && st.alwaysOn != null) ks.textContent = st.alwaysOn ? (st.lockdown ? '✓ Altijd aan, met kill switch.' : '✓ Altijd aan (kill switch staat uit).') : 'Altijd-aan staat nu uit.';
  else ks.textContent = '';
}
window.onVpnChanged = function(msg){ vpBusy = false; if (msg) toast(msg); if (current === 'vpn') vpRender(); if (current === 'home') refreshVpnTile(); };

const vpKind = tt => tt.type === 'ovpn' ? 'OpenVPN' : 'WireGuard';
function vpTunSub(tt){
  const fb = (tt.fallback || []).map(id => (vpTun.find(x => x.id === id) || {}).name).filter(Boolean);
  const where = tt.type === 'ovpn' ? (tt.summary || tt.endpoint || '') : (tt.endpoint || '') + (tt.full ? ' · ' + t('al je verkeer') : ' · ' + t('alleen {wat}', { wat: (tt.allowed || []).slice(0, 2).join(', ') }));
  return vpKind(tt) + ' · ' + where + (fb.length ? ' · ' + t('↪ reserve: {namen}', { namen: fb.join(', ') }) : '');
}
function vpToggle(){
  if (vpBusy) return;
  if (vpSt.on) {
    if (vpSt.alwaysOn) toast('Altijd-aan VPN staat aan: Android zet hem zo weer aan. Uitzetten doe je in de VPN-instellingen van Android.');
    Android.vpnStop(); setTimeout(() => vpRender(), 400); return;
  }
  const id = vpSt.active || (vpTun[0] && vpTun[0].id);
  vpBusy = true; vpRender(true);
  const r = Android.vpnStart(id);
  if (r === 'consent') { toast('Android vraagt of Tools een VPN mag maken: tik op OK'); return; }
  vpBusy = false;
  if (r) { toast(r); vpRender(); return; }
  setTimeout(() => vpRender(), 500);
}

function vpTunMenu(id){
  const tt = vpTun.find(x => x.id === id); if (!tt) return;
  const on = vpSt.on && vpSt.id === id;
  const acts = [];
  if (!on) acts.push(['▶ Deze tunnel aanzetten', () => { Android.vpnSetActive(id); vpSt.active = id; vpToggleTo(id); }]);
  if (!on && vpSt.active !== id) acts.push(['✓ Als standaard (tegel, snelkoppeling, vanzelf)', () => { Android.vpnSetActive(id); vpRender(); }]);
  acts.push(['✎ Bewerken', () => veOpen(id)]);
  if (vpTun.length > 1) acts.push(['↪ Reserve bij blokkade…', () => vpFallbackMenu(id)]);
  if (tt.pub) acts.push(['Mijn publieke sleutel kopiëren', () => { Android.vpnCopy(tt.pub); toast(t('Gekopieerd: {tekst}…', { tekst: tt.pub.slice(0, 12) })); }]);
  acts.push(['Verwijderen', () => askConfirm('Tunnel verwijderen?', t('"{naam}" en zijn sleutels verdwijnen van deze telefoon. Op de server verandert niets.', { naam: tt.name }), 'Verwijderen', () => { Android.vpnDelete(id); vpRender(); })]);
  openSheet(tt.name, (tt.endpoint || '') + ((tt.addr || []).length ? ' · ' + tt.addr.join(', ') : ''), acts);
}
/* Reserve-tunnels: lukt verbinden niet (bijv. UDP geblokkeerd), dan vanzelf de volgende */
function vpFallbackMenu(id){
  const tt = vpTun.find(x => x.id === id); if (!tt) return;
  const fb = (tt.fallback || []).filter(x => vpTun.some(y => y.id === x));
  const set = l => { Android.vpnSetFallback(id, JSON.stringify(l)); vpTun = rdJson(Android.vpnTunnels(), []); vpRender(); vpFallbackMenu(id); };
  const acts = [];
  vpTun.filter(x => x.id !== id).forEach(x => {
    const i = fb.indexOf(x.id);
    acts.push(i >= 0 ? [t('✓ {n}. {naam} ({soort}) — weghalen', { n: i + 1, naam: x.name, soort: vpKind(x) }), () => set(fb.filter(y => y !== x.id))]
                     : fb.length >= 4 ? [t('Hooguit 4 reserves'), () => {}] : [t('＋ {naam} ({soort}) als reserve', { naam: x.name, soort: vpKind(x) }), () => set(fb.concat([x.id]))]);
  });
  openSheet(t('Reserve voor {naam}', { naam: tt.name }), fb.length ? t('Eerst {naam}, lukt dat niet binnen een paar seconden, dan {reserves}. Op een nieuw netwerk weer eerst {naam}.', { naam: tt.name, reserves: fb.map(x => (vpTun.find(y => y.id === x) || {}).name).join(t(', dan ')) }) : t('Kies wat er gebruikt wordt als {naam} geblokkeerd is (bijv. in een hotel of op kantoor).', { naam: tt.name }), acts);
}
/* Na het toevoegen: de andere soort als reserve voorstellen */
function vpSuggestFallback(newId){
  const n = vpTun.find(x => x.id === newId); if (!n) return;
  const isOv = n.type === 'ovpn';
  const other = vpTun.find(x => x.id !== newId && (x.type === 'ovpn') !== isOv && !(x.fallback || []).length && !vpTun.some(y => (y.fallback || []).includes(x.id)));
  if (!other) return;
  const first = isOv ? other : n, second = isOv ? n : other;
  setTimeout(() => askConfirm('Reserve instellen?', t('Werkt "{eerste}" (WireGuard) ergens niet, bijvoorbeeld omdat een hotel of kantoor het blokkeert? Dan vanzelf "{tweede}" (OpenVPN) gebruiken. "{eerste}" wordt dan je standaard-tunnel.', { eerste: first.name, tweede: second.name }), 'Ja, als reserve', () => {
    Android.vpnSetFallback(first.id, JSON.stringify([second.id])); Android.vpnSetActive(first.id);
    vpTun = rdJson(Android.vpnTunnels(), []); vpRender(); toast(t('↪ {tweede} is de reserve van {eerste}', { eerste: first.name, tweede: second.name }));
  }), 400);
}
function vpToggleTo(id){
  vpBusy = true; vpRender(true);
  const r = Android.vpnStart(id);
  if (r === 'consent') return;
  vpBusy = false;
  if (r) toast(r);
  setTimeout(() => vpRender(), 500);
}

function vpAddMenu(){
  openSheet('Tunnel toevoegen', 'Van je eigen server of een VPN-aanbieder', [
    ['QR-code scannen', vpScan], ['QR-code uit een afbeelding', () => Android.vpnPickImage()], ['Bestand (.conf)', () => Android.vpnPickFile()],
    ['Tekst plakken', vpPaste], ['Zelf maken (met eigen sleutels)', vpNew]]);
}

/* vanzelf */
function vpAutoSet(){
  const a = Object.assign({}, vpSt.auto || {}, { wifi: $('#vp-a-wifi').checked, home: $('#vp-a-home').checked, mobile: $('#vp-a-mobile').checked });
  Android.vpnAuto(JSON.stringify(a));
  vpRender();
  if ((a.wifi || a.mobile) && !vpSt.bgLocOk) toast('Sta locatie toe (Altijd), anders kan de app je wifi niet herkennen');
}
function vpTrustCurrent(){
  const s = Android.vpnTrustCurrent();
  if (s) toast(t('📶 {naam} is vertrouwd', { naam: s })); else toast('Wifi-naam onbekend: sta locatie toe');
  vpRender();
}
function vpUntrust(s){
  const a = Object.assign({}, vpSt.auto || {});
  a.trusted = (a.trusted || []).filter(x => x !== s);
  Android.vpnAuto(JSON.stringify(a)); vpRender();
}

/* snel */
function vpAddTile(){
  const r = Android.vpnAddTile();
  if (r === 'manual') askConfirm('Tegel toevoegen', 'Veeg twee keer omlaag vanaf de bovenkant, tik op het potlood (bewerken) en sleep de tegel "VPN" naar je snelle instellingen.', 'Begrepen', () => {});
}
window.onVpnTile = function(r){ r = +r; toast(r === 2 || r === 1 ? (r === 2 ? '✓ Tegel toegevoegd' : 'De tegel staat er al') : r === 0 ? 'Niet toegevoegd' : 'Toevoegen lukte niet; voeg hem toe via het potlood in de snelle instellingen'); };
function vpPinToggle(){ const e = Android.vpnPinToggle(); toast(e || 'Bevestig op je startscherm om "VPN aan/uit" toe te voegen'); }

/* ---------- importeren ---------- */
function vpPaste(){ veOpen('', '', ''); setTimeout(() => { $('#ve-text').focus(); toast('Plak de hele configuratie in het tekstvak (WireGuard: begint met [Interface]; OpenVPN: de inhoud van het .ovpn-bestand)'); }, 300); }
function vpImport(text, name){
  text = String(text || '').replace(/\r/g, '');
  if (!/\[Interface\]/i.test(text) && !(/^\s*(client|tls-client)\b/im.test(text) && /^\s*remote\s/im.test(text))) { toast('Dit is geen WireGuard- of OpenVPN-configuratie'); return; }
  veOpen('', text, name);
}
window.onVpnImported = function(r){ if (!r || r.error) { toast((r && r.error) || 'Bestand niet te lezen'); return; } vpImport(r.text, r.name); };
window.onVpnImage = function(r){
  if (!r || r.error) { toast((r && r.error) || 'Afbeelding niet te lezen'); return; }
  vpLoadQr(() => {
    const img = new Image();
    img.onload = () => {
      const c = document.createElement('canvas'); c.width = img.naturalWidth; c.height = img.naturalHeight;
      const x = c.getContext('2d'); x.drawImage(img, 0, 0);
      const code = window.jsQR ? jsQR(x.getImageData(0, 0, c.width, c.height).data, c.width, c.height) : null;
      if (code && code.data) vpImport(code.data, ''); else toast('Geen QR-code gevonden in deze afbeelding');
    };
    img.onerror = () => toast('Afbeelding niet te lezen');
    img.src = r.data;
  });
};
function vpNew(){
  const k = rdJson(Android.vpnNewKey(), {});
  const tt = '[Interface]\nPrivateKey = ' + k.priv + '\nAddress = 10.0.0.2/32\nDNS = 1.1.1.1\n\n[Peer]\nPublicKey = \nAllowedIPs = 0.0.0.0/0, ::/0\nEndpoint = \nPersistentKeepalive = 25\n';
  veOpen('', tt, '', true);
}

/* QR-code scannen met de camera (jsQR; beelden gaan nergens heen) */
let vpStream = null, vpScanTimer = null;
function vpLoadQr(done){
  if (window.jsQR) { done(); return; }
  const s = document.createElement('script'); s.src = 'vendor/jsQR.js';
  s.onload = done; s.onerror = () => { Android.vpnCameraDone(); toast('QR-lezer laden lukt niet'); };
  document.head.appendChild(s);
}
function vpScan(){ Android.vpnCamera(); }
window.onVpnCamera = function(ok){
  if (current !== 'vpn' && current !== 'vpnedit') { Android.vpnCameraDone(); return; } // intussen weg gegaan
  if (!ok) { toast('Zonder camera kan het ook: QR-code uit een afbeelding (bijv. een schermafbeelding)'); return; }
  vpLoadQr(vpScanStart);
};
function vpScanStart(){
  const box = $('#vp-scan'), v = $('#vp-video');
  box.classList.add('on'); $('#vp-scanmsg').textContent = 'Camera starten…';
  if (!navigator.mediaDevices || !navigator.mediaDevices.getUserMedia) { $('#vp-scanmsg').textContent = 'De camera is hier niet beschikbaar. Gebruik "QR-code uit een afbeelding".'; return; }
  navigator.mediaDevices.getUserMedia({ video: { facingMode: 'environment' }, audio: false }).then(stream => {
    if (!box.classList.contains('on')) { stream.getTracks().forEach(tt => tt.stop()); return; }
    vpStream = stream; v.srcObject = stream; v.play().catch(() => {});
    $('#vp-scanmsg').textContent = 'Richt de camera op de QR-code';
    const c = document.createElement('canvas'), x = c.getContext('2d', { willReadFrequently: true });
    clearInterval(vpScanTimer);
    vpScanTimer = setInterval(() => {
      if (!v.videoWidth) return;
      const w = Math.min(960, v.videoWidth), h = Math.round(v.videoHeight * w / v.videoWidth);
      c.width = w; c.height = h; x.drawImage(v, 0, 0, w, h);
      const code = jsQR(x.getImageData(0, 0, w, h).data, w, h, { inversionAttempts: 'dontInvert' });
      if (code && code.data) {
        if (/\[Interface\]/i.test(code.data)) { vpScanClose(); vpImport(code.data, ''); }
        else $('#vp-scanmsg').textContent = 'Dit is een andere QR-code, geen WireGuard-configuratie';
      }
    }, 300);
  }).catch(() => { $('#vp-scanmsg').textContent = 'De camera start niet. Gebruik "QR-code uit een afbeelding".'; });
}
function vpScanClose(){
  clearInterval(vpScanTimer); vpScanTimer = null;
  if (vpStream) { vpStream.getTracks().forEach(tt => tt.stop()); vpStream = null; }
  const v = $('#vp-video'); v.srcObject = null;
  $('#vp-scan').classList.remove('on');
  Android.vpnCameraDone();
}
function vpScanOpen(){ return $('#vp-scan').classList.contains('on'); }

/* ---------- bewerken ---------- */
let veId = '', veTimer = null, veCfgUser = '', veHasPass = false;
function veOpen(id, text, name, isNew){
  veId = id || '';
  if (id) {
    const r = rdJson(Android.vpnConfig(id), {});
    if (r.error) { toast(r.error); return; }
    text = r.text; veCfgUser = r.user || ''; veHasPass = !!r.hasPass;
    name = (vpTun.find(tt => tt.id === id) || {}).name || '';
  }
  show('vpnedit');
  $('#ve-h').textContent = id ? 'Tunnel bewerken' : 'Nieuwe tunnel';
  $('#ve-name').value = name || '';
  $('#ve-text').value = text || '';
  $('#ve-user').value = id ? (veCfgUser || '') : ''; $('#ve-pass').value = '';
  $('#ve-pass').placeholder = id && veHasPass ? '(bewaard; leeg laten = zo houden)' : '';
  veCheck();
  if (isNew) setTimeout(() => toast('Vul PublicKey en Endpoint van je server in, en zet jouw publieke sleutel op de server'), 300);
}
function veCheckSoon(){ clearTimeout(veTimer); veTimer = setTimeout(veCheck, 350); }
function veCheck(){
  const text = $('#ve-text').value, m = /PrivateKey\s*=\s*(\S+)/i.exec(text);
  const pub = m && /\[Interface\]/i.test(text) ? Android.vpnPubOf(m[1]) : '';
  $('#ve-pubbox').style.display = pub ? 'block' : 'none';
  $('#ve-pub').textContent = pub;
  const r = rdJson(Android.vpnCheck(text), {}), c = $('#ve-check');
  if (r.error) { c.className = 'note bad'; c.textContent = '⚠ ' + r.error; $('#ve-authbox').style.display = /auth-user-pass/.test(text) ? 'block' : 'none'; return false; }
  const s = r.summary || {};
  c.className = 'note';
  $('#ve-authbox').style.display = s.type === 'ovpn' && s.auth ? 'block' : 'none';
  if (s.type === 'ovpn') {
    c.textContent = t('✓ Klopt') + ' · OpenVPN · ' + (s.summary || s.endpoint) + ' · ' + t(s.auth ? 'inloggen met gebruikersnaam en wachtwoord' : 'met certificaat') + ((s.warnings || []).length ? ' · ' + t('let op: {wat}', { wat: s.warnings.map(x => t(x)).join('; ') }) : '');
    return true;
  }
  c.textContent = t('✓ Klopt') + ' · WireGuard · ' + t('server {adres}', { adres: s.endpoint || '?' }) + ' · ' + (s.full ? t('al je verkeer via de VPN') : t('alleen {wat}', { wat: (s.allowed || []).join(', ') })) + (s.dns && s.dns.length ? ' · DNS ' /* taal-vast: afkorting */ + s.dns.join(', ') : ' · ' + t('let op: geen DNS ingesteld'));
  return true;
}
function vePubCopy(){ const p = $('#ve-pub').textContent; if (p) { Android.vpnCopy(p); toast('Publieke sleutel gekopieerd'); } }
function veSave(){
  if (!veCheck()) { toast($('#ve-check').textContent.replace(/^⚠ /, '')); return; }
  const r = rdJson(Android.vpnSave(veId, $('#ve-name').value, $('#ve-text').value, $('#ve-user').value, $('#ve-pass').value), {});
  if (r.error) { toast(r.error); return; }
  $('#ve-pass').value = '';
  toast(veId ? (r.restart ? 'Opgeslagen; de tunnel start opnieuw' : 'Opgeslagen') : 'Tunnel toegevoegd');
  const wasNew = !veId;
  vpTun = rdJson(Android.vpnTunnels(), []);
  show('vpn');
  if (wasNew) vpSuggestFallback(r.id);
}

/* tegel op het startscherm van de app */
function refreshVpnTile(){
  const el = $('#tile-vpn-sub'); if (!el) return;
  const st = rdJson(Android.vpnState(), {});
  el.textContent = st.on ? (st.status === 'connected' ? t('Aan') + ' · ' + (st.name || '') : t('Verbinden…')) : t('WireGuard, uit');
}
// App naar de achtergrond: camera los (anders staat het beeld stil bij terugkomen)
(function(){ const previous = window.onPauseApp; window.onPauseApp = function(){ if (previous) previous(); if (vpScanOpen()) vpScanClose(); }; })();
