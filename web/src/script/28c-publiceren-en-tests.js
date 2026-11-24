/* Publiceren en tests: de stand van de workflows en de open punten op GitHub (alleen lezen, via Java: GithubStatus).
   Alles wat van GitHub komt (namen, titels, takken, labels) is tekst van buiten: altijd esc() en translate="no".
   Een tik opent de run of het punt in de browser; het adres komt uit de opgehaalde gegevens (Java laat alleen adressen
   van deze repository door), nooit uit het scherm. */
const gh = { data: null, meer: false, laden: false };
const GH_SOORT = {
  bezig: ['⏳', 'ghwarn'], gelukt: ['✓', 'good'], mislukt: ['✗', 'bad'], gestopt: ['■', ''], overgeslagen: ['–', ''], wacht: ['⏸', 'ghwarn']
};
function ghSoortTekst(s){
  return { bezig: t('bezig'), gelukt: t('gelukt'), mislukt: t('mislukt'), gestopt: t('gestopt'), overgeslagen: t('overgeslagen'), wacht: t('wacht op goedkeuring') }[s] || s;
}
function ghEnter(){ ghMeldingRender(); ghLaad(false); }
function ghLaad(vers){
  if (gh.laden || typeof Android.githubStatus !== 'function') return;
  gh.laden = true;
  $('#gh-fout').hidden = true;
  if (!gh.data) { $('#gh-samen').textContent = t('Stand ophalen bij GitHub…'); $('#gh-samen').classList.add('wacht'); }
  $('#gh-ververs').disabled = true;
  Android.githubStatus(!!vers);
}
window.onGithub = function(r){
  gh.laden = false;
  $('#gh-ververs').disabled = false;
  $('#gh-samen').classList.remove('wacht');
  if (!r || r.error) {
    $('#gh-fout').hidden = false;
    $('#gh-fout').textContent = (r && r.error) || t('Ophalen bij GitHub lukte niet');
    if (!gh.data) $('#gh-samen').textContent = t('Geen stand');
    return;
  }
  gh.data = r;
  ghRender();
};
function ghWanneer(ms){ return ms ? fmtDT(ms, { day: 'numeric', month: 'short', hour: '2-digit', minute: '2-digit' }) : ''; }
function ghRender(){
  const d = gh.data;
  if (!d) return;
  const s = d.samen || {};
  const delen = [];
  if (s.bezig) delen.push(tn(s.bezig, 'Eén run bezig', '{n} runs bezig'));
  if (s.mislukt) delen.push(tn(s.mislukt, 'Eén taak mislukt', '{n} taken mislukt'));
  if (!s.mislukt) delen.push(t('Alles gelukt'));
  $('#gh-kop').textContent = s.mislukt ? t('Er mislukte iets') : s.bezig ? t('Er loopt iets') : t('Alles in orde');
  $('#gh-samen').textContent = delen.join(' · ') + ' · ' + t('stand van {wanneer}', { wanneer: ghWanneer(d.t) });
  // Per taak: de laatste run op main (daar gaat het om), anders de laatste run
  const taken = d.taken || [];
  $('#gh-taken-kaart').hidden = !taken.length;
  $('#gh-taken').innerHTML = taken.map((x, i) => {
    const st = x.stand || {}, [teken, cls] = GH_SOORT[st.soort] || ['?', ''];
    // Loopt er intussen een run op een andere tak, dan staat die erachter (de tak is tekst van GitHub: niet vertalen)
    const later = x.laatste && x.laatste.id !== st.id ? ' · ' + esc(t('nu op {tak}: {stand}', { tak: '\u0001', stand: ghSoortTekst(x.laatste.soort) })).replace('\u0001', '<span translate="no">' + esc(x.laatste.tak || '') + '</span>') : '';
    return '<button class="strow" data-gh-taak="' + i + '"><span class="stic ' + cls + '" aria-hidden="true">' + teken + '</span><span class="sttxt"><b translate="no">' + esc(x.naam) + '</b><small>' +
      esc(ghSoortTekst(st.soort)) + ' · ' + esc(ghWanneer(st.eind || st.start)) + ' · <span translate="no">' + esc(st.tak || '') + '</span>' + later + '</small></span>' +
      '<span class="ui-icon stchev" data-icon="chevron-right" aria-hidden="true"></span></button>';
  }).join('');
  // Recente runs (eerst 12, met Meer tonen)
  const runs = d.runs || [], toon = gh.meer ? runs : runs.slice(0, 12);
  $('#gh-runs-kaart').hidden = !runs.length;
  // Welke periode de runs beslaan (de nieuwste 100): een taak die zelden draait staat bij Per taak met zijn laatste run
  $('#gh-sinds').textContent = d.sinds ? t('De nieuwste runs, sinds {wanneer}', { wanneer: ghWanneer(d.sinds) }) : '';
  $('#gh-runs').innerHTML = toon.map((x, i) => {
    const [teken, cls] = GH_SOORT[x.soort] || ['?', ''];
    return '<button class="ghrun" data-gh-run="' + i + '"><span class="ghteken ' + cls + '" aria-hidden="true">' + teken + '</span><span class="ghtxt"><b translate="no">' + esc(x.naam) + ' #' + esc(String(x.nr || '')) + '</b>' +
      '<small><span class="sr-only">' + esc(ghSoortTekst(x.soort)) + ' · </span><span translate="no">' + esc(x.titel || '') + '</span></small><small>' + esc(ghWanneer(x.start)) + ' · <span translate="no">' + esc(x.tak || '') + '</span></small></span></button>';
  }).join('');
  $('#gh-runs-meer-w').hidden = gh.meer || runs.length <= 12;
  // Open punten (issues) met hun labels
  const punten = d.punten || [];
  $('#gh-punten-kaart').hidden = !punten.length && !d.prs;
  $('#gh-punten').innerHTML = punten.map((x, i) => '<button class="ghrun" data-gh-punt="' + i + '"><span class="ghteken" aria-hidden="true">#</span><span class="ghtxt"><b translate="no">' + esc(x.titel) + '</b><small>#' + esc(String(x.nr)) +
    ((x.labels || []).length ? ' · <span translate="no">' + esc(x.labels.join(', ')) + '</span>' : '') + '</small></span></button>').join('') +
    (d.prs ? '<p class="note">' + esc(tn(d.prs, 'Eén pull request staat open.', '{n} pull requests staan open.')) + '</p>' : '');
  ghKoppel();
}
/* Klikken via de gegevens (niet via een adres in het scherm) */
function ghKoppel(){
  const d = gh.data || {};
  document.querySelectorAll('#gh-taken [data-gh-taak]').forEach(b => b.addEventListener('click', () => { const x = (d.taken || [])[+b.dataset.ghTaak]; ghOpen(x && x.stand && x.stand.url); }));
  document.querySelectorAll('#gh-runs [data-gh-run]').forEach(b => b.addEventListener('click', () => { const x = (d.runs || [])[+b.dataset.ghRun]; ghOpen(x && x.url); }));
  document.querySelectorAll('#gh-punten [data-gh-punt]').forEach(b => b.addEventListener('click', () => { const x = (d.punten || [])[+b.dataset.ghPunt]; ghOpen(x && x.url); }));
}
function ghOpen(url){
  const repo = (gh.data && gh.data.repo) || 'https://github.com/renevanes/renes-tools/';
  if (typeof url !== 'string' || !url.startsWith(repo)) { toast(t('Dit is niet te openen')); return; }
  Android.openUrl(url);
}
function ghMeer(){ gh.meer = true; ghRender(); }

