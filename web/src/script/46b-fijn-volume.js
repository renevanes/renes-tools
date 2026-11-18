/* Fijner volume met de volumeknoppen (podcasts, radio en voorlezen; Java: FijnVolume).
   Dezelfde kaart staat bij Podcasts → Instellingen (voorvoegsel ps) en bij Spraak (sv). */
function fvInfo(){
  const d = { aan: true, fijn: 3, keuzes: [2, 3, 4], stappen: 15 };
  if (typeof Android.fijnVolume !== 'function') return null; // oudere app: geen fijn volume
  const o = rdJson(Android.fijnVolume(), {});
  return Object.assign(d, o, { stappen: o.stappen > 0 ? o.stappen : 15 });
}
/** Uitleg bij de stand. Zuiver. */
function fvUitleg(i){
  if (!i.aan) return t('Uit: de gewone stappen van Android.');
  return t('Elke stap van de volumeknoppen wordt {n} kleinere: {m} stappen in plaats van {s}. Vasthouden of snel drukken gaat met hele stappen.', { n: i.fijn, m: i.fijn * i.stappen, s: i.stappen });
}
function fvRender(pre){
  const kaart = $('#' + pre + '-fvk'); if (!kaart) return;
  const i = fvInfo(); kaart.hidden = !i; if (!i) return;
  const b = $('#' + pre + '-fv'); b.classList.toggle('on', !!i.aan); b.setAttribute('aria-checked', i.aan ? 'true' : 'false');
  const sel = $('#' + pre + '-fvstap'), sig = i.keuzes.join() + '|' + i.stappen + '|' + rgLang().id;
  if (sel.dataset.sig !== sig){
    sel.dataset.sig = sig;
    sel.replaceChildren(...i.keuzes.map(n => { const o = document.createElement('option'); o.value = String(n); o.textContent = t('{n} keer zo fijn ({m} stappen)', { n, m: n * i.stappen }); return o; }));
  }
  sel.value = String(i.fijn); sel.disabled = !i.aan;
  $('#' + pre + '-fvu').textContent = fvUitleg(i);
}
function fvZet(pre, wat, v){
  const i = fvInfo(); if (!i) return;
  if (wat === 'aan') Android.fijnVolumeSet(!i.aan, i.fijn);
  else Android.fijnVolumeSet(i.aan, +v);
  fvRender(pre);
}
/** Een volumeknop terwijl Tools in beeld is en speelt (Java: MainActivity.dispatchKeyEvent). */
window.onFijnVolume = function(n, max){
  if (!(max > 0)) return;
  // Grote speler open: de schuif laat het zien (geen melding erover)
  if (typeof plIsOpen !== 'undefined' && plIsOpen && $('#pl-volwrap').style.display !== 'none') { const r = $('#pl-vol'); r.max = max; r.value = n; return; }
  toast(t('Volume {p}%', { p: fmtNum(Math.round(n / max * 100)) }));
};
