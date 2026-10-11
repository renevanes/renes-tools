/* ---------- VPN (WireGuard) ---------- */
let vpSt = {}, vpTun = [], vpTimer = null, vpBusy = false;

function enterVpn(){ vpRender(); clearInterval(vpTimer); vpTimer = setInterval(() => { if (current === 'vpn' && !appPaused) vpRender(true); else if (current !== 'vpn') { clearInterval(vpTimer); vpTimer = null; } }, 1500); }

function vpAgo(t){
  if (!t) return 'nog niet';
  const s = Math.max(0, Math.round((Date.now() - t) / 1000));
  return s < 60 ? s + ' s geleden' : s < 3600 ? Math.round(s / 60) + ' min geleden' : new Date(t).toLocaleTimeString('nl-NL', { hour: '2-digit', minute: '2-digit' });
}
function vpSize(b){ b = +b || 0; return b < 1024 ? b + ' B' : b < 1048576 ? Math.round(b / 1024) + ' kB' : b < 1073741824 ? (b / 1048576).toFixed(1).replace('.', ',') + ' MB' : (b / 1073741824).toFixed(2).replace('.', ',') + ' GB'; }
const VP_REASON = { app: '', tegel: 'via de tegel', snelkoppeling: 'via de snelkoppeling', wifi: 'vanzelf: onbekende wifi', mobiel: 'vanzelf: mobiel internet', auto: 'door Automatiseren', altijd: 'Altijd-aan VPN van Android', melding: 'via de melding' };

function vpRender(light){
  vpSt = rdJson(Android.vpnState(), {});
  if (!light) vpTun = rdJson(Android.vpnTunnels(), []);
  const has = vpTun.length > 0, st = vpSt, on = !!st.on;
  $('#vp-intro').style.display = has ? 'none' : 'block';
  ['#vp-main', '#vp-listwrap', '#vp-autocard', '#vp-quick', '#vp-kill'].forEach(s => $(s).style.display = has ? 'block' : 'none');
  if (!has) return;
  const act = vpTun.find(t => t.id === (on ? st.id : st.active)) || vpTun[0];
  const connected = on && st.status === 'connected';
  $('#vp-dot').className = 'vpdot' + (connected ? ' on' : on ? ' busy' : '');
  $('#vp-title').textContent = on ? (connected ? 'Verbonden' : 'Verbinden…') + ' · ' + (st.name || act.name) : 'VPN uit';
  let sub = on ? (VP_REASON[st.reason] || '') : (act ? 'Tunnel: ' + act.name : '');
  if (on && !connected && st.error) sub = st.error + ' · probeert het opnieuw';
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
  $('#vp-list').innerHTML = vpTun.map(t => {
    const cur = t.id === (on ? st.id : st.active);
    return '<div class="srow"><button class="sbtn" data-id="' + esc(t.id) + '" onclick="vpTunMenu(this.dataset.id)"><span class="vpic' + (cur ? ' cur' : '') + '" aria-hidden="true">' + (cur ? '✓' : '🔑') + '</span>' +
      '<span class="stxt"><b>' + esc(t.name) + (cur ? '<span class="sr-only"> (standaard)</span>' : '') + '</b><small>' + esc((t.endpoint || '') + (t.full ? ' · al je verkeer' : ' · alleen ' + (t.allowed || []).slice(0, 2).join(', '))) + '</small></span>' +
      '<span class="vpmore" aria-hidden="true">⋯</span></button></div>';
  }).join('');
  // vanzelf
  const a = st.auto || {};
  $('#vp-a-wifi').checked = !!a.wifi; $('#vp-a-home').checked = a.home !== false; $('#vp-a-mobile').checked = !!a.mobile;
  const tr = a.trusted || [];
  $('#vp-trusted').innerHTML = tr.length ? tr.map(s => '<div class="row vptr"><span style="flex:1">📶 ' + esc(s) + '</span><button class="mini" style="float:none" data-s="' + esc(s) + '" onclick="vpUntrust(this.dataset.s)" aria-label="' + esc(s) + ' niet meer vertrouwen">✕</button></div>').join('')
    : '<p class="note" style="margin:0">Nog geen. Voeg je wifi thuis en op je werk toe.</p>';
  $('#vp-trust').textContent = st.ssid ? '＋ "' + st.ssid + '" vertrouwen' : '＋ Deze wifi vertrouwen';
  $('#vp-trust').style.display = st.ssid && !tr.includes(st.ssid) ? 'block' : 'none';
  const ln = $('#vp-locnote');
  const needBg = (a.wifi || a.mobile) && !st.bgLocOk;
  if ((a.wifi || a.mobile || tr.length) && st.locOk && st.locOn === false) {
    ln.style.display = 'block';
    ln.textContent = 'Locatie staat uit op je telefoon: dan weet Android niet op welke wifi je zit, en telt elke wifi als onbekend. Zet locatie aan in de snelle instellingen.';
  } else if (!st.locOk || needBg) {
    ln.style.display = 'block';
    ln.innerHTML = (!st.locOk ? 'Om te zien op welke wifi je zit, heeft Android toestemming voor je locatie nodig.' : 'Om dat ook te zien als de app dicht is: locatie <b>Altijd toestaan</b>.') +
      ' <a href="#" onclick="Android.autoPerm(\'' + (st.locOk ? 'bgloc' : 'loc') + '\');return false">Toestaan</a>';
  } else ln.style.display = 'none';
  // kill switch
  const ks = $('#vp-killstate');
  if (on && st.alwaysOn != null) ks.textContent = st.alwaysOn ? (st.lockdown ? '✓ Altijd aan, met kill switch.' : '✓ Altijd aan (kill switch staat uit).') : 'Altijd-aan staat nu uit.';
  else ks.textContent = '';
}
window.onVpnChanged = function(msg){ vpBusy = false; if (msg) toast(msg); if (current === 'vpn') vpRender(); if (current === 'home') refreshVpnTile(); };

