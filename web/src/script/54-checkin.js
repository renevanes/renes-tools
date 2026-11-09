/* ---------- Check-in: per dag, week of maand kort je score, met één of twee tikken ----------
 * Java (CheckinStore/Checkin) rekent de periodes uit: wat open staat, ingevuld of gemist is, en de reeks. Hier de
 * schermen: de lijst met vragen (met de antwoordknoppen erbij), het antwoordscherm (ook vanuit de melding) en de tegel. */
let ciSt = {}, ciShowArch = false;
const CI_DOW = ['', 'ma', 'di', 'wo', 'do', 'vr', 'za', 'zo'];
const CI_DOW_L = ['', 'maandag', 'dinsdag', 'woensdag', 'donderdag', 'vrijdag', 'zaterdag', 'zondag'];
const CI_MON = ['januari', 'februari', 'maart', 'april', 'mei', 'juni', 'juli', 'augustus', 'september', 'oktober', 'november', 'december'];

function ciLoad(){ ciSt = rdJson(Android.checkinState(), {}); return ciSt; }
function ciQ(id){ return (ciSt.questions || []).find(q => q.id === id); }
function ciActive(q){ return !!(q && q.on && !q.archived); }
function ciOpts(q){ return (q && q.options) || []; }
/* Datums als "yyyy-MM-dd". Een tijdstip (antwoord, slot) hoort bij een dag in de app-zone: rgDayKey(t). ciDayKey en
 * ciDate rekenen met kalenderdatums (Date van 12:00 telefoontijd, nooit om te tonen); vandaag is ciToday(). */
function ciPad(n){ return String(n).padStart(2, '0'); }
function ciDayKey(d){ return rgCalKey(d); }
/* Vandaag (in de app-zone) als kalenderdatum */
function ciToday(){ return rgCalDate(rgToday()); }
function ciDate(key){ const m = /^(\d{4})-(\d{2})-(\d{2})/.exec(key || ''); return m ? new Date(+m[1], +m[2] - 1, +m[3], 12) : null; }
function ciTime(tt){ return fmtTime(tt, { hour: '2-digit', minute: '2-digit' }); }
function ciShort(d){ return t(CI_DOW[(d.getDay() + 6) % 7 + 1]) + ' ' + d.getDate() + ' ' + t(CI_MON[d.getMonth()]).slice(0, 3); }
/** Eerste dag (maandag) van een ISO-week "2026-W44". Zuiver. */
function ciWeekStart(key){
  const m = /^(\d{4})-W(\d{2})$/.exec(key || ''); if (!m) return null;
  const j = new Date(+m[1], 0, 4, 12), dow = (j.getDay() + 6) % 7;
  j.setDate(j.getDate() - dow + (+m[2] - 1) * 7); return j;
}
/** De periode in woorden: "vandaag", "donderdag 29 oktober", "week 44 (26 okt – 1 nov)", "oktober 2026", "vandaag 21:00". Zuiver. */
function ciKeyLabel(key, now){
  const today = now ? rgDayKey(now) : rgToday(), yr = +today.slice(0, 4); // vandaag in de app-zone
  if (/^\d{4}-W\d{2}$/.test(key)) { const a = ciWeekStart(key), b = new Date(a); b.setDate(b.getDate() + 6); return t('week {n}', { n: +key.slice(6) }) + ' (' + a.getDate() + ' ' + t(CI_MON[a.getMonth()]).slice(0, 3) + ' – ' + b.getDate() + ' ' + t(CI_MON[b.getMonth()]).slice(0, 3) + ')'; }
  if (/^\d{4}-\d{2}$/.test(key)) return t(CI_MON[+key.slice(5) - 1]) + ' ' + key.slice(0, 4);
  const d = ciDate(key); if (!d) return key;
  const day = key.slice(0, 10) === today ? t('vandaag') : key.slice(0, 10) === rgDayAdd(today, -1) ? t('gisteren') : t(CI_DOW_L[(d.getDay() + 6) % 7 + 1]) + ' ' + d.getDate() + ' ' + t(CI_MON[d.getMonth()]) + (d.getFullYear() !== yr ? ' ' + d.getFullYear() : '');
  return key.length === 16 ? day + ' ' + key.slice(11) : day;
}
/** Hoe vaak, in woorden: "Elke dag om 21:00", "Werkdagen om 17:30" … Zuiver. */
function ciRhythmText(q){
  const hm = x => typeof fmtHm === 'function' ? fmtHm(x) : x; // 12- of 24-uurs (Regio en taal)
  const tt = (q.times || []).slice().sort().map(hm), at = ' ' + t('om {tijd}', { tijd: tt[0] || '' });
  const days = (q.days || []).slice().sort();
  const dayList = days.length === 7 ? t('Elke dag') : days.join() === '1,2,3,4,5' ? t('Werkdagen') : t('Op {dagen}', { dagen: days.map(d => t(CI_DOW[d])).join(', ') });
  let s;
  switch (q.rhythm) {
    case 'daily': s = t('Elke dag') + at; break;
    case 'workdays': s = t('Werkdagen') + at; break;
    case 'weekdays': s = dayList + at; break;
    case 'weekly': s = t('Elke {dag}', { dag: t(CI_DOW_L[q.wday || 5]) }) + at; break;
    case 'monthly': s = t('Elke maand op de {n}e', { n: q.dom || 1 }) + ((q.dom || 1) > 28 ? ' ' + t('(of de laatste dag)') : '') + at; break;
    case 'multi': s = dayList + ' ' + t('om {tijd}', { tijd: tt.length > 1 ? t('{a} en {b}', { a: tt.slice(0, -1).join(', '), b: tt[tt.length - 1] }) : tt[0] || '' }); break;
    default: s = '';
  }
  if (q.quietWeekend) s += ' · ' + t('stil in het weekend');
  if (q.pauseUntil && q.pauseUntil >= rgToday()) { const p = ciDate(q.pauseUntil); s += ' · ' + t('pauze t/m {datum}', { datum: ciShort(p) }); }
  return s;
}
function ciAnsText(a){ return ((a.e || '') + ' ' + (a.l || '')).trim(); }
function ciOptText(o){ return ((o.emoji || '') + ' ' + (o.label || '')).trim(); }
function ciHasExtras(q){ return !!(q.note || q.voice || (q.labels && (q.labelList || []).length)); }
function ciDue(q){ const c = q.cur; if (q.tpl && !q.on) return false; return c && (c.status === 'open' || c.status === 'upcoming' || (c.status === 'answered' && q.perPeriod === 'many')); }

