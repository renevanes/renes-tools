/* ---------- start ---------- */
$('#hver').textContent = 'v' + ver().webName;
bars(); matchMedia('(prefers-color-scheme: dark)').addEventListener('change', bars);
refreshTile(); refreshWaTile(); refreshTrackTile(); refreshSmsTile(); refreshCallsTile(); refreshNotesTile(); rdPollOnce(); bkPollOnce(); refreshPodTile();
const po = Android.pendingOpen(); if (po) openTool(po);
if (stJson(Android.lockState()).locked) showLock();
// Pas nu mag de app zichtbaar worden (met app-slot staat het slotscherm er dan al; geen flits van het startscherm)
setTimeout(() => { try { if (typeof Android.uiReady === 'function') Android.uiReady(); } catch(e){} }, 0); // (geen requestAnimationFrame: die loopt niet in een verborgen WebView)
(function(){
  const owc = window.onWaChanged;
  window.onWaChanged = function(){ if (owc) owc(); if (current === 'settings') enterSettings(); if (current === 'backup') enterBackup(); };
  // Bestaande gebruikers (van voor deze functie) krijgen ook "Wat is er nieuw" te zien.
  if (store.get('seenVersion', null) == null && !Android.freshInstall()) store.set('seenVersion', VERSION_CODE - 1);
  setTimeout(() => {
    if (Android.crashNew()) askConfirm('De app is vastgelopen', 'Rene\'s Tools is de vorige keer onverwacht gestopt. Wil je het foutrapport delen, zodat het opgelost kan worden?', 'Delen', () => Android.crashShare());
    else whatsNew();
  }, 600);
})();