function vpToggle(){
  if (vpBusy) return;
  if (vpSt.on) {
    if (vpSt.alwaysOn) toast('Altijd-aan VPN staat aan: Android zet hem zo weer aan. Uitzetten doe je in de VPN-instellingen van Android.');
    Android.vpnStop(); setTimeout(() => vpRender(), 400); return;
  }
  const id = vpSt.active || (vpTun[0] && vpTun[0].id);
  vpBusy = true; vpRender(true);
  const r = Android.vpnStart(id);
  if (r === 'consent') { toast('Android vraagt of Rene\'s Tools een VPN mag maken: tik op OK'); return; }
  vpBusy = false;
  if (r) { toast(r); vpRender(); return; }
  setTimeout(() => vpRender(), 500);
}

function vpTunMenu(id){
  const t = vpTun.find(x => x.id === id); if (!t) return;
  const on = vpSt.on && vpSt.id === id;
  const acts = [];
  if (!on) acts.push(['▶ Deze tunnel aanzetten', () => { Android.vpnSetActive(id); vpSt.active = id; vpToggleTo(id); }]);
  if (!on && vpSt.active !== id) acts.push(['✓ Als standaard (tegel, snelkoppeling, vanzelf)', () => { Android.vpnSetActive(id); vpRender(); }]);
  acts.push(['✎ Bewerken', () => veOpen(id)]);
  acts.push(['🔑 Mijn publieke sleutel kopiëren', () => { Android.vpnCopy(t.pub); toast('Gekopieerd: ' + t.pub.slice(0, 12) + '…'); }]);
  acts.push(['🗑 Verwijderen', () => askConfirm('Tunnel verwijderen?', '"' + t.name + '" en zijn sleutels verdwijnen van deze telefoon. Op de server verandert niets.', 'Verwijderen', () => { Android.vpnDelete(id); vpRender(); })]);
  openSheet(t.name, (t.endpoint || '') + ((t.addr || []).length ? ' · ' + t.addr.join(', ') : ''), acts);
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
    ['📷 QR-code scannen', vpScan], ['🖼️ QR-code uit een afbeelding', () => Android.vpnPickImage()], ['📄 Bestand (.conf)', () => Android.vpnPickFile()],
    ['📋 Tekst plakken', vpPaste], ['🔑 Zelf maken (met eigen sleutels)', vpNew]]);
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
  if (s) toast('📶 ' + s + ' is vertrouwd'); else toast('Wifi-naam onbekend: sta locatie toe');
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
function vpPaste(){ veOpen('', '', ''); setTimeout(() => { $('#ve-text').focus(); toast('Plak de hele configuratie in het tekstvak (begint met [Interface])'); }, 300); }
function vpImport(text, name){
  text = String(text || '').replace(/\r/g, '');
  if (!/\[Interface\]/i.test(text)) { toast('Dit is geen WireGuard-configuratie (er staat geen [Interface] in)'); return; }
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
  const t = '[Interface]\nPrivateKey = ' + k.priv + '\nAddress = 10.0.0.2/32\nDNS = 1.1.1.1\n\n[Peer]\nPublicKey = \nAllowedIPs = 0.0.0.0/0, ::/0\nEndpoint = \nPersistentKeepalive = 25\n';
  veOpen('', t, '', true);
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
    if (!box.classList.contains('on')) { stream.getTracks().forEach(t => t.stop()); return; }
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
  if (vpStream) { vpStream.getTracks().forEach(t => t.stop()); vpStream = null; }
  const v = $('#vp-video'); v.srcObject = null;
  $('#vp-scan').classList.remove('on');
  Android.vpnCameraDone();
}
function vpScanOpen(){ return $('#vp-scan').classList.contains('on'); }