/* Melding als iets mislukt: via Signalen (bron "Publiceren en tests"), met de keuzes van Signalen (direct, overzicht, stille uren) */
function ghMeldingBron(){
  if (typeof snLaad !== 'function' || typeof snOud === 'function' && snOud()) return null;
  snLaad();
  return snBronnen().find(b => b.soort === 'github') || null;
}
function ghMeldingRender(){
  const kaart = $('#gh-melding-kaart');
  if (!kaart) return;
  const kan = typeof snBronNieuw === 'function' && !(typeof snOud === 'function' && snOud());
  kaart.hidden = !kan;
  if (!kan) return;
  const b = ghMeldingBron();
  $('#gh-melding-txt').textContent = b
    ? (b.aan === false ? t('Staat in Signalen, maar uit. Zet hem daar aan om een melding te krijgen.') : t('Staat aan in Signalen: een run op main die mislukt, geeft een melding (zoals je bij die bron instelde).'))
    : t('Via Signalen: Tools kijkt dan elk uur bij GitHub of een run op main mislukte. Hoe en wanneer je de melding krijgt, stel je daar in.');
  $('#gh-melding').textContent = b ? t('Instellen in Signalen') : t('Melding aanzetten');
}
function ghMelding(){
  const b = ghMeldingBron();
  if (b) { snBronOpen(b.id); return; }
  snBronNieuw('github');
}
