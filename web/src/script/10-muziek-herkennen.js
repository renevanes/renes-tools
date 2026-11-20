/* ---------- Muziek herkennen ---------- */
let muPoll = null, muHist = [], muLastAt = 0, muUsed = 0;
const MU_ZOEK_VANAF = 6; // het zoekveld pas vanaf zoveel herkende nummers (of als er al iets in staat, bijv. vanuit Zoeken)
function enterMusic(){
  const st = rdJson(Android.musicState(), {});
  uiSetup($('#mu-setup'), st.hasToken);
  $('#mu-main').hidden = !st.hasToken;
  $('#mu-menu').hidden = !st.hasToken;
  $('#mu-token-msg').textContent = '';
  muUsed = +st.used || 0;
  muHist = rdJson(Android.musicHistory(), []);
  muRenderHistory();
  muLastAt = 0;
  muPollOnce(); stopMuPoll(); muPoll = setInterval(muPollOnce, 300);
}
function stopMuPoll(){ if (muPoll) { clearInterval(muPoll); muPoll = null; } }
window.onMusicChanged = function(){ if (current !== 'music') return; if (Android.musicHasMic()) muToggle(); else toast('Zonder microfoon kan alleen herkennen vanaf de radio. Toegang geven kan bij de app-instellingen.'); };
function muAccount(){ Android.openUrl('https://dashboard.audd.io/'); }
function muSaveToken(){
  const tt = $('#mu-token').value.trim();
  if (tt.length < 10) { $('#mu-token-msg').textContent = t('Dat lijkt geen geldige sleutel'); $('#mu-token').focus(); return; }
  Android.musicSetToken(tt); $('#mu-token').value = ''; enterMusic();
}
function muChangeToken(){ askInput('AudD-sleutel', t('Huidige sleutel: {sleutel}', { sleutel: Android.musicTokenHint() || t('geen') }), '', 'Opslaan', v => { if (v.trim()) { Android.musicSetToken(v); enterMusic(); } }); }
/* Menu ⋯: zelden nodig (sleutel wijzigen, het account bij AudD), met het gebruik als ondertitel */
function muMenu(){
  openSheet(t('Muziek herkennen'), (muUsed ? tn(muUsed, '{n} herkenning gedaan via deze app (AudD: 300 gratis).', '{n} herkenningen gedaan via deze app (AudD: 300 gratis).') + ' ' : '') +
    t('Alleen een fragment van 10 seconden gaat naar AudD. Op je telefoon wordt alleen het resultaat bewaard, niet de opname.'), [
    [t('AudD-sleutel wijzigen'), muChangeToken, 'edit'],
    [t('Je account bij AudD'), muAccount, 'external'],
  ]);
}
function muToggle(){
  const st = rdJson(Android.musicState(), {});
  if (st.state === 'recording' || st.state === 'sending') { Android.musicCancel(); muPollOnce(); return; }
  if (!Android.musicHasMic()) { Android.musicRequestMic(); return; }
  const e = Android.musicStart(); if (e) { toast(e); return; }
  $('#mu-result').innerHTML = ''; muPollOnce();
}
function muPollOnce(){
  const st = rdJson(Android.musicState(), {});
  const busy = st.state === 'recording' || st.state === 'sending';
  const btn = $('#mu-btn');
  btn.classList.toggle('busy', busy);
  const label = busy ? t('Stoppen') : t('Herkennen');
  if (btn.getAttribute('aria-label') !== label) btn.setAttribute('aria-label', label);
  const stateTxt = st.state === 'recording' ? t('Luisteren…') : t('Tik om te herkennen');
  const stEl = $('#mu-state');
  // alleen bij een verandering: de stand is een status (TalkBack leest elke nieuwe tekst voor)
  if (st.state === 'sending') { if (stEl.textContent !== t('Zoeken…')) uiWachtTekst(stEl, t('Zoeken…')); }
  else if (stEl.textContent !== stateTxt) stEl.textContent = stateTxt;
  const sub = st.state === 'recording' ? t('Tik nogmaals om te stoppen') : st.state === 'sending' ? t('Even geduld') : t('Houd de telefoon bij de muziek');
  if ($('#mu-sub').textContent !== sub) $('#mu-sub').textContent = sub;
  if ((st.state === 'done' || st.state === 'error') && st.at !== muLastAt) {
    muLastAt = st.at;
    if (typeof st.used === 'number') muUsed = st.used;
    if (st.state === 'error') $('#mu-result').innerHTML = '<div class="result on error">' + esc(st.error || t('Herkennen mislukt')) + '</div>';
    else if (st.result && st.result.title) { $('#mu-result').innerHTML = muCard(st.result); muHist = rdJson(Android.musicHistory(), []); muRenderHistory(); }
    else $('#mu-result').innerHTML = '<div class="result on other">' + esc(t('Geen nummer gevonden. Probeer het dichter bij de muziek of tijdens het refrein.')) + '</div>';
  }
}
/* Adressen uit het antwoord van AudD alleen als https */
function muUrl(u){ return /^https:\/\/[^\s"'<>]+$/i.test(String(u || '')) ? String(u) : ''; }
function muCard(r){
  const q = encodeURIComponent(r.artist + ' ' + r.title);
  const links = [];
  if (muUrl(r.spotify)) links.push(['Spotify', r.spotify]);
  if (muUrl(r.apple)) links.push(['Apple Music', r.apple]);
  links.push(['YouTube', 'https://music.youtube.com/search?q=' + q]);
  if (muUrl(r.link)) links.push([t('Alle diensten'), r.link]);
  return '<div class="card mucard">' + (muUrl(r.art) ? '<img src="' + esc(r.art) + '" alt="">' : '<div class=noart aria-hidden="true">♫</div>') +
    '<div class=mumeta translate="no"><b>' + esc(r.title) + '</b><span>' + esc(r.artist) + '</span>' +
    '<small>' + esc([r.album, (r.date || '').slice(0, 4)].filter(Boolean).join(' · ')) + '</small></div>' +
    '<div class=mulinks>' + links.map(l => '<button class="mini" data-u="' + esc(l[1]) + '" onclick="Android.openUrl(this.dataset.u)" aria-label="' + esc(t('Openen in {dienst}', { dienst: l[0] })) + '">' + esc(l[0]) + '</button>').join('') + '</div></div>';
}
function muRenderHistory(){
  const zoek = $('#mu-search'), q = (zoek.value || '').trim().toLowerCase();
  zoek.hidden = muHist.length < MU_ZOEK_VANAF && !q;
  const kop = $('#mu-hist-h');
  kop.hidden = !muHist.length;
  kop.textContent = t('Herkende nummers ({n})', { n: muHist.length });
  const l = muHist.filter(r => !q || (r.title + ' ' + r.artist + ' ' + (r.album || '')).toLowerCase().includes(q));
  $('#mu-hist').classList.toggle('card', !!l.length);
  $('#mu-hist').innerHTML = l.map(r =>
    '<button data-t="' + (+r.t || 0) + '" onclick="muOpen(+this.dataset.t)"><span class=r1><b translate="no">' + esc(r.title) + '</b><time>' + esc(fmtD(r.t)) + '</time></span>' +
    '<span class=r2 translate="no">' + esc(r.artist) + ((r.source || '').startsWith('radio:') ? ' · ' + esc(r.source.slice(6)) : '') + '</span></button>').join('') ||
    (q ? '<p class=note style="padding:14px">' + esc(t('Niets gevonden.')) + '</p>' : uiEmpty('music', t('Herkende nummers verschijnen hier.')));
}
function muOpen(tt){
  const r = muHist.find(x => x.t === tt); if (!r) return;
  openSheet(r.title, r.artist + ' · ' + fmtD(r.t), [
    [t('Tonen'), () => { $('#mu-result').innerHTML = muCard(r); window.scrollTo({ top: 0, behavior: 'smooth' }); }],
    [t('Zoeken op YouTube'), () => Android.openUrl('https://music.youtube.com/search?q=' + encodeURIComponent(r.artist + ' ' + r.title))],
    [t('Uit de lijst verwijderen'), () => { Android.musicDelete(String(r.t)); muHist = rdJson(Android.musicHistory(), []); muRenderHistory(); }],
  ]);
}