/** Wat er bij de vraag staat. */
function ciStatus(q){
  if (q.archived) return [t('Gearchiveerd') + ' · ' + tn(q.total || 0, '{n} antwoord', '{n} antwoorden'), ''];
  const c = q.cur, nxt = q.next ? t('Volgende melding: {wanneer}', { wanneer: ciShort(new Date(q.next)) + ' ' + ciTime(q.next) }) : '';
  if (c && c.status === 'answered') { const a = c.answers[c.answers.length - 1]; return ['✓ ' + ciKeyLabel(c.key).replace(/^./, x => x.toUpperCase()) + ': ' + ciAnsText(a) + (c.answers.length > 1 ? ' (' + c.answers.length + '×)' : ''), 'ok']; }
  if (!q.on) return [q.tpl ? 'Voorstel: staat uit. Zet hem aan als je hem wilt gebruiken.' : 'Staat uit: geen meldingen', 'warn'];
  if (c && c.status === 'open') return [t('Nog niet ingevuld ({wanneer})', { wanneer: ciKeyLabel(c.key) }), ''];
  if (c && c.status === 'upcoming') return [t('Vandaag om {tijd}', { tijd: ciTime(c.at) }) + (c.quiet ? ' ' + t('(stil: geen melding)') : '') + ' · ' + t('je kunt nu al antwoorden'), ''];
  return [nxt || 'Geen melding gepland', ''];
}
function ciOptBtns(q, key, sel, big){
  const n = ciOpts(q).length;
  return '<div class="' + (big ? 'ci-big' : 'ci-opts') + (n >= 7 ? ' n10' : '') + '">' + ciOpts(q).map((o, i) => '<button class="ci-opt' + (o.emoji ? '' : ' noe') + '"' + (sel === i ? ' aria-pressed="true"' : ' aria-pressed="false"') +
    ' data-q="' + esc(q.id) + '" data-k="' + esc(key) + '" data-i="' + i + '" onclick="' + (big ? 'ciAnsPick(+this.dataset.i)' : 'ciPick(this.dataset.q, this.dataset.k, +this.dataset.i)') + '"' +
    ' aria-label="' + esc(q.text + ': ' + ciOptText(o)) + '">' + (o.emoji ? '<span class="e" aria-hidden="true">' + esc(o.emoji) + '</span>' + (o.label ? '<span>' + esc(o.label) + '</span>' : '') : esc(o.label)) + '</button>').join('') + '</div>';
}
function ciCard(q){
  const [st, cls] = ciStatus(q), c = q.cur;
  let h = '<div class="card ci-card" data-c="' + esc(q.color || 'primary') + '" data-q="' + esc(q.id) + '"><div class="ci-head"><span class="ci-em" aria-hidden="true">' + esc(q.emoji || '•') + '</span>' +
    '<button class="ci-tt" data-q="' + esc(q.id) + '" onclick="ciOpen(this.dataset.q)" aria-label="' + esc(q.text + ': ' + t('terugkijken')) + '"><b>' + esc(q.text) + (q.tpl && !q.on ? '<span class="ci-badge">Voorstel</span>' : '') + '</b><small>' + esc(ciRhythmText(q)) + '</small></button>' +
    '<button class="iconbtn" data-q="' + esc(q.id) + '" onclick="ciMenu(this.dataset.q)" aria-label="' + esc(q.text + ': ' + t('meer')) + '">' + uiIcon('more') + '</button></div>' +
    '<p class="ci-st ' + cls + '">' + esc(st) + '</p>';
  if (!q.archived && ciDue(q)) h += ciOptBtns(q, c.key, -1, false);
  const acts = [];
  if (!q.archived && !q.on) acts.push('<button class="mini tonal" data-q="' + esc(q.id) + '" onclick="ciSetOn(this.dataset.q, true)" aria-label="' + esc(q.text + ': ' + t('aanzetten')) + '">Aanzetten</button>', '<button class="mini" data-q="' + esc(q.id) + '" onclick="ciEdit(this.dataset.q)" aria-label="' + esc(q.text + ': ' + t('bewerken')) + '">Bewerken</button>');
  if (c && c.status === 'answered' && c.answers.length && (q.editable !== false || Date.now() - c.answers[c.answers.length - 1].t < 15 * 60000)) {
    const a = c.answers[c.answers.length - 1];
    acts.push('<button class="linkish" data-q="' + esc(q.id) + '" data-k="' + esc(c.key) + '" data-a="' + esc(a.id) + '" onclick="ciAsk(this.dataset.q, this.dataset.k, {aid: this.dataset.a})" aria-label="' + esc(q.text + ': ' + t(ciHasExtras(q) ? 'aanvullen of wijzigen' : 'wijzigen')) + '">' + (ciHasExtras(q) ? 'Aanvullen of wijzigen' : 'Wijzigen') + '</button>');
  }
  if (!q.archived && q.backfill !== false && q.missed7 > 0) acts.push('<button class="linkish" data-q="' + esc(q.id) + '" onclick="ciOpen(this.dataset.q)" aria-label="' + esc(q.text + ': ' + t('achteraf invullen')) + '">' + t('{n}× gemist deze week · achteraf invullen', { n: q.missed7 }) + '</button>');
  if (q.archived) acts.push('<button class="mini" data-q="' + esc(q.id) + '" onclick="ciOpen(this.dataset.q)" aria-label="' + esc(q.text + ': ' + t('terugkijken')) + '">Terugkijken</button>', '<button class="mini" data-q="' + esc(q.id) + '" onclick="ciArchive(this.dataset.q, false)" aria-label="' + esc(q.text + ': ' + t('terugzetten')) + '">Terugzetten</button>');
  if (acts.length) h += '<div class="ci-acts">' + acts.join('') + '</div>';
  return h + '</div>';
}
function enterCheckin(){ ciLoad(); ciRender(); }
function ciRender(){
  const qs = ciSt.questions || [], live = qs.filter(q => !q.archived), arch = qs.filter(q => q.archived);
  slHtml($('#ci-list'), live.map(ciCard).join('') || '<div class="card">' + uiEmpty('smile', t('Nog geen vragen. Tik op Nieuwe vraag.')) + '</div>');
  slHtml($('#ci-arch'), arch.length ? '<button class="ci-archh" aria-expanded="' + ciShowArch + '" onclick="ciShowArch=!ciShowArch;ciRender()">' + (ciShowArch ? '▾' : '▸') + ' Gearchiveerd (' + arch.length + ')</button>' + (ciShowArch ? arch.map(ciCard).join('') : '') : '');
  const pm = $('#ci-perm'), need = live.some(ciActive);
  if (need && ciSt.notifOk === false) { pm.style.display = ''; slHtml(pm, '<p>Sta meldingen toe, anders komt de vraag niet op het moment dat je koos.</p><button class="big tonal" onclick="Android.permRequest(\'notif\')">Meldingen toestaan</button>'); }
  else if (need && ciSt.muted) { pm.style.display = ''; slHtml(pm, '<p>Alle meldingen van de app staan uit (Instellingen → Meldingen). Je kunt hier wel gewoon antwoorden.</p>'); }
  else pm.style.display = 'none';
}
window.onCheckin = function(){
  if (current === 'checkin') enterCheckin();
  else if (current === 'checkinq' && typeof ciqLoad === 'function') ciqLoad();
  refreshCheckinTile();
};
/* Tegel op het startscherm: "Nog 1 vraag voor vandaag" of "✓ Alles ingevuld" */
function ciTileText(st){
  const qs = (st.questions || []).filter(ciActive);
  if (!qs.length) return 'Even kort je score bijhouden';
  const open = qs.filter(q => q.cur && q.cur.status === 'open').length;
  if (open) return tn(open, 'Nog {n} vraag voor vandaag', 'Nog {n} vragen voor vandaag');
  const later = qs.filter(q => q.cur && q.cur.status === 'upcoming' && !q.cur.quiet).map(q => q.cur.at).sort((a, b) => a - b)[0];
  if (later) return t('Straks om {tijd}', { tijd: ciTime(later) });
  return '✓ Alles ingevuld';
}
function refreshCheckinTile(){ const el = $('#tile-checkin-sub'); if (!el || typeof Android.checkinState !== 'function') return; el.textContent = ciTileText(ciLoad()); }

