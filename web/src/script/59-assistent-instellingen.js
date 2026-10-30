/* ---------- De assistent en de instellingen van de app ----------
 * Vragen over de app worden beantwoord met het instellingenregister (58-instellingen-register.js):
 *  - duidelijke opdrachten ("zet de nachtelijke backup aan", "zet de tekstgrootte op groot") en opzoekvragen
 *    ("waar vind ik …", "wat staat … op", "wat kan ik instellen bij …", "wat heb je veranderd") zonder taalmodel:
 *    snel, precies en ook als er nog geen taalmodel is;
 *  - andere vragen over de app ("hoe …", "wat doet …", "waarom krijg ik …") met het taalmodel, dat alleen de paar
 *    instellingen meekrijgt die erbij horen (met de stand van nu) en een wijziging mag voorstellen ([[instelling: id = waarde]]).
 * Er verandert nooit iets zonder een tik op Toepassen; daarna kan het terug met Ongedaan maken. Wat de assistent
 * aanpaste, staat in een logboek (alleen op de telefoon). Gevoelige instellingen: alleen uitleg en Openen.
 * In een losse app (zonder app-slot) kan dit niet. */

function setAll(){
  const gen = [];
  SET_GEN.forEach(g => { try { gen.push(...g.gen()); } catch (e) { } });
  return SET_REG.concat(gen);
}
function setEntry(id){ return setAll().find(e => e.id === id) || null; }
function setType(e){ return e.type || 'aan'; }
function setVals(e){ let v = e.vals; if (typeof v === 'function') { try { v = v(); } catch (x) { v = []; } } return v || (setType(e) === 'aan' ? SET_AU : []); }
function setGet(e){ if (!e || !e.get || e.safe === 'geheim') return ''; try { const v = e.get(); return v == null ? '' : String(v); } catch (x) { return ''; } }
function setName(e, v){ if (v === '' || v == null) return ''; const x = setVals(e).find(p => p[0] === v); return x ? (setType(e) === 'aan' ? x[1].toLowerCase() : x[1]) : e.vname ? String(e.vname(v)) : String(v); }
/* Mag de assistent dit naar deze waarde zetten? Gevoelig = nee (alleen uitleg en Openen) */
function setSafe(e, v){ return !!e.safe && (e.safe === 'geheim' || !e.weak || !!e.weak(v)); }
function setCanChange(e){ const t = setType(e); return (t === 'aan' || t === 'keuze' || t === 'tijd') && typeof e.set === 'function' && e.safe !== 'geheim'; }
/* Waar het staat: de koppen van de schermen, van boven naar beneden */
function setPath(scr){
  const out = []; let n = scr, g = 0;
  while (n && n !== 'home' && g++ < 6) { const h = document.querySelector('#s-' + n + ' .hdr h1'); out.unshift(h ? h.textContent.trim() : n); n = BACK[n]; }
  return out.join(' → ');
}

/* ---------- zoeken in het register ---------- */
const SET_STOP = new Set(('de het een en of in op aan uit af bij van voor met naar te is zijn was wat waar hoe kan ik je jij u mijn me mij m\'n zet zetten schakel schakelen staat staan stel ' +
  'stellen instellen instelling instellingen app tools graag even wil wilt kun kunt kunnen moet dat dit die er om ook nog niet geen wel welke wanneer waarom vind vinden zit zitten doe doet dan als ' +
  'alles allemaal heb hebt heeft wordt worden aub alsjeblieft svp please nu iets ding knop optie mogelijk').split(' '));
