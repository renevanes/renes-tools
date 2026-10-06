/* ---------- Instellingen ---------- */
function stJson(s){ try { return JSON.parse(s); } catch(e){ return {}; } }
/* Meldingen van deze app: één lijst met wat aan en uit staat; tikken = die soort in Android. */
const NLEVEL = ['Uit', 'Stil', 'Zacht', 'Normaal', 'Met geluid'];
function stNotifRender(){
  if (typeof Android.notifState !== 'function') return;
  const s = stJson(Android.notifState());
  const muted = !!s.muted, off = !s.allowed;
  const mb = $('#st-notif-mute'); if (mb) { mb.classList.toggle('on', muted); mb.setAttribute('aria-pressed', muted ? 'true' : 'false'); }
  $('#st-notif-state').textContent = muted ? '🔕 Alle meldingen staan uit. Je krijgt geen herinneringen, backup- of automatiseringsmeldingen. Draait de radio of een route-opname, dan toont Android alleen een stille regel onderaan je meldingen.'
    : off ? 'Meldingen staan uit voor deze app: je mist herinneringen, de radiowekker en backup-meldingen.' : '✓ Meldingen staan aan.';
  $('#st-notif-ask').style.display = off && !muted ? 'block' : 'none';
  $('#st-notif-list').style.opacity = muted ? '.45' : '';
  $('#st-notif-list').innerHTML = (s.kinds || []).map(k => '<button class=nkind data-id="' + esc(k.id) + '" onclick="Android.notifOpen(this.dataset.id)"><span><b>' + esc(k.name) + '</b><small>' + esc(k.desc) + '</small></span>' +
    '<span class="nst' + (off || !k.on ? ' off' : '') + '">' + (off ? 'Uit' : NLEVEL[k.level] || 'Aan') + '</span><span aria-hidden=true>›</span></button>').join('');
}
function stNotifMute(on){
  if (typeof Android.notifMute !== 'function') return;
  Android.notifMute(!!on);
  toast(on ? 'Alle meldingen van de app staan uit' : 'Meldingen staan weer aan');
  stNotifRender();
}
function enterSettings(){
  stNotifRender();
  $('#st-zoom').value = String(Android.textZoomGet());
  stHomeRender();
  const i = stJson(Android.settingsInfo());
  const lk = i.lock || {};
  $('#st-lock-state').textContent = lk.on ? 'Aan' : (lk.secure ? 'Uit' : 'Uit · stel eerst een schermvergrendeling in op je telefoon');
  $('#st-lock-btn').textContent = lk.on ? 'Uitzetten' : 'Aanzetten';
  $('#st-lock-tw').style.display = lk.on ? 'flex' : 'none';
  $('#st-lock-timeout').value = String(lk.timeout != null ? lk.timeout : 0);
  $('#st-dest').textContent = i.dest ? 'Huidige map: ' + (i.destName || 'gekozen') : 'Nog niet gekozen';
  $('#st-audd').textContent = i.audd ? 'Sleutel ingesteld (' + i.audd + ')' : 'Nog geen sleutel ingesteld';
  $('#st-perms').innerHTML = (i.perms || []).map(p =>
    '<div class="row setrow"><span style="flex:1"><b>' + esc(p.name) + '</b><small class=note style="display:block;margin:0">' + esc(p.used) + '</small></span>' +
    (p.ok ? '<span class=permok>✓ Aan</span>' : '<button class="mini" style="float:none" data-p="' + esc(p.id) + '" onclick="Android.permRequest(this.dataset.p)">Toestaan</button>') + '</div>').join('');
  $('#st-crash').textContent = i.crashes ? i.crashes + (i.crashes === 1 ? ' fout' : ' fouten') + ' vastgelegd.' : 'Er zijn geen fouten vastgelegd. ✓';
}
function stHomeRender(){
  const on = Android.homeIsDefault();
  $('#st-home-state').textContent = on ? '✓ Rene\'s Tools is nu je startscherm.' : '';
  $('#st-home-btn').style.display = on ? 'none' : 'block';
  $('#st-home-back').style.display = on ? 'block' : 'none';
}
window.onHomeRole = function(){ if (current === 'settings') stHomeRender(); if (Android.homeIsDefault()) toast('✓ Startscherm ingesteld; druk op de home-knop'); };
window.onSettingsChanged = function(msg){ if (msg) toast(msg); if (current === 'settings') enterSettings(); };
function stLockToggle(){ const lk = stJson(Android.lockState()); Android.lockSet(!lk.on); }
function stCrashClear(){ askConfirm('Foutrapport wissen?', 'De vastgelegde foutmeldingen worden verwijderd.', 'Wissen', () => { Android.crashClear(); enterSettings(); }); }