/* ---------- antwoorden ---------- */
function ciPick(qid, key, i){
  const q = ciQ(qid); if (!q) return;
  ciSend({ q: qid, p: key, o: i }, r => {
    toast(t('✓ Bewaard: {wat}', { wat: ciOptText(ciOpts(q)[i] || {}) }) + (r.late ? ' ' + t('(achteraf)') : ''));
    ciAfter();
  });
}
/** Antwoord naar de app; vraagt eerst om te vervangen als er al een antwoord staat. */
function ciSend(req, done){
  const r = rdJson(Android.checkinAnswer(JSON.stringify(req)), {});
  if (r.need === 'replace') {
    const q = ciQ(req.q), o = ciOpts(q)[req.o] || {};
    askConfirm(t('Antwoord vervangen?'), t('Er staat al: {oud}. Vervangen door {nieuw}?', { oud: r.old, nieuw: ciOptText(o) }), t('Vervangen'), () => ciSend(Object.assign({}, req, { replace: true }), done));
    return;
  }
  if (r.error) { toast(r.error); return; }
  done(r);
}
function ciAfter(){ ciLoad(); if (current === 'checkin') ciRender(); if (current === 'checkinq' && typeof ciqLoad === 'function') ciqLoad(); refreshCheckinTile(); }

/* Het antwoordscherm: grote knoppen; met notitie, inspreken of labels blijft hij na het kiezen open om aan te vullen. */
let ciA = null;
function ciAsk(qid, key, opt){
  opt = opt || {};
  if (!ciQ(qid)) ciLoad();
  const q = ciQ(qid); if (!q) { toast('Deze vraag bestaat niet meer'); return; }
  if (q.archived) { toast('Deze vraag is gearchiveerd'); return; }
  const c = q.cur && (!key || q.cur.key === key) ? q.cur : null;
  ciA = { q: qid, key: key || (c ? c.key : ''), aid: opt.aid || '', sel: -1, note: '', labels: [], voice: false, late: !!opt.late, from: document.activeElement };
  if (!ciA.key) { toast('Er staat nu geen vraag open'); ciA = null; return; }
  if (opt.aid) { // aanvullen of wijzigen
    const a = opt.ans || (c && c.answers.find(x => x.id === opt.aid));
    if (a) { ciA.sel = a.o; ciA.note = a.note || ''; ciA.labels = (a.labels || []).slice(); ciA.late = !!a.late; }
  }
  $('#ci-ans-t').textContent = (q.emoji ? q.emoji + ' ' : '') + q.text;
  $('#ci-ans-p').textContent = ciKeyLabel(ciA.key).replace(/^./, x => x.toUpperCase()) + (ciA.late ? ' · ' + t('achteraf') : '');
  $('#ci-ans').dataset.c = q.color || 'primary';
  ciAnsRender();
  $('#ci-ans').classList.add('on');
  setTimeout(() => { const b = $('#ci-ans-opts .ci-opt'); if (b) b.focus(); }, 60);
}
function ciAnsRender(){
  const q = ciQ(ciA.q); if (!q) return;
  slHtml($('#ci-ans-opts'), ciOptBtns(q, ciA.key, ciA.sel, true).replace(/^<div class="[^"]*">/, '').replace(/<\/div>$/, ''));
  $('#ci-ans-opts').className = 'ci-big' + (ciOpts(q).length >= 7 ? ' n10' : '');
  const ex = ciHasExtras(q);
  $('#ci-ans-st').textContent = ciA.aid ? (ex ? '✓ Bewaard. Vul aan als je wilt.' : '✓ Bewaard') : ex ? 'Kies je antwoord; daarna kun je nog iets toevoegen.' : 'Eén tik is genoeg.';
  let x = '';
  if (ex) {
    if (q.labels && (q.labelList || []).length) x += '<div class="chips sm" role="group" aria-label="Labels">' + q.labelList.map(l => { const on = ciA.labels.includes(l);
      return '<button class="' + (on ? 'on' : '') + '" aria-pressed="' + on + '" data-l="' + esc(l) + '" onclick="ciAnsLabel(this.dataset.l)">' + esc(l) + '</button>'; }).join('') + '</div>';
    if (q.note || q.voice) x += '<div class="ci-notebar"><textarea class="ci-in" id="ci-ans-note" rows="2" maxlength="2000" placeholder="Notitie (mag leeg)" aria-label="' + esc(t('Notitie bij {vraag}', { vraag: q.text })) + '"' + (q.note ? '' : ' readonly') + '>' + esc(ciA.note) + '</textarea>' +
      (q.voice ? '<button class="iconbtn" onclick="ciAnsVoice()" aria-label="' + esc(t('Notitie inspreken bij {vraag}', { vraag: q.text })) + '">' + uiIcon('mic') + '</button>' : '') + '</div>';
  }
  const box = $('#ci-ans-x');
  if (box._h !== x) { const keep = document.activeElement && document.activeElement.id === 'ci-ans-note'; box.innerHTML = x; box._h = x; if (keep) { const tt = $('#ci-ans-note'); if (tt) tt.focus(); } }
  $('#ci-ans-done').style.display = ciA.aid ? '' : 'none';
  $('#ci-ans-cancel').textContent = ciA.aid ? 'Sluiten' : 'Annuleren';
}
function ciAnsNoteNow(){ const tt = $('#ci-ans-note'); return tt ? tt.value : ciA.note; }
function ciAnsPick(i){
  const q = ciQ(ciA.q); if (!q) return;
  if (ciA.aid) { // ander antwoord gekozen: dat antwoord aanpassen
    const r = rdJson(Android.checkinAnswer(JSON.stringify({ q: ciA.q, id: ciA.aid, o: i })), {});
    if (r.error) { toast(r.error); return; }
    ciA.sel = i; ciAnsRender(); ciAfter(); return;
  }
  ciA.note = ciAnsNoteNow();
  ciSend({ q: ciA.q, p: ciA.key, o: i, note: ciA.note, labels: ciA.labels, voice: ciA.voice }, r => {
    ciA.aid = r.id; ciA.sel = i;
    ciAfter();
    if (!ciHasExtras(q)) { ciAnsClose(); toast(t('✓ Bewaard: {wat}', { wat: ciOptText(ciOpts(q)[i] || {}) }) + (r.late ? ' ' + t('(achteraf)') : '')); return; }
    ciAnsRender();
  });
}
function ciAnsLabel(l){
  ciA.labels = ciA.labels.includes(l) ? ciA.labels.filter(x => x !== l) : ciA.labels.concat([l]);
  ciA.note = ciAnsNoteNow();
  if (ciA.aid) { const r = rdJson(Android.checkinAnswer(JSON.stringify({ q: ciA.q, id: ciA.aid, labels: ciA.labels })), {}); if (r.error) { toast(r.error); return; } ciAfter(); }
  ciAnsRender();
}
/** Notitie bewaren (als die veranderde) en sluiten. */
function ciAnsDone(){
  if (ciA && ciA.aid) {
    const note = ciAnsNoteNow();
    if (note !== ciA.note || ciA.voice) {
      const r = rdJson(Android.checkinAnswer(JSON.stringify({ q: ciA.q, id: ciA.aid, note, voice: ciA.voice })), {});
      if (r.error) { toast(r.error); return; }
      toast('✓ Aangevuld');
    }
    ciAfter();
  }
  ciAnsClose();
}
function ciAnsClose(){
  if (ciA && ciA.aid) { const note = ciAnsNoteNow(); if (note !== ciA.note) { Android.checkinAnswer(JSON.stringify({ q: ciA.q, id: ciA.aid, note })); ciAfter(); } } // niets kwijtraken
  $('#ci-ans').classList.remove('on');
  // Focus terug naar waar je vandaan kwam (de knop is na het verversen soms nieuw: dan dezelfde knop, of de kop)
  const from = ciA && ciA.from; ciA = null;
  setTimeout(() => {
    if ($('#ci-ans').classList.contains('on') || document.querySelector('#modal.on, #voice.on, #ci-rem.on')) return;
    let el = from && from.isConnected && from.offsetParent ? from : null;
    if (!el && from && from.getAttribute && from.getAttribute('aria-label')) el = [...document.querySelectorAll('.screen.on button')].find(b => b.getAttribute('aria-label') === from.getAttribute('aria-label') && b.offsetParent);
    if (!el) el = document.querySelector('.screen.on .hdr h1');
    if (el) el.focus({ preventScroll: true });
  }, 0);
}
/* Inspreken: zelfde venster als een spraaknotitie; de tekst komt in de notitie */
function ciAnsVoice(){
  if (!ciA) return;
  ciA.note = ciAnsNoteNow();
  voiceOpen(null, { title: 'Notitie inspreken', models: true, done: text => {
    if (!ciA) return;
    ciA.note = (ciA.note ? ciA.note.trim() + ' ' : '') + text; ciA.voice = true;
    const tt = $('#ci-ans-note'); if (tt) tt.value = ciA.note;
    if (ciA.aid) { const r = rdJson(Android.checkinAnswer(JSON.stringify({ q: ciA.q, id: ciA.aid, note: ciA.note, voice: true })), {}); if (!r.error) { toast('✓ Notitie bewaard'); ciA.voice = false; ciAfter(); } }
  } });
}

/* ---------- een vraag beheren ---------- */
function ciOpen(id){ ciqId = id; ciqMonthAt = null; jumpTo('checkinq'); }
function ciSaveQ(q, msg){
  const r = rdJson(Android.checkinSave(JSON.stringify(q)), {});
  if (r.error) { toast(r.error); return false; }
  ciAfter(); if (msg) toast(msg); return r.id;
}
function ciSetOn(id, on){
  const q = ciQ(id); if (!q) return;
  if (on && ciSt.notifOk === false) Android.permRequest('notif'); // Android 13+: eerst toestemming voor meldingen
  ciSaveQ(Object.assign({}, q, { on }), on ? t('✓ Staat aan: {wanneer}', { wanneer: ciRhythmText(q).replace(/^./, x => x.toLowerCase()) }) : t('Staat uit: geen meldingen meer'));
}
function ciArchive(id, on){
  const q = ciQ(id); if (!q) return;
  if (ciSaveQ(Object.assign({}, q, { archived: on, on: on ? false : q.on }), on ? 'Gearchiveerd: de antwoorden blijven bewaard' : 'Teruggezet (staat uit; zet hem aan als je weer meldingen wilt)') && on && current === 'checkinq') goBack();
}
function ciMenu(id){
  const q = ciQ(id); if (!q) return;
  const qs = (ciSt.questions || []).filter(x => !!x.archived === !!q.archived), i = qs.indexOf(q), acts = [];
  acts.push(['Terugkijken', () => ciOpen(id), 'calendar']);
  if (!q.archived) {
    acts.push(['Bewerken', () => ciEdit(id), 'edit']);
    acts.push([q.on ? 'Uitzetten (geen meldingen)' : 'Aanzetten', () => ciSetOn(id, !q.on), q.on ? 'bell' : 'bell']);
    acts.push(['Melding nu proberen', () => ciPreview(id), 'bell']);
  }
  if (i > 0) acts.push(['Omhoog', () => { Android.checkinMove(id, -1); ciAfter(); }, 'up']);
  if (i >= 0 && i < qs.length - 1) acts.push(['Omlaag', () => { Android.checkinMove(id, 1); ciAfter(); }, 'down']);
  acts.push(['Dupliceren', () => { const r = rdJson(Android.checkinDuplicate(id), {}); if (r.error) toast(r.error); else { ciAfter(); toast('Kopie gemaakt (staat uit)'); } }, 'copy']);
  if (!q.archived) acts.push(['Archiveren', () => ciArchive(id, true), 'save']);
  else acts.push(['Terugzetten', () => ciArchive(id, false), 'refresh']);
  acts.push(['Verwijderen…', () => ciDelete(id), 'trash']);
  openSheet((q.emoji ? q.emoji + ' ' : '') + q.text, ciRhythmText(q), acts);
}
function ciDelete(id){
  const q = ciQ(id); if (!q) return;
  const n = q.total || 0;
  const del = () => { const e = Android.checkinDelete(id); if (e) { toast(e); return; } toast('Verwijderd'); if (current === 'checkinq' || current === 'checkined') show('checkin'); ciAfter(); };
  if (q.archived) askConfirm(t('Vraag verwijderen?'), tn(n, 'De vraag en het ene antwoord gaan weg. Dat kan niet ongedaan worden.', 'De vraag en alle {n} antwoorden gaan weg. Dat kan niet ongedaan worden.'), t('Verwijderen'), del);
  else askChoice(t('Vraag verwijderen?'), tn(n, 'De vraag en het ene antwoord gaan weg; dat kan niet ongedaan worden. Wil je de geschiedenis houden, kies dan Archiveren (tik ernaast om niets te doen).', 'De vraag en alle {n} antwoorden gaan weg; dat kan niet ongedaan worden. Wil je de geschiedenis houden, kies dan Archiveren (tik ernaast om niets te doen).'), t('Verwijderen'), t('Archiveren'), del, () => ciArchive(id, true));
}
function ciPreview(id){
  const e = Android.checkinPreview(id);
  if (e === 'notif') { Android.permRequest('notif'); toast('Sta meldingen toe en probeer het opnieuw'); return; }
  toast(e || 'Kijk in je meldingen');
}
/* Vanuit een melding, snelkoppeling of de assistent */
function ciOpenFrom(s){
  const m = /^checkin:([A-Za-z0-9_-]{1,40}):([0-9TW:-]{7,16})$/.exec(s || '');
  show('checkin');
  if (m) tsDaarna('checkin', () => setTimeout(() => ciAsk(m[1], m[2], {}), 60));
}
