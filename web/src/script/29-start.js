/* ---------- start ---------- */
$('#hver').textContent = '(v' + ver().webName + ')';
bars(); matchMedia('(prefers-color-scheme: dark)').addEventListener('change', bars);
refreshTile(); refreshWaTile(); refreshTrackTile(); refreshSmsTile(); refreshCallsTile(); refreshNotesTile(); rdPollOnce(); bkPollOnce(); refreshPodTile();
const po = SOLO || Android.pendingOpen(); if (po) openTool(po); // losse app: meteen de eigen tool
// Nieuwe installatie: eerst aanbieden om je gegevens terug te zetten; net teruggezet: het lijstje van wat opnieuw moet
if (!SOLO && po !== 'herstel') { const hs = hsState(); if (hs.pending || hs.after) show('herstel'); }
if (!SOLO && stJson(Android.lockState()).locked) showLock();
// Pas nu mag de app zichtbaar worden (met app-slot staat het slotscherm er dan al; geen flits van het startscherm)
setTimeout(() => { try { if (typeof Android.uiReady === 'function') Android.uiReady(); } catch(e){} }, 0); // (geen requestAnimationFrame: die loopt niet in een verborgen WebView)
(function(){
  const owc = window.onWaChanged;
  window.onWaChanged = function(){ if (owc) owc(); if (stOn()) enterSettings(); if (current === 'backup') enterBackup(); if (current === 'spotify') spRender(); if(current==='sms')enterSms(); };
  // Bestaande gebruikers (van voor deze functie) krijgen ook "Wat is er nieuw" te zien.
  if (store.get('seenVersion', null) == null && !Android.freshInstall()) store.set('seenVersion', VERSION_CODE - 1);
  if (!SOLO) setTimeout(() => {
    if (current === 'herstel') return; // eerst terugzetten (of overslaan); "Wat is er nieuw" komt later
    if (Android.crashNew()) askConfirm('De app is vastgelopen', 'Tools is de vorige keer onverwacht gestopt. Wil je het foutrapport delen, zodat het opgelost kan worden?', 'Delen', () => Android.crashShare());
    else whatsNew();
  }, 600);
})();
