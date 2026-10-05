/* ---------- Instellingen ---------- */
function stJson(s){ try { return JSON.parse(s); } catch(e){ return {}; } }
function enterSettings(){
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