/* ---------- bewerken ---------- */
let veId = '', veTimer = null;
function veOpen(id, text, name, isNew){
  veId = id || '';
  if (id) {
    const r = rdJson(Android.vpnConfig(id), {});
    if (r.error) { toast(r.error); return; }
    text = r.text;
    name = (vpTun.find(t => t.id === id) || {}).name || '';
  }
  show('vpnedit');
  $('#ve-h').textContent = id ? 'Tunnel bewerken' : 'Nieuwe tunnel';
  $('#ve-name').value = name || '';
  $('#ve-text').value = text || '';
  veCheck();
  if (isNew) setTimeout(() => toast('Vul PublicKey en Endpoint van je server in, en zet jouw publieke sleutel op de server'), 300);
}
function veCheckSoon(){ clearTimeout(veTimer); veTimer = setTimeout(veCheck, 350); }
function veCheck(){
  const text = $('#ve-text').value, m = /PrivateKey\s*=\s*(\S+)/i.exec(text);
  const pub = m ? Android.vpnPubOf(m[1]) : '';
  $('#ve-pubbox').style.display = pub ? 'block' : 'none';
  $('#ve-pub').textContent = pub;
  const r = rdJson(Android.vpnCheck(text), {}), c = $('#ve-check');
  if (r.error) { c.className = 'note bad'; c.textContent = '⚠ ' + r.error; return false; }
  const s = r.summary || {};
  c.className = 'note';
  c.textContent = '✓ Klopt · server ' + (s.endpoint || '?') + ' · ' + (s.full ? 'al je verkeer via de VPN' : 'alleen ' + (s.allowed || []).join(', ')) + (s.dns && s.dns.length ? ' · DNS ' + s.dns.join(', ') : ' · let op: geen DNS ingesteld');
  return true;
}
function vePubCopy(){ const p = $('#ve-pub').textContent; if (p) { Android.vpnCopy(p); toast('Publieke sleutel gekopieerd'); } }
function veSave(){
  if (!veCheck()) { toast($('#ve-check').textContent.replace(/^⚠ /, '')); return; }
  const r = rdJson(Android.vpnSave(veId, $('#ve-name').value, $('#ve-text').value), {});
  if (r.error) { toast(r.error); return; }
  toast(veId ? (r.restart ? 'Opgeslagen; de tunnel start opnieuw' : 'Opgeslagen') : 'Tunnel toegevoegd');
  vpTun = rdJson(Android.vpnTunnels(), []);
  show('vpn');
}

/* tegel op het startscherm van de app */
function refreshVpnTile(){
  const el = $('#tile-vpn-sub'); if (!el) return;
  const st = rdJson(Android.vpnState(), {});
  el.textContent = st.on ? (st.status === 'connected' ? '🔒 Aan · ' + (st.name || '') : 'Verbinden…') : 'WireGuard, uit';
}
// App naar de achtergrond: camera los (anders staat het beeld stil bij terugkomen)
(function(){ const previous = window.onPauseApp; window.onPauseApp = function(){ if (previous) previous(); if (vpScanOpen()) vpScanClose(); }; })();
