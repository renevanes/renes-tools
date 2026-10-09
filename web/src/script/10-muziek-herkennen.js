/* ---------- Muziek herkennen ---------- */
let muPoll = null, muHist = [], muLastAt = 0;
function enterMusic(){
  const st = rdJson(Android.musicState(), {});
  $('#mu-setup').style.display = st.hasToken ? 'none' : 'block';
  $('#mu-main').style.display = st.hasToken ? 'block' : 'none';
  muHist = rdJson(Android.musicHistory(), []);
  muRenderHistory();
  $('#mu-foot').innerHTML = (st.used ? st.used + (st.used === 1 ? ' herkenning' : ' herkenningen') + ' gedaan via deze app (AudD: 300 gratis). ' : '') +
    'Alleen een fragment van 10 seconden gaat naar AudD. Op je telefoon wordt alleen het resultaat bewaard, niet de opname. ' +
    '<a href="#" onclick="muChangeToken();return false">Sleutel wijzigen</a>';
  muLastAt = 0;
  muPollOnce(); stopMuPoll(); muPoll = setInterval(muPollOnce, 300);
}
function stopMuPoll(){ if (muPoll) { clearInterval(muPoll); muPoll = null; } }
window.onMusicChanged = function(){ if (current !== 'music') return; if (Android.musicHasMic()) muToggle(); else toast('Zonder microfoon kan alleen herkennen vanaf de radio. Toegang geven kan bij de app-instellingen.'); };
function muSaveToken(){
  const t = $('#mu-token').value.trim();
  if (t.length < 10) { toast('Dat lijkt geen geldige sleutel'); return; }
  Android.musicSetToken(t); $('#mu-token').value = ''; enterMusic();
}
function muChangeToken(){ askInput('AudD-sleutel', 'Huidige sleutel: ' + (Android.musicTokenHint() || 'geen'), '', 'Opslaan', v => { if (v.trim()) { Android.musicSetToken(v); enterMusic(); } }); }
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
  $('#mu-btn').classList.toggle('busy', busy);
  $('#mu-state').textContent = st.state === 'recording' ? 'Luisteren…' : st.state === 'sending' ? 'Zoeken…' : 'Tik om te herkennen';
  $('#mu-sub').textContent = st.state === 'recording' ? 'Tik nogmaals om te stoppen' : st.state === 'sending' ? 'Even geduld' : 'Houd de telefoon bij de muziek';
  if ((st.state === 'done' || st.state === 'error') && st.at !== muLastAt) {
    muLastAt = st.at;
    if (st.state === 'error') $('#mu-result').innerHTML = '<div class="result on error">' + esc(st.error || 'Herkennen mislukt') + '</div>';
    else if (st.result && st.result.title) { $('#mu-result').innerHTML = muCard(st.result); muHist = rdJson(Android.musicHistory(), []); muRenderHistory(); }
    else $('#mu-result').innerHTML = '<div class="result on other">Geen nummer gevonden. Probeer het dichter bij de muziek of tijdens het refrein.</div>';
  }
}
function muCard(r){
  const q = encodeURIComponent(r.artist + ' ' + r.title);
  const links = [];
  if (r.spotify) links.push(['Spotify', r.spotify]);
  if (r.apple) links.push(['Apple Music', r.apple]);
  links.push(['YouTube', 'https://music.youtube.com/search?q=' + q]);
  if (r.link) links.push(['Alle diensten', r.link]);
  return '<div class="card mucard">' + (r.art && /^https:/.test(r.art) ? '<img src="' + esc(r.art) + '" alt="">' : '<div class=noart>♫</div>') +
    '<div class=mumeta><b>' + esc(r.title) + '</b><span>' + esc(r.artist) + '</span>' +
    '<small>' + esc([r.album, (r.date || '').slice(0, 4)].filter(Boolean).join(' · ')) + '</small></div>' +
    '<div class=mulinks>' + links.map(l => '<button class="mini" data-u="' + esc(l[1]) + '" onclick="Android.openUrl(this.dataset.u)">' + esc(l[0]) + '</button>').join('') + '</div></div>';
}
function muRenderHistory(){
  const q = ($('#mu-search').value || '').trim().toLowerCase();
  const l = muHist.filter(r => !q || (r.title + ' ' + r.artist + ' ' + (r.album || '')).toLowerCase().includes(q));
  $('#mu-hist').innerHTML = l.map(r =>
    '<button data-t="' + (+r.t || 0) + '" onclick="muOpen(+this.dataset.t)"><span class=r1><b>' + esc(r.title) + '</b><time>' + esc(fmtD(r.t)) + '</time></span>' +
    '<span class=r2>' + esc(r.artist) + ((r.source || '').startsWith('radio:') ? ' · ' + esc(r.source.slice(6)) : '') + '</span></button>').join('') ||
    '<p class=note style="padding:14px">' + (q ? 'Niets gevonden.' : 'Herkende nummers verschijnen hier.') + '</p>';
}
function muOpen(t){
  const r = muHist.find(x => x.t === t); if (!r) return;
  openSheet(r.title, r.artist + ' · ' + fmtD(r.t), [
    ['Tonen', () => { $('#mu-result').innerHTML = muCard(r); window.scrollTo({ top: 0, behavior: 'smooth' }); }],
    ['Zoeken op YouTube', () => Android.openUrl('https://music.youtube.com/search?q=' + encodeURIComponent(r.artist + ' ' + r.title))],
    ['Uit de lijst verwijderen', () => { Android.musicDelete(String(r.t)); muHist = rdJson(Android.musicHistory(), []); muRenderHistory(); }],
  ]);
}

