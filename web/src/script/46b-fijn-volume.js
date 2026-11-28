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
  if (typeof plIsOpen !== 'undefined' && plIsOpen && $('#pl-volwrap').style.display !== 'none') { const r = $('#pl-vol'); r.max = max; r.value = n; if (typeof plVolTxt === 'function') plVolTxt(r); return; }
  toast(t('Volume {p}%', { p: fmtNum(Math.round(n / max * 100)) }));
};

/* Zacht beginnen (Java: PodcastZacht): een luisterbeurt in de avond begint op een zacht volume. Kaart op Instellingen van Podcasts. */
function psZachtInfo(){ return typeof Android.podZacht === 'function' ? rdJson(Android.podZacht(), null) : null; } // oudere app: de kaart blijft weg
function psZachtHm(m){ return String(Math.floor(m / 60)).padStart(2, '0') + ':' + String(m % 60).padStart(2, '0'); }
function psZachtMin(v, std){ const m = /^(\d{1,2}):(\d{2})$/.exec(String(v || '')); return m && +m[1] < 24 && +m[2] < 60 ? +m[1] * 60 + +m[2] : std; }
/** Uitleg bij de stand. Zuiver. */
function psZachtUitleg(i){
  if (i.wijze === 'uit') return t('Uit: de speler begint op het volume van je telefoon.');
  const waar = i.luidspreker ? ' ' + t('Alleen op de luidspreker van de telefoon.') : '';
  if (i.wijze === 'altijd') return t('Elke keer dat je begint met luisteren (na een half uur of langer niets) begint het zacht.') + waar;
  return t('Begin je tussen {van} en {tot} met luisteren (na een half uur of langer niets), dan begint het zacht.', { van: fmtHm(psZachtHm(i.van)), tot: fmtHm(psZachtHm(i.tot)) }) + waar;
}
function psZachtTxt(){ $('#ps-zacht-pt').textContent = t('{p}% van het hoogste volume', { p: fmtNum(+$('#ps-zacht-pct').value) }); }
function psZachtRender(){
  const kaart = $('#ps-zk'); if (!kaart) return;
  const i = psZachtInfo(); kaart.hidden = !i || !i.wijze; if (kaart.hidden) return;
  $('#ps-zacht').value = i.wijze;
  $('#ps-zacht-meer').hidden = i.wijze === 'uit'; $('#ps-zacht-tijd').style.display = i.wijze === 'avond' ? '' : 'none';
  $('#ps-zacht-van').value = psZachtHm(i.van); $('#ps-zacht-tot').value = psZachtHm(i.tot);
  const r = $('#ps-zacht-pct'); r.min = i.min || 1; r.max = i.max || 40; r.value = i.pct; psZachtTxt();
  const nu = $('#ps-zacht-nu'), kan = i.nuPct >= (i.min || 1) && i.nuPct <= (i.max || 40) && i.nuPct !== i.pct;
  nu.style.display = kan ? '' : 'none'; if (kan) nu.textContent = t('Het volume van nu gebruiken ({p}%)', { p: fmtNum(i.nuPct) });
  const ls = $('#ps-zacht-ls'); ls.classList.toggle('on', !!i.luidspreker); ls.setAttribute('aria-checked', i.luidspreker ? 'true' : 'false');
  $('#ps-zacht-u').textContent = psZachtUitleg(i);
}
function psZachtZet(o){ const i = psZachtInfo(); if (!i) return; Android.podZachtZet(JSON.stringify(Object.assign({ wijze: i.wijze, van: i.van, tot: i.tot, pct: i.pct, luidspreker: !!i.luidspreker }, o))); if (current === 'podset') psZachtRender(); }
function psZacht(){
  const i = psZachtInfo(); if (!i) return;
  psZachtZet({ wijze: $('#ps-zacht').value, van: psZachtMin($('#ps-zacht-van').value, i.van), tot: psZachtMin($('#ps-zacht-tot').value, i.tot), pct: +$('#ps-zacht-pct').value });
}
function psZachtNu(){ const i = psZachtInfo(); if (i && i.nuPct > 0) psZachtZet({ pct: i.nuPct }); }
function psZachtLs(){ const i = psZachtInfo(); if (i) psZachtZet({ luidspreker: !i.luidspreker }); }
/** Korte melding van de speler (Java: PodcastService.melding): zacht begonnen, of terug in de vorige aflevering. Eén keer per melding. */
let pcMeldT = 0;
function pcMeldKort(st){
  const m = st && st.meld; if (!m || !(m.t > pcMeldT)) return;
  const eerste = !pcMeldT; pcMeldT = m.t;
  if (eerste && Date.now() - m.t > 15000) return; // van voor het openen van de app
  if (m.wat === 'zacht') toast(t('Zacht begonnen. Harder kan met de volumeknoppen.'));
  else if (m.wat === 'terug') toast(t('Terug in de vorige aflevering. Met ⏭ ga je weer verder.'));
}