function setWords(s){
  return norm2(s).replace(/['’`]/g, '').replace(/([a-z])-([a-z])/g, '$1$2').replace(/[^a-z0-9]+/g, ' ').split(' ').filter(w => w.length > 1 && !SET_STOP.has(w));
}
function setStem(w){ return w.length > 5 ? w.replace(/(elijke|elijk|ingen|ing|en|es|e|s)$/, '') : w; }
/* Hoe goed een woord uit de vraag past bij een woord van de instelling (0 tot 1) */
function setWm(q, f){
  if (q === f) return 1;
  const a = setStem(q), b = setStem(f);
  if (a === b && a.length >= 3) return 0.9;
  if (q.length >= 4 && f.startsWith(q)) return 0.8;
  if (f.length >= 4 && q.startsWith(f)) return 0.7;
  if (q.length >= 6 && f.includes(q)) return 0.6;
  if (f.length >= 6 && q.includes(f)) return 0.5;
  return 0;
}
function setIdx(e){
  if (!e._w) { const nd = SET_NEED.find(x => e.id.startsWith(x[0])); e._w = { l: setWords(e.label), s: setWords(e.syn || ''), p: setWords(setPath(e.scr)), d: setWords(e.desc || ''), n: norm2(e.label), need: e.need || (nd && nd[1]) }; }
  return e._w;
}
function setScore(e, qs, raw){
  const w = setIdx(e), best = (q, list) => list.reduce((m, f) => Math.max(m, setWm(q, f)), 0);
  let s = 0;
  qs.forEach(q => { s += Math.max(3 * best(q, w.l), 2 * best(q, w.s), 1.2 * best(q, w.p), 0.6 * best(q, w.d)); });
  const nq = norm2(raw || '');
  if (w.n.length > 4 && nq.includes(w.n)) s += 2;
  if (e.phr && e.phr.some(p => nq.includes(norm2(p)))) s += 3;
  if (w.need && !w.need.test(nq)) s *= 0.6; // een instelling van een andere tool dan genoemd
  return s;
}
function setFind(text, list){
  const qs = [...new Set(setWords(text))];
  if (!qs.length) return [];
  return (list || setAll()).map(e => ({ e, s: setScore(e, qs, text) })).filter(x => x.s > 0).sort((a, b) => b.s - a.s);
}
const SET_MIN = 2.4; // minstens één woord uit de naam, of een paar zoekwoorden

/* ---------- wat wordt er gevraagd ---------- */
const SET_ON = /^(aan|in|aanzetten|aan zetten|inschakelen|activeren)$/, SET_OFF = /^(uit|af|uitzetten|uit zetten|uitschakelen|deactiveren)$/;
const SET_PRE = '(?:(?:kun|kan|wil) (?:je|jij|u) (?:even |eens )?|graag |mag |ik wil (?:graag )?|doe eens )?';
const SET_Q = /\b(instelling|instellingen|instellen|stel ik|stel je|aanzetten|uitzetten|aan zetten|uit zetten|inschakelen|uitschakelen|wat doet|waarom krijg ik|hoe zet ik|hoe schakel ik|hoe verander ik|hoe pas ik)\b/;
function setIntent(q){
  const s = norm2(q).replace(/[?!.]+\s*$/, '').replace(/\s+/g, ' ').trim();
  if (!s || s.length > 200 || /^(onthoud|vergeet)\b/.test(s)) return null; // dat is het geheugen
  if (/\b(herinner|notitie|noteer|agenda)\w*/.test(s) && !/\binstelling/.test(s)) return null; // dat zijn acties
  let m;
  if (/\b(wat|welke)\b.*\b(veranderd|gewijzigd|aangepast)\b/.test(s) && /\b(ik|je|jij|instelling|instellingen|heb|hebt)\b/.test(s) && !/\btekst\b/.test(s)) return { kind: 'log' };
  if ((m = new RegExp('^' + SET_PRE + 'wat (?:kan|kun) (?:ik|je) (?:allemaal |zoal )?(?:instellen|aanpassen|veranderen|regelen) (?:bij|in|voor|aan|van) (.+)$').exec(s)) ||
      (m = /^welke instellingen (?:heeft|hebben|zijn er (?:bij|voor|in)|zitten er in|horen bij) (.+)$/.exec(s)) || (m = /^(?:de )?instellingen (?:van|voor|bij) (.+)$/.exec(s))) return { kind: 'lijst', x: m[1] };
  if ((m = new RegExp('^' + SET_PRE + '(?:zet|schakel|doe)\\s+(.+?)\\s+(aan|uit|in|af)$').exec(s))) return { kind: 'cmd', x: m[1], raw: m[2], sure: true };
  if ((m = new RegExp('^' + SET_PRE + '(.+?)\\s+(aanzetten|uitzetten|aan zetten|uit zetten|inschakelen|uitschakelen)$').exec(s))) return { kind: 'cmd', x: m[1], raw: m[2], sure: true };
  if ((m = new RegExp('^' + SET_PRE + '(?:zet|stel|verander|wijzig)\\s+(.+?)\\s+(?:op|naar|in|om)\\s+(.+)$').exec(s))) return { kind: 'cmd', x: m[1], raw: m[2] };
  if ((m = new RegExp('^' + SET_PRE + '(.+?)\\s+(?:op|naar)\\s+(.+?)\\s+(?:zetten|instellen|veranderen|wijzigen)$').exec(s))) return { kind: 'cmd', x: m[1], raw: m[2] };
  if ((m = /^waar (?:vind|zie|zet|stel|verander|regel|kan|zit|staat|staan|zitten|pas) (?:ik |je |jij )?(.+?)(?: (?:vinden|instellen|aanzetten|uitzetten|aanpassen|veranderen|zien|in|aan|uit))*$/.exec(s))) return { kind: 'waar', x: m[1] };
  if ((m = /^(?:wat|hoe) (?:staat|staan) (.+?) (?:op|ingesteld)$/.exec(s)) || (m = /^(?:staat|staan|is) (.+?) (?:aan|uit|ingeschakeld|uitgeschakeld|ingesteld)$/.exec(s))) return { kind: 'stand', x: m[1] };
  if (SET_Q.test(s)) return { kind: 'vraag', x: s };
  return null;
}
/* De waarde uit de vraag, zoals de app hem kent; null als hij niet bij deze instelling past */
function setValFor(e, raw){
  const t = setType(e), r = norm2(raw || '').replace(/^(?:de|het|een|stand|op)\s+/, '').replace(/\s+(?:stand|zetten|uur)$/, '').trim();
  if (!r) return null;
  const vals = setVals(e);
  if (t === 'aan') return SET_ON.test(r) ? 'aan' : SET_OFF.test(r) ? 'uit' : null;
  if (t === 'tijd') return setTime(r);
  if (t !== 'keuze') return null;
  const nm = x => norm2(x).replace(/[×x]$/, '').replace(',', '.').trim(), rr = nm(r);
  let v = vals.find(x => nm(x[0]) === rr || nm(x[1]) === rr);
  if (!v && SET_OFF.test(r)) v = vals.find(x => /^(uit|off)$/.test(nm(x[0])) || nm(x[1]) === 'uit');
  if (!v && rr.length >= 3) v = vals.find(x => nm(x[1]).startsWith(rr) || nm(x[1]).split(' ')[0] === rr);
  if (!v && /^\d+([.]\d+)?$/.test(rr)) v = vals.find(x => parseFloat(nm(x[0])) === parseFloat(rr) || parseFloat(nm(x[1])) === parseFloat(rr));
  return v ? v[0] : null;
}
/* "7:30", "07.30", "7 uur", "half 8" → "07:30" */
function setTime(r){
  let m, h = -1, mi = 0;
  if ((m = /^(\d{1,2})[:.](\d{2})$/.exec(r))) { h = +m[1]; mi = +m[2]; }
  else if ((m = /^(\d{1,2})$/.exec(r))) h = +m[1];
  else if ((m = /^half (\d{1,2})$/.exec(r))) { h = (+m[1] + 23) % 24; mi = 30; }
  else if ((m = /^kwart over (\d{1,2})$/.exec(r))) { h = +m[1]; mi = 15; }
  else if ((m = /^kwart voor (\d{1,2})$/.exec(r))) { h = (+m[1] + 23) % 24; mi = 45; }
  if (h < 0 || h > 23 || mi > 59) return null;
  return String(h).padStart(2, '0') + ':' + String(mi).padStart(2, '0');
}

/* ---------- antwoorden ---------- */
const setB0 = s => '**' + s + '**';
function setNow(e){ const v = setGet(e); return v ? setName(e, v) : ''; }
function setWhere(e){ return setPath(e.scr) || 'Instellingen'; }
/* Een wijziging voorstellen (of uitleggen waarom niet) */
function setPropose(e, v){
  const where = setWhere(e);
  if (setSafe(e, v) || !setCanChange(e)) {
    const now = setNow(e);
    return { text: setB0(e.label) + (now ? ' staat nu op ' + now + '.' : '.') + ' Dit pas ik niet voor je aan' + (e.why ? ': ' + e.why.charAt(0).toLowerCase() + e.why.slice(1) : '.') + ' Je vindt het bij ' + where + '.', set: { open: [e.id] } };
  }
  const na = e.avail ? e.avail() : '';
  if (na) return { text: 'Dat kan nu niet: ' + na.charAt(0).toLowerCase() + na.slice(1) + '. ' + setB0(e.label) + ' vind je bij ' + where + '.', set: { open: [e.id] } };
  const cur = setGet(e);
  if (cur === v) return { text: setB0(e.label) + ' staat al op ' + setName(e, v) + '. Je vindt het bij ' + where + '.', set: { open: [e.id] } };
  return { text: 'Ik kan ' + setB0(e.label) + ' voor je aanpassen' + (cur ? ': van ' + setName(e, cur) + ' naar ' + setName(e, v) : ' naar ' + setName(e, v)) + '. Bevestig hieronder met Toepassen.',
    set: { items: [{ id: e.id, from: cur, to: v, st: 'voorgesteld' }] } };
}
function setLookup(e, kind){
  const now = setNow(e), where = setWhere(e), secret = e.safe === 'geheim';
  const val = secret ? ' Wat erin staat, lees ik niet voor.' : now ? ' Staat nu: ' + now + '.' : '';
  const t = kind === 'stand' ? (secret ? setB0(e.label) + ' vind je bij ' + where + '.' + val : now ? setB0(e.label) + ' staat op ' + now + '. Je vindt het bij ' + where + '.' : setB0(e.label) + ' vind je bij ' + where + '.')
    : setB0(e.label) + ' vind je bij ' + where + '.' + val;
  return { text: t + (e.desc ? ' ' + e.desc : ''), set: { open: [e.id] } };
}
function setNotFound(x){ return { text: 'Ik vond geen instelling die past bij "' + String(x).slice(0, 60) + '". Probeer het met andere woorden, of kijk bij Instellingen.', set: { open: ['__instellingen'] } }; }
/* Wat de assistent aan het taalmodel meegeeft over deze instellingen */
function setOffer(list){
  return list.map(e => ({ id: e.id, label: e.label, where: setWhere(e), desc: e.desc || '', cur: e.safe === 'geheim' ? '' : setGet(e), type: setType(e),
    safe: !!e.safe && !e.weak || !setCanChange(e), vals: setCanChange(e) ? setVals(e).slice(0, 12) : [] }));
}
/* Instellingen per scherm ("wat kan ik instellen bij de assistent") */
function setList(x){
  const all = setAll(), scrs = [...new Set(all.map(e => e.scr))];
  const qs = setWords(x);
  // Het best passende scherm (de kop, of een scherm daarboven: "Assistent" geeft ook Lokale AI en de rest)
  const titles = {}; scrs.forEach(sc => setPath(sc).split(' → ').forEach(t => { titles[t] = true; }));
  let best = null, bs = 0;
  Object.keys(titles).forEach(t => { const ws = setWords(t); let sc = 0; qs.forEach(q => { sc += ws.reduce((m, f) => Math.max(m, setWm(q, f)), 0); }); if (sc > bs) { bs = sc; best = t; } });
  let list = best && bs >= 0.7 ? all.filter(e => setPath(e.scr).split(' → ').includes(best)) : [];
  if (!list.length) list = setFind(x, all).filter(h => h.s >= SET_MIN).slice(0, 8).map(h => h.e);
  if (!list.length) return setNotFound(x);
  const rows = list.slice(0, 24).map(e => '- ' + e.label + (e.safe === 'geheim' ? '' : setNow(e) ? ': ' + setNow(e) : ''));
  const ex = list.find(e => setCanChange(e) && !e.safe && setType(e) === 'aan');
  return { text: (best && bs >= 0.7 ? 'Bij ' + setB0(best) + ' kun je instellen:' : 'Deze instellingen horen erbij:') + '\n' + rows.join('\n') + (list.length > 24 ? '\n- … en nog ' + (list.length - 24) + ' meer' : '') +
    '\n\n' + (ex ? 'Zeg bijvoorbeeld "zet ' + ex.label.charAt(0).toLowerCase() + ex.label.slice(1) + ' ' + (setGet(ex) === 'aan' ? 'uit' : 'aan') + '", of tik op Openen.' : 'Tik op Openen om ernaartoe te gaan.'), set: { open: [list[0].id] } };
}
function setLogText(){
  const log = setLogGet();
  if (!log.length) return { text: 'Ik heb nog geen instellingen voor je aangepast.' };
  return { text: 'Dit heb ik voor je aangepast (nieuwste eerst):', set: { log: log.slice(0, 10).map(x => x.n) } };
}

/* Een vraag over de app? Geeft null (gewoon de assistent), {local, text, set} (antwoord zonder taalmodel) of
   {settings, set} (taalmodel, met deze instellingen) */
function setHandle(q){
  const it = setIntent(q);
  if (!it) return null;
  if (typeof SOLO !== 'undefined' && SOLO) return { local: true, text: 'In een losse app kan ik de instellingen van Tools niet bekijken of aanpassen. Open daarvoor Tools zelf.' };
  const model = aiChatReady();
  if (it.kind === 'log') return Object.assign({ local: true }, setLogText());
  if (it.kind === 'lijst') return Object.assign({ local: true }, setList(it.x));
  const hits = setFind(it.x);
  const top = hits[0], second = hits[1];
  const clear = top && top.s >= SET_MIN && (!second || second.s < top.s * 0.8);
  if (it.kind === 'cmd') {
    const raw = it.raw;
    const valid = hits.slice(0, 8).filter(h => h.s >= SET_MIN && setValFor(h.e, raw) !== null);
    if (valid.length && valid[0].s >= top.s * 0.6) {
      if (valid[1] && valid[1].s >= valid[0].s * 0.85) {
        return { local: true, text: 'Welke instelling bedoel je?', set: { pick: valid.slice(0, 3).map(h => ({ id: h.e.id, v: setValFor(h.e, raw) })) } };
      }
      const e = valid[0].e;
      return Object.assign({ local: true }, setPropose(e, setValFor(e, raw)));
    }
    if (top && top.s >= SET_MIN) {
      const e = top.e;
      if (!setCanChange(e) || setSafe(e, 'aan') && setSafe(e, 'uit')) return Object.assign({ local: true }, setPropose(e, ''));
      if (setType(e) === 'keuze') return { local: true, text: setB0(e.label) + (setNow(e) ? ' staat nu op ' + setNow(e) + '.' : '.') + ' Kies een van deze:', set: { choose: e.id, open: [e.id] } };
      if (setType(e) === 'tijd') return { local: true, text: 'Welke tijd wil je voor ' + setB0(e.label) + '? Zeg bijvoorbeeld "zet ' + e.label.toLowerCase() + ' op 07:30".', set: { open: [e.id] } };
    }
    if (it.sure && (!top || top.s < SET_MIN)) return Object.assign({ local: true }, setNotFound(it.x));
    return null; // geen duidelijke instelling ("zet dit op een rijtje"): gewoon de assistent
  }
  if (it.kind === 'waar' || it.kind === 'stand') {
    if (clear) return Object.assign({ local: true }, setLookup(top.e, it.kind));
    const some = hits.filter(h => h.s >= SET_MIN * 0.6).slice(0, 4).map(h => h.e);
    if (some.length && model) return { settings: setOffer(some), set: { open: some.slice(0, 3).map(e => e.id), offer: some.map(e => e.id) } };
    if (some.length) return { local: true, text: 'Bedoel je een van deze? Tik op Openen om ernaartoe te gaan.', set: { open: some.slice(0, 3).map(e => e.id) } };
    return model ? null : Object.assign({ local: true }, setNotFound(it.x));
  }
  // Uitleg ("hoe …", "wat doet …"): met het taalmodel als dat er is
  const some = hits.filter(h => h.s >= SET_MIN * 0.6).slice(0, 4).map(h => h.e);
  if (!some.length) return null;
  if (model) return { settings: setOffer(some), set: { open: some.slice(0, 3).map(e => e.id), offer: some.map(e => e.id) } };
  if (clear) return Object.assign({ local: true }, setLookup(top.e, 'waar'));
  return { local: true, text: 'Bedoel je een van deze? Tik op Openen om ernaartoe te gaan.', set: { open: some.slice(0, 3).map(e => e.id) } };
}
/* Opnieuw antwoorden: zelfde instellingen als de vraag erom vroeg */
function setOfferFor(q){ const h = setHandle(q); return h && h.settings ? h : null; }

/* Wat het taalmodel voorstelde: streng tegen het register (alleen meegegeven, niet gevoelig, een bekende waarde) */
function setFromModel(m, list){
  if (!m || !m.set || !Array.isArray(list)) return;
  const offer = m.set.offer || [], items = [];
  list.slice(0, 4).forEach(x => {
    const id = String(x && x.id || ''), e = offer.includes(id) ? setEntry(id) : null;
    if (!e || !setCanChange(e)) return;
    const v = setValFor(e, String(x.value || '')) || setVals(e).map(p => p[0]).find(p => p === String(x.value || '')) || (setType(e) === 'tijd' ? setTime(String(x.value || '')) : null);
    if (v == null || setSafe(e, v) || (e.avail && e.avail())) return;
    const cur = setGet(e);
    if (cur === v || items.some(y => y.id === id)) return;
    items.push({ id, from: cur, to: v, st: 'voorgesteld' });
  });
  if (items.length) m.set.items = items;
}

/* ---------- kaarten onder het antwoord ---------- */
function setMsgHtml(m, i){
  const s = m && m.set; if (!s) return '';
  let h = '';
  (s.items || []).forEach((x, k) => { h += setItemHtml(x, i, k); });
  if (s.pick && s.pick.length) h += '<div class="aiact" role="group" aria-label="Welke instelling bedoel je?"><b>' + uiIcon('settings') + 'Welke bedoel je?</b><div class="aiactb">' +
    s.pick.map((p, k) => { const e = setEntry(p.id); return e ? '<button class="mini" onclick="setPick(' + i + ',' + k + ')">' + esc(e.label + (p.v ? ': ' + setName(e, p.v) : '')) + '</button>' : ''; }).join('') + '</div></div>';
  if (s.choose && !(s.items || []).length) { const e = setEntry(s.choose); if (e) { const cur = setGet(e);
    h += '<div class="aiact" role="group" aria-label="' + esc('Kies: ' + e.label) + '"><b>' + uiIcon('settings') + esc(e.label) + '</b><div class="aiactb">' +
      setVals(e).filter(x => x[0] !== cur).map(x => '<button class="mini" onclick="setChoose(' + i + ',' + jsq(x[0]) + ')">' + esc(x[1]) + '</button>').join('') + '</div></div>'; } }
  if (s.log) { const log = setLogGet();
    h += '<div class="aiact setlog" role="list">' + s.log.map(n => { const x = log.find(y => y.n === n); if (!x) return '';
      return '<div class="setlogrow" role="listitem"><span>' + (x.undone ? '<s>' : '') + esc(x.label) + ': ' + esc(x.fromN || x.from) + ' → <b>' + esc(x.toN || x.to) + '</b>' + (x.undone ? '</s> <small>(teruggezet)</small>' : '') + '<small>' + esc(fmtD(x.t)) + '</small></span>' +
        (x.undone ? '' : '<button class="linkish" onclick="setLogUndo(' + n + ',' + i + ')" aria-label="' + esc('Ongedaan maken: ' + x.label) + '">Ongedaan maken</button>') + '</div>'; }).join('') + '</div>'; }
  if (s.open && s.open.length) h += '<div class="aisrc setopen">' + s.open.map(id => {
    if (id === '__instellingen') return '<button class="aichip sm" onclick="jumpTo(\'settings\')">' + uiIcon('settings') + 'Instellingen openen</button>';
    const e = setEntry(id); return e ? '<button class="aichip sm" onclick="setOpen(' + jsq(id) + ')" aria-label="' + esc('Openen: ' + e.label) + '">' + uiIcon('external') + esc(e.label) + '</button>' : ''; }).join('') + '</div>';
  return h;
}
function setItemHtml(x, i, k){
  const e = setEntry(x.id); if (!e) return '';
  const from = x.from === '' || x.from == null ? '' : setName(e, x.from), to = setName(e, x.to);
  const open = '<button class="linkish" onclick="setOpen(' + jsq(e.id) + ')">Openen</button>';
  if (x.st === 'gedaan') return '<div class="aiact done" role="status">' + uiIcon('check') + '<span>' + esc(e.label) + ': <b>' + esc(to) + '</b>' + (from ? '<small>Was: ' + esc(from) + '</small>' : '') + '</span>' +
    '<button class="linkish" onclick="setUndo(' + i + ',' + k + ')" aria-label="' + esc('Ongedaan maken: ' + e.label) + '">Ongedaan maken</button>' + open + '</div>';
  if (x.st === 'terug') return '<div class="aiact done" role="status">' + uiIcon('refresh') + '<span>Teruggezet: ' + esc(e.label) + ' weer op <b>' + esc(from) + '</b></span>' + open + '</div>';
  if (x.st === 'nee') return '<div class="aiact no">' + uiIcon('settings') + '<span><s>' + esc(e.label + ' naar ' + to) + '</s> <small>(niet veranderd)</small></span><button class="linkish" onclick="setAgain(' + i + ',' + k + ')">Toch voorstellen</button></div>';
  if (x.st === 'fout') return '<div class="aiact no" role="status">' + uiIcon('alert') + '<span>' + esc(e.label) + ': ' + esc(x.err || 'aanpassen lukte niet') + '</span>' + open + '</div>';
  const warn = e.warn ? e.warn(x.to) : '';
  return '<div class="aiact setcard" role="group" aria-label="' + esc('Voorstel: ' + e.label + ' aanpassen') + '"><b>' + uiIcon('settings') + 'Instelling aanpassen?</b>' +
    '<div class="aiactt">' + esc(e.label) + '<small>' + esc(setWhere(e)) + '</small></div>' +
    '<div class="setft">' + (from ? '<span>Van <s>' + esc(from) + '</s></span>' : '') + '<span>' + (from ? 'naar' : 'Naar') + ' <b>' + esc(to) + '</b></span></div>' +
    (warn ? '<p class="note setwarn">' + uiIcon('alert') + esc(warn) + '</p>' : '') +
    '<div class="aiactb"><button class="mini on" onclick="setDo(' + i + ',' + k + ')" aria-label="' + esc('Toepassen: ' + e.label + ' naar ' + to) + '">Toepassen</button>' +
    '<button class="mini" onclick="setNo(' + i + ',' + k + ')" aria-label="' + esc('Annuleren: ' + e.label) + '">Annuleren</button>' + open + '</div></div>';
}
function setItem(i, k){ const m = aiChat && aiChat.msgs[i], x = m && m.set && m.set.items && m.set.items[k]; return x ? { m, x } : null; }
/* Is de wijziging (nog) toegestaan? "" = ja */
function setCheck(e, v){
  if (!e) return 'Deze instelling bestaat niet meer';
  if (!setCanChange(e) || setSafe(e, v)) return 'Dit pas je zelf aan op het scherm';
  if (setType(e) === 'tijd' ? !setTime(v) : !setVals(e).some(x => x[0] === v)) return 'Deze waarde kan niet';
  const na = e.avail ? e.avail() : ''; if (na) return na;
  return '';
}
function setDo(i, k){
  const it = setItem(i, k); if (!it || it.x.st !== 'voorgesteld') return;
  const x = it.x, e = setEntry(x.id), why = setCheck(e, x.to);
  if (why) { x.st = 'fout'; x.err = why; aiMemAfter(i); toast(why); return; }
  const before = setGet(e);
  if (before === x.to) { x.st = 'gedaan'; aiMemAfter(i); toast(e.label + ' stond al op ' + setName(e, x.to)); return; }
  let err = '';
  try { err = e.set(x.to) || ''; } catch (er) { err = 'Aanpassen lukte niet'; }
  const after = setGet(e);
  if (!err && e.get && after !== x.to) err = 'Aanpassen lukte niet; kijk op het scherm (Openen)';
  if (err) { x.st = 'fout'; x.err = err; aiMemAfter(i); toast(err); aiVSay(err); return; }
  x.from = before; x.st = 'gedaan'; x.log = setLogAdd(e, before, x.to);
  aiMemAfter(i);
  const msg = 'Aangepast: ' + e.label + ' staat nu op ' + setName(e, x.to);
  toast(msg); aiVSay(msg);
}
function setNo(i, k){ const it = setItem(i, k); if (!it || it.x.st !== 'voorgesteld') return; it.x.st = 'nee'; aiMemAfter(i); }
function setAgain(i, k){ const it = setItem(i, k); if (!it || it.x.st !== 'nee') return; it.x.st = 'voorgesteld'; it.x.from = setGet(setEntry(it.x.id)); aiMemAfter(i); }
/* Terugzetten: alleen als het intussen niet al iets anders is */
function setBack(id, from, to){
  const e = setEntry(id); if (!e) return 'Deze instelling bestaat niet meer';
  const cur = setGet(e);
  if (cur !== to) return 'Intussen staat ' + e.label + ' op ' + setName(e, cur) + '; niets teruggezet';
  let err = ''; try { err = e.set(from) || ''; } catch (er) { err = 'Terugzetten lukte niet'; }
  if (!err && e.get && setGet(e) !== from) err = 'Terugzetten lukte niet; kijk op het scherm (Openen)';
  return err;
}
function setUndo(i, k){
  const it = setItem(i, k); if (!it || it.x.st !== 'gedaan') return;
  const x = it.x, err = setBack(x.id, x.from, x.to);
  if (err) { toast(err); return; }
  x.st = 'terug'; setLogUndone(x.log);
  aiMemAfter(i); const e = setEntry(x.id); toast('Teruggezet: ' + e.label + ' weer op ' + setName(e, x.from)); aiVSay('Teruggezet');
}
function setPick(i, k){
  const m = aiChat && aiChat.msgs[i], p = m && m.set && m.set.pick && m.set.pick[k]; if (!p) return;
  const e = setEntry(p.id); if (!e) return;
  const r = setPropose(e, p.v);
  m.set = Object.assign({}, r.set); m.c = r.text; aiMemAfter(i);
}
function setChoose(i, v){
  const m = aiChat && aiChat.msgs[i], e = m && m.set && setEntry(m.set.choose); if (!e) return;
  const r = setPropose(e, v);
  m.set = Object.assign({ choose: e.id }, r.set); aiMemAfter(i);
}
/* Naar het scherm, en de instelling even aanwijzen */
function setOpen(id){
  const e = setEntry(id); if (!e) return;
  if (typeof SOLO !== 'undefined' && SOLO) { toast('Open daarvoor Tools zelf'); return; }
  jumpTo(e.scr);
  if (!e.ctl) return;
  setTimeout(() => {
    const el = document.querySelector('#s-' + e.scr + ' ' + e.ctl.split(',')[0].trim()) || document.querySelector(e.ctl.split(',')[0].trim());
    const t = el && (el.closest('.sw, .setrow, .aicfg, label.chk, .row, .card') || el);
    if (!t || !t.offsetParent) return;
    t.scrollIntoView({ block: 'center', behavior: 'smooth' });
    t.classList.add('setflash'); setTimeout(() => t.classList.remove('setflash'), 1800);
  }, 150);
}

/* ---------- logboek: wat de assistent aanpaste (alleen op de telefoon) ---------- */
function setLogGet(){ let l = []; try { l = store.get('aiSetLog', []) || []; } catch (e) { } return Array.isArray(l) ? l : []; }
function setLogAdd(e, from, to){
  const l = setLogGet(), n = (l.reduce((m, x) => Math.max(m, x.n || 0), 0)) + 1;
  l.unshift({ n, id: e.id, label: e.label, from, to, fromN: setName(e, from), toN: setName(e, to), t: Date.now() });
  store.set('aiSetLog', l.slice(0, 40));
  return n;
}
function setLogUndone(n){ const l = setLogGet(), x = l.find(y => y.n === n); if (x) { x.undone = true; store.set('aiSetLog', l); } }
function setLogUndo(n, i){
  const x = setLogGet().find(y => y.n === n); if (!x || x.undone) return;
  const err = setBack(x.id, x.from, x.to);
  if (err) { toast(err); return; }
  setLogUndone(n);
  // Ook de kaart bij het oorspronkelijke voorstel bijwerken (als dat in dit gesprek staat)
  (aiChat ? aiChat.msgs : []).forEach(m => (m.set && m.set.items || []).forEach(y => { if (y.log === n && y.st === 'gedaan') y.st = 'terug'; }));
  aiSaveChat(aiChat); aiRenderMsgs();
  toast('Teruggezet: ' + x.label + ' weer op ' + (x.fromN || x.from)); aiVSay('Teruggezet');
}
