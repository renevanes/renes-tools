/* ---------- Versie & updates ---------- */
function ver(){ try { return JSON.parse(Android.version()); } catch(e){ return {}; } }
function renderInfo(){
  const v = ver();
  $('#i-ver').textContent = v.webName + ' (' + v.webCode + ')';
  $('#i-app').textContent = v.appName + ' (' + v.appCode + ')';
  $('#i-android').textContent = v.android;
  let log = CHANGELOG;
  try { const m = JSON.parse(Android.lastManifest()); if (m && m.changelog && m.versionCode >= VERSION_CODE) log = m.changelog; } catch(e){}
  $('#log').innerHTML = log.map(e => '<h3>Versie ' + esc(e.version) + ' <small>' + esc(e.date||'') + '</small></h3><ul>' + e.changes.map(c=>'<li>'+esc(c)+'</li>').join('') + '</ul>').join('');
}
function checkUpdates(){ $('#chk-btn').textContent = 'Bezig met controleren…'; Android.checkUpdates(); }
function installUpdate(){ toast('Update wordt gedownload…'); Android.installUpdate(); }
window.onUpdateStatus = function(r){
  $('#chk-btn').textContent = 'Controleren op updates';
  if (r.state === 'apk') {
    ['#upd-banner','#upd-banner2'].forEach(b=>$(b).classList.add('on'));
    $('#upd-text').textContent = $('#upd-text2').textContent = 'Nieuwe versie ' + r.versionName + ' is beschikbaar';
  } else if (r.state === 'uptodate' && r.manual) toast('Je hebt de nieuwste versie (' + ver().webName + ') ✓');
  else if (r.state === 'needs-permission') toast('Sta "Installeren van onbekende apps" toe en tik daarna nog eens op Installeren');
  else if (r.state === 'error' && (r.manual || r.error.indexOf('nstall') >= 0 || r.error.indexOf('pdate') >= 0)) toast(r.error);
};

/* Licenties: van de app en van de onderdelen van anderen (de teksten zitten ook in de app, in assets/licenties/) */
function infoLicenses(){
  const G = 'https://github.com/renevanes/renes-tools/blob/main/';
  openSheet('Licenties', 'Tools: GPL-3.0-or-later. Onderdelen van anderen staan hieronder.', [
    ['Tools — GNU GPL versie 3', () => Android.openUrl(G + 'LICENSE')],
    ['Leaflet (kaarten) — BSD-2', () => Android.openUrl(G + 'web/vendor/LEAFLET-LICENSE')],
    ['jsQR (QR-codes lezen) — Apache-2.0', () => Android.openUrl(G + 'web/vendor/JSQR-LICENSE')],
    ['whisper.cpp (spraak naar tekst) — MIT', () => Android.openUrl(G + 'app/lib/arm64-v8a/LICENSE-whisper.cpp.txt')],
    ['llama.cpp (lokale AI) — MIT', () => Android.openUrl(G + 'app/lib/arm64-v8a/LICENSE-llama.cpp.txt')],
    ['musl (in whisper.cpp en llama.cpp) — MIT', () => Android.openUrl(G + 'app/lib/arm64-v8a/LICENSE-musl.txt')]
  ]);
}
