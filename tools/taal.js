#!/usr/bin/env node
/* Taal: welke Nederlandse teksten heeft de app, en heeft elke tekst een Engelse vertaling (web/src/taal/en.json)?
 * Gebruik:
 *   node tools/taal.js            controle: ontbrekende vertalingen, ongebruikte vertalingen, harde teksten zonder t()
 *   node tools/taal.js --lijst    alle sleutels als JSON (bron → [plekken]); --ontbrekend: alleen die zonder vertaling
 * Ook gebruikt door tests/ui/taal-dekking.test.js. Zie 02-taal.js en Taal.java voor hoe vertalen werkt, en AGENTS.md.
 * Wat telt als tekst:
 *  - vaste HTML (schermen, vensters, speler, start.html): tekst en de attributen placeholder, aria-label, title, alt,
 *    precies zoals 02-taal.js de pagina opdeelt (taalKeys, in een browser);
 *  - in JavaScript: t('…'), tn(n, '…', '…'), en letterlijke teksten in toast, undoToast, askConfirm, askInput,
 *    askChoice, openSheet en in HTML-stukjes ('<p class="note">Zoeken…</p>');
 *  - in Java: Taal.t(ctx, "…") en Taal.tn(ctx, n, "…", "…").
 * Bewaker: een melding of tekst die aan elkaar geplakt wordt met Nederlandse stukjes ('Klaar: ' + x) zonder t()
 * is een fout, behalve op regels in tools/taal-uitzonderingen.txt (met de reden). */
'use strict';
const fs = require('fs');
const path = require('path');
const ROOT = path.join(__dirname, '..');
const SRC = path.join(ROOT, 'web', 'src');
const rd = p => fs.readFileSync(p, 'utf8');
const norm = s => String(s).replace(/\u00ad/g, '').replace(/[ \t\r\n]+/g, ' ').trim();
const hasLetters = s => /\p{L}{2}/u.test(s);

/* ---------- JavaScript: letterlijke tekenreeksen met hun plek en buren ---------- */
function jsTokens(src){
  const out = []; let i = 0, last = ''; const n = src.length;
  const regexOk = () => /^$|[(,=:[!&|?{};+\-*%<>~^]$/.test(last) || /\b(return|typeof|case|in|of|void|delete|throw|else|do)$/.test(src.slice(Math.max(0, i - 8), i).trimEnd());
  while (i < n) {
    const c = src[i];
    if (c === '/' && src[i + 1] === '/') { const e = src.indexOf('\n', i); i = e < 0 ? n : e; continue; }
    if (c === '/' && src[i + 1] === '*') { const e = src.indexOf('*/', i + 2); i = e < 0 ? n : e + 2; continue; }
    if (c === '\'' || c === '"') {
      let j = i + 1, s = '';
      while (j < n && src[j] !== c && src[j] !== '\n') { if (src[j] === '\\') { s += unesc(src[j + 1]); j += 2; } else { s += src[j]; j++; } }
      out.push({ s, at: i, end: j + 1, q: c }); i = j + 1; last = 'S'; continue;
    }
    if (c === '`') {
      let j = i + 1, s = '', tpl = false;
      while (j < n && src[j] !== '`') {
        if (src[j] === '\\') { s += unesc(src[j + 1]); j += 2; continue; }
        if (src[j] === '$' && src[j + 1] === '{') { tpl = true; let d = 1; j += 2; s += '${…}'; while (j < n && d) { if (src[j] === '{') d++; else if (src[j] === '}') d--; j++; } continue; }
        s += src[j]; j++;
      }
      out.push({ s, at: i, end: j + 1, q: '`', tpl }); i = j + 1; last = 'S'; continue;
    }
    if (c === '/' && regexOk()) { // reguliere expressie overslaan
      let j = i + 1, cls = false;
      while (j < n && src[j] !== '\n') { if (src[j] === '\\') { j += 2; continue; } if (src[j] === '[') cls = true; else if (src[j] === ']') cls = false; else if (src[j] === '/' && !cls) break; j++; }
      i = j + 1; while (/[a-z]/.test(src[i] || '')) i++; last = 'R'; continue;
    }
    if (!/\s/.test(c)) last = c;
    i++;
  }
  return out;
}
function unesc(ch){ return ({ n: '\n', t: '\t', r: '', '\'': '\'', '"': '"', '\\': '\\', '`': '`' })[ch] !== undefined ? ({ n: '\n', t: '\t', r: '', '\'': '\'', '"': '"', '\\': '\\', '`': '`' })[ch] : ch === 'u' ? '\\u' : ch; }
function lineOf(src, at){ let l = 1; for (let i = 0; i < at; i++) if (src.charCodeAt(i) === 10) l++; return l; }
/* Lijkt op Nederlandse tekst voor Rene (geen code, id, selector, sleutel of bestandsnaam) */
const NL_WORDS = new Set('de het een en van in op met voor niet is je jouw je je naar bij uit aan als of dan nog al geen wel om te tot over door dit dat deze die wat hoe wordt worden kan kunnen moet moeten mag is zijn was er hier daar nu ook alleen eerst later nieuw nieuwe niets alles meer minder keer keren dag dagen uur uren minuut minuten seconden week maand jaar vandaag gisteren morgen klaar bezig mislukt gelukt bewaard opgeslagen gewist verwijderd kies kiezen tik zet aan uit opnieuw terug annuleren opslaan verwijderen wissen'.split(' '));
function looksDutch(s){
  const t = norm(s);
  if (!hasLetters(t) || t.length < 2) return false;
  if (/^[\w$.#\-\[\]=:>*\s,"']+$/.test(t) && !/\s/.test(t) && !/^[A-Z][a-zë]+$/.test(t)) return false; // id, selector, sleutel
  if (/^(https?:|file:|content:|data:|blob:|\.|#|\/)/.test(t) && !/\s\p{L}{2,}\s/u.test(t)) return false;
  if (/^[a-z][a-zA-Z0-9_]*$/.test(t)) return false; // naam in code
  if (/[{};]\s*$|=>|\bfunction\b|\(\)/.test(t)) return false;
  const words = t.toLowerCase().match(/\p{L}+/gu) || [];
  if (words.some(w => NL_WORDS.has(w))) return true;
  return /^[A-ZÉ][a-zéëïöü]+/.test(t) || /\s/.test(t);
}
/* Duidelijk Nederlandse woorden (voor de bewaker op aan elkaar geplakte tekst) */
const DUTCHY = /(^|[^\p{L}])(de|het|een|niet|voor|met|naar|wordt|kies|tik|nog|geen|deze|dit|van|je|bij|uit|aan|als|ook|alleen|op|is|zijn|om|tot|en|dag|dagen|uur|minuten|keer|staat|klaar|gestopt|vrij|versie|gemiddeld|vraagt|leert|meteen|antwoord|antwoorden|gesprek|niets|stem|modellen|gaat|weg|gewist|bewaard|opgeslagen|mislukt|gelukt|lukt|lukte|bezig|nieuwe?|vandaag|gisteren|morgen|wachten|ingesteld|gekoppeld|verbonden|opnieuw|terug)(?![\p{L}])/iu;
const SINKS = { toast: [0], undoToast: [0], askConfirm: [0, 1, 2], askInput: [0, 1, 3], askChoice: [0, 1, 2, 3], openSheet: [0, 1], t: [0], tn: [1, 2] };
/* De argumenten van een aanroep (bovenste niveau), met begin en eind in de bron */
function callArgs(src, open){
  const args = []; let d = 0, start = open + 1, i = open + 1, q = null;
  for (; i < src.length; i++) {
    const c = src[i];
    if (q) { if (c === '\\') { i++; continue; } if (c === q) q = null; continue; }
    if (c === '\'' || c === '"' || c === '`') { q = c; continue; }
    if (c === '(' || c === '[' || c === '{') d++;
    else if (c === ')' || c === ']' || c === '}') { if (d === 0) { args.push([start, i]); break; } d--; }
    else if (c === ',' && d === 0) { args.push([start, i]); start = i + 1; }
  }
  return args;
}
/* Geen tekst voor Rene: stukjes HTML-attributen ('" onclick="…', '" alt="">'), alleen opmaak of entiteiten, code */
function junk(k){
  if (/^["'][\s\],)>;]|^[)\]]/.test(k)) return true;
  const bare = k.replace(/<[^>]*>/g, ' ').replace(/&[a-z#0-9]+;/gi, ' ');
  return !hasLetters(bare) || /=["']|\bon[a-z]+=|\bAndroid\.\w+\(/.test(bare);
}
/* Teksten tussen > en < in één letterlijke tekenreeks, en attributen met tekst */
function htmlBits(s){
  const out = [];
  if (!/<[a-z\/!]/i.test(s)) return out;
  const re = />([^<>]*)</g; let m;
  while ((m = re.exec(s))) { re.lastIndex = m.index + 1; const v = norm(m[1]); if (hasLetters(v) && !/\$\{…\}/.test(v)) out.push(v); }
  const ra = /\b(?:placeholder|aria-label|title|alt)="([^"]*)"/g;
  while ((m = ra.exec(s))) { const v = norm(m[1]); if (hasLetters(v) && !/\$\{…\}/.test(v)) out.push(v); }
  return out;
}
function jsFiles(){
  return fs.readdirSync(path.join(SRC, 'script')).filter(f => f.endsWith('.js') && f !== '01-testbrug.js').map(f => ['web/src/script/' + f, rd(path.join(SRC, 'script', f))]);
}
function startScript(){
  // Alleen de scripts, met de rest als lege regels: dan kloppen de regelnummers met start.html
  const s = rd(path.join(ROOT, 'web', 'start.html'));
  return s.replace(/([\s\S]*?)(<script>[\s\S]*?<\/script>|$)/g, (m, out, scr) => out.replace(/[^\n]/g, '') + (scr ? ' '.repeat(8) + scr.slice(8, -9) + ' '.repeat(9) : ''));
}
/* Sleutels uit JavaScript: { key → [plek] }, en de bewaker: [plek, reden] */
function jsScan(){
  const keys = new Map(), guard = [], loose = new Map(), strs = new Set();
  const add = (k, where, map) => { k = norm(k); if (!k || junk(k)) return; const m = map || keys; if (!m.has(k)) m.set(k, []); m.get(k).push(where); };
  const files = jsFiles().concat([['web/start.html', startScript()]]);
  for (const [name, src] of files) {
    const toks = jsTokens(src), byAt = new Map(toks.map(t => [t.at, t]));
    for (const tk of toks) if (!tk.tpl) strs.add(norm(tk.s));
    // 1. aanroepen van t, tn en de meldingen
    const re = /(^|[^\w$.])(toast|undoToast|askConfirm|askInput|askChoice|openSheet|tn|t)\s*\(/g; let m;
    while ((m = re.exec(src))) {
      const fn = m[2], open = m.index + m[0].length - 1;
      if (/function\s+$/.test(src.slice(Math.max(0, m.index - 12), m.index + m[1].length))) continue; // de definitie zelf
      const tok = toks.find(x => x.at <= m.index && x.end > m.index); if (tok) continue; // in een tekenreeks
      const args = callArgs(src, open), line = lineOf(src, m.index), where = name + ':' + line;
      for (const ai of (name === 'web/start.html' && fn === 'openSheet' ? [1] : SINKS[fn])) {
        const a = args[ai]; if (!a) continue;
        const raw = src.slice(a[0], a[1]).trim();
        const lits = toks.filter(x => x.at >= a[0] && x.end <= a[1]);
        if (!lits.length) continue;
        if (/^(['"])(?:(?!\1)[^\\\n]|\\.)*\1$/.test(raw) || (/^`[^`]*`$/.test(raw) && !lits[0].tpl)) { add(lits[0].s, where); continue; }
        // Keuze tussen vaste teksten (a ? 'x' : 'y'): elke tekst is een sleutel
        const bare = raw.replace(/(['"])(?:[^\\\n]|\\.)*?\1/g, 'S');
        if (!/\+/.test(bare) && !/`/.test(raw)) { lits.forEach(x => { if (looksDutch(x.s) && !/^\s*t\(/.test(raw)) add(x.s, where); }); if (fn === 't' || fn === 'tn') continue; }
        if ((fn === 't' || fn === 'tn')) { if (lits[0] && raw.startsWith(lits[0].q)) add(lits[0].s, where); continue; }
        // Aan elkaar geplakt met Nederlandse tekst, niet in t(): bewaker
        if (/\+/.test(bare) || (/`/.test(raw) && lits.some(x => x.tpl))) {
          const dutch = lits.filter(x => !inT(src, x.at) && looksDutch(x.s.replace(/\$\{…\}/g, ' ')) && /\p{L}{3}/u.test(x.s));
          if (dutch.length) guard.push([where, fn + '(…) plakt tekst aan elkaar zonder t(): ' + dutch.map(x => JSON.stringify(x.s)).join(' + ')]);
        }
      }
    }
    // 2. textContent = 'tekst' + …  (bewaker) en textContent = 'tekst' (sleutel; de observer vertaalt hem)
    const rt = /\.(textContent|innerText)\s*=(?!=)\s*/g;
    while ((m = rt.exec(src))) {
      const s0 = m.index + m[0].length; let e = s0, d = 0;
      for (; e < src.length; e++) { const c = src[e]; const tk = byAt.get(e); if (tk) { e = tk.end - 1; continue; } if (c === '(' || c === '[' || c === '{') d++; else if (c === ')' || c === ']' || c === '}') { if (!d) break; d--; } else if ((c === ';' || c === '\n') && !d) break; else if (c === ',' && !d) break; }
      const raw = src.slice(s0, e), lits = toks.filter(x => x.at >= s0 && x.end <= e), where = name + ':' + lineOf(src, m.index);
      const bare = raw.replace(/(['"])(?:[^\\\n]|\\.)*?\1/g, 'S');
      if (/^\s*t\(|^\s*tn\(/.test(raw)) continue;
      if (/\+/.test(bare) || lits.some(x => x.tpl)) {
        const dutch = lits.filter(x => !inT(src, x.at) && looksDutch(x.s.replace(/\$\{…\}/g, ' ')) && /\p{L}{3}/u.test(x.s));
        if (dutch.length) guard.push([where, m[1] + ' plakt tekst aan elkaar zonder t(): ' + dutch.map(x => JSON.stringify(x.s)).join(' + ')]);
      } else lits.forEach(x => { if (looksDutch(x.s) && !inT(src, x.at)) add(x.s, where); });
    }
    // 2b. Elke andere Nederlandse tekst die met + aan iets vastgeplakt wordt (in HTML, een variabele, een aria-label …),
    //     zonder t(): de observer kan die niet vertalen, want de tekst en het ingevulde stuk komen in één tekstknoop.
    //     Wat aan een tag grenst ('<b>' + naam + '</b> gaat weg' → losse knopen) is wel goed.
    const seenLine = new Set();
    for (const tk of toks) {
      if (tk.tpl || inT(src, tk.at)) continue;
      const before = /\+\s*$/.test(src.slice(Math.max(0, tk.at - 4), tk.at)), after = /^\s*\+/.test(src.slice(tk.end, tk.end + 4));
      if (!before && !after) continue;
      const frags = [];
      if (before) { const lead = tk.s.split(/[<"]/)[0]; if (!/(uiIcon|icon|Icon|svg\w*)\([^()]*\)\s*\+\s*$/.test(src.slice(Math.max(0, tk.at - 60), tk.at))) frags.push(lead); }
      if (after) { const m2 = /(?:aria-label|title|placeholder|alt)="([^"]*)$/.exec(tk.s); frags.push(m2 ? m2[1] : /[>"]/.test(tk.s) ? tk.s.slice(Math.max(tk.s.lastIndexOf('>'), tk.s.lastIndexOf('"')) + 1) : tk.s); }
      const bad = frags.filter(f => /\p{L}{3}/u.test(f) && looksDutch(f) && DUTCHY.test(f));
      if (!bad.length) continue;
      const line = lineOf(src, tk.at), where = name + ':' + line;
      if (seenLine.has(where) || guard.some(g => g[0] === where)) continue;
      seenLine.add(where); guard.push([where, 'tekst aan elkaar geplakt zonder t(): ' + bad.map(x => JSON.stringify(x)).join(' + ')]);
    }
    // 3. HTML-stukjes in tekenreeksen
    for (const tk of toks) for (const b of htmlBits(tk.s)) if (!inT(src, tk.at)) add(b, name + ':' + lineOf(src, tk.at));
    // Uitzonderingen in de bron zelf: een regel met "taal-vast: reden" in een opmerking, of alles tussen
    // "taal-vast-begin: reden" en "taal-vast-eind" (bijv. Nederlandse zinsdelen die de assistent zelf ontleedt)
    const vast = vastLines(src);
    for (let i = guard.length - 1; i >= 0; i--) { const g = guard[i][0]; if (g.startsWith(name + ':') && vast.has(+g.slice(name.length + 1))) guard.splice(i, 1); }
    // 3b. t( of tn( in een functie met een eigen variabele t of tn: dan roept hij die aan, niet de vertaling
    for (const sh of shadowed(src, toks)) guard.push([name + ':' + sh.line, sh.why]);
    // 4. Losse teksten die op Nederlands lijken (alleen ter info: --lijst --los)
    for (const tk of toks) if (!tk.tpl && looksDutch(tk.s) && !/<[a-z\/]/i.test(tk.s)) add(tk.s, name + ':' + lineOf(src, tk.at), loose);
  }
  return { keys, guard, loose, strs };
}
/* Regels die bewust vast Nederlands zijn (zie hierboven); een opmerking zonder reden telt niet */
function vastLines(src){
  const out = new Set(), lines = src.split('\n'); let open = false;
  lines.forEach((l, i) => {
    if (/taal-vast-begin:\s*\S/.test(l)) open = true;
    if (open || /taal-vast:\s*\S/.test(l)) out.add(i + 1);
    if (/taal-vast-eind/.test(l)) open = false;
  });
  return out;
}
/* Aanroepen van t( / tn( binnen een functie (vanaf de laatste regel die in kolom 0 begint) waarin t of tn zelf een
 * naam is (const t = …, (t) =>, function (a, t)); benadering, maar vangt de gewone gevallen */
function shadowed(src, toks){
  const out = [], inStr = at => toks.some(x => x.at <= at && x.end > at);
  const blank = src.replace(/\/\*[\s\S]*?\*\//g, m => m.replace(/[^\n]/g, ' '));
  const lines = blank.split('\n'); let start = 0, offs = [0];
  for (let i = 0; i < lines.length; i++) offs.push(offs[i] + lines[i].length + 1);
  const decl = n => new RegExp('(\\b(const|let|var)\\s+(?:[^;=\\n]*?[,{\\[]\\s*)?' + n + '\\b(?=\\s*[=,;}\\]])|[(,]\\s*' + n + '\\s*(?=[,)=])|(?:^|[^\\w$.])' + n + '\\s*=>|\\bfor\\s*\\(\\s*(const|let|var)\\s+' + n + '\\b)');
  const dT = decl('t'), dN = decl('tn');
  for (let i = 0; i < lines.length; i++) {
    if (/^\S/.test(lines[i]) && !/^[}\])]/.test(lines[i])) start = i;
    const re = /(^|[^\w$.])(tn|t)\(/g; let m;
    while ((m = re.exec(lines[i]))) {
      const at = offs[i] + m.index + m[1].length; if (inStr(at)) continue;
      if (/function\s+$/.test(lines[i].slice(0, m.index + m[1].length))) continue;
      const body = blank.slice(offs[start], at).replace(/(['"`])(?:[^\\\n]|\\.)*?\1/g, 'S');
      if ((m[2] === 't' ? dT : dN).test(body)) out.push({ line: i + 1, why: m[2] + '() wordt aangeroepen in een functie met een eigen variabele ' + m[2] + ': hernoem die variabele' });
    }
  }
  return out;
}
/* Staat deze tekenreeks direct in t( of tn( ? */
function inT(src, at){
  // Binnenste aanroep waarin de tekenreeks staat (haakjes terugtellen, tekenreeksen overslaan): t( of tn( ?
  let d = 0, q = null;
  for (let i = at - 1, n = 0; i >= 0 && n < 4000; i--, n++) {
    const c = src[i];
    if (q) { if (c === q && src[i - 1] !== '\\') q = null; continue; }
    if (c === '\'' || c === '"' || c === '`') { q = c; continue; }
    if (c === ')' || c === ']' || c === '}') d++;
    else if (c === '(' || c === '[' || c === '{') { if (d === 0) { if (c !== '(') return false; return /(^|[^\w$.])(t|tn)\s*$/.test(src.slice(Math.max(0, i - 4), i)); } d--; }
    else if (c === ';' && d === 0) return false;
  }
  return false;
}

/* ---------- Java: Taal.t(ctx, "…") en Taal.tn(ctx, n, "…", "…") ---------- */
function javaScan(){
  const keys = new Map(), dir = path.join(ROOT, 'app', 'src', 'nl', 'rene', 'tools');
  const add = (k, w) => { k = norm(k); if (!hasLetters(k)) return; if (!keys.has(k)) keys.set(k, []); keys.get(k).push(w); };
  const jstr = '"((?:[^"\\\\\\n]|\\\\.)*)"';
  for (const f of fs.readdirSync(dir).filter(f => f.endsWith('.java'))) {
    const src = rd(path.join(dir, f));
    let m; const r1 = new RegExp('\\bTaal\\.t\\(\\s*[^,()]*(?:\\([^()]*\\))?[^,()]*,\\s*' + jstr, 'g');
    while ((m = r1.exec(src))) add(JSON.parse('"' + m[1] + '"'), 'Java ' + f + ':' + lineOf(src, m.index));
    const r2 = new RegExp('\\bTaal\\.tn\\(\\s*[^,]*,\\s*[^,]*,\\s*' + jstr + '\\s*,\\s*' + jstr, 'g');
    while ((m = r2.exec(src))) { add(JSON.parse('"' + m[1] + '"'), 'Java ' + f + ':' + lineOf(src, m.index)); add(JSON.parse('"' + m[2] + '"'), 'Java ' + f + ':' + lineOf(src, m.index)); }
    // Vaste lijsten met teksten voor Rene: Taal.T("…") markeert een tekst die later met Taal.t(ctx, tekst) vertaald wordt
    const r3 = new RegExp('\\bTaal\\.T\\(\\s*' + jstr, 'g');
    while ((m = r3.exec(src))) add(JSON.parse('"' + m[1] + '"'), 'Java ' + f + ':' + lineOf(src, m.index));
  }
  return keys;
}

/* ---------- vaste HTML, in een browser opgedeeld zoals 02-taal.js het doet ---------- */
const HTML_PARTS = () => {
  const order = rd(path.join(SRC, 'volgorde.txt')).split('\n').map(l => l.trim()).filter(l => l && !l.startsWith('#') && l.endsWith('.html'));
  return order.map(p => ['web/src/' + p, rd(path.join(SRC, p))]).concat([['web/start.html', rd(path.join(ROOT, 'web', 'start.html'))]]);
};
async function htmlScan(){
  const { chromium } = require('playwright');
  const b = await chromium.launch();
  try {
    const pg = await b.newPage();
    await pg.setContent('<!doctype html><html><body></body></html>');
    await pg.addScriptTag({ content: rd(path.join(SRC, 'script', '02-taal.js')).replace(/\nif \(taalId\(\)[\s\S]*$/, '\n') });
    const parts = HTML_PARTS();
    const res = await pg.evaluate(parts => parts.map(([name, html]) => {
      // Alleen de opmaak: scripts eruit (hun teksten telt de JavaScript-scan)
      const doc = new DOMParser().parseFromString(html.replace(/<script\b[\s\S]*?<\/script>/gi, '').replace(/__\w+__/g, ''), 'text/html');
      return [name, taalKeys(doc.documentElement).filter(([k, v]) => !(k === 'tekst' && doc.title && v === doc.title && false))];
    }), parts);
    const keys = new Map();
    for (const [name, list] of res) for (const [, k] of list) { if (!keys.has(k)) keys.set(k, []); keys.get(k).push(name); }
    return keys;
  } finally { await b.close(); }
}

/* Teksten die niet vertaald hoeven: eigennamen, merknamen, eenheden, voorbeelden (in beide talen hetzelfde) */
function sameOk(k){
  return /^[\d\s.,:;%°/+\-–—×x()€$£#@!?…·•|=<>"'’“”‘_*&~^]*$/.test(k) || /^[A-Z0-9][A-Za-z0-9+.\-]*( [A-Z0-9][A-Za-z0-9+.\-]*)?$/.test(k) && TAAL_SAME.has(k);
}
const TAAL_SAME = new Set();
function readDict(){
  const p = path.join(SRC, 'taal', 'en.json');
  return JSON.parse(rd(p));
}
function allowlist(){
  const p = path.join(ROOT, 'tools', 'taal-uitzonderingen.txt');
  if (!fs.existsSync(p)) return [];
  return rd(p).split('\n').map(l => l.trim()).filter(l => l && !l.startsWith('#')).map(l => { const [where, ...why] = l.split(/\s+—\s+|\s+-\s+/); return { where: where.trim(), why: why.join(' — ').trim() }; });
}
async function scan(){
  const js = jsScan(), java = javaScan(), html = await htmlScan();
  const all = new Map();
  for (const m of [html, js.keys, java]) for (const [k, w] of m) { if (!all.has(k)) all.set(k, []); all.get(k).push(...w); }
  return { all, html, js, java };
}
async function check(){
  const { all, js } = await scan(), dict = readDict(), errs = [], warns = [];
  const keys = new Set(Object.keys(dict));
  for (const k of keys) if (k !== norm(k)) errs.push('sleutel niet genormaliseerd (witruimte/zachte afbreekstreep): ' + JSON.stringify(k));
  for (const [k, v] of Object.entries(dict)) {
    const vals = Array.isArray(v) ? v : [v];
    if (Array.isArray(v) && (v.length !== 2 || !/\{n\}/.test(k + v.join('')))) errs.push('enkelvoud/meervoud moet ["één", "meer"] zijn met {n}: ' + JSON.stringify(k));
    for (const e of vals) {
      if (typeof e !== 'string' || !e.trim()) { errs.push('lege vertaling: ' + JSON.stringify(k)); continue; }
      const ph = s => (s.match(/\{\w+\}/g) || []).sort().join();
      if (ph(k) !== ph(e) && !(Array.isArray(v) && ph(k).replace('{n}', '') === ph(e).replace('{n}', ''))) errs.push('plaatshouders verschillen: ' + JSON.stringify(k) + ' → ' + JSON.stringify(e));
      const tags = s => (s.match(/<\/?[a-z]+/gi) || []).map(x => x.toLowerCase()).sort().join();
      if (tags(k) !== tags(e)) errs.push('HTML-opmaak verschilt: ' + JSON.stringify(k) + ' → ' + JSON.stringify(e));
      if (/\bn[e3]p\b/i.test(e) || /\bfake\b/i.test(e)) errs.push('verboden woord in vertaling: ' + JSON.stringify(e));
      if (keys.has(norm(e)) && norm(e) !== k && dict[norm(e)] !== e) errs.push('vertaling is zelf een andere Nederlandse sleutel (heen en weer): ' + JSON.stringify(k) + ' → ' + JSON.stringify(e));
    }
  }
  const missing = [...all.keys()].filter(k => !keys.has(k));
  for (const k of missing) errs.push('geen Engelse vertaling: ' + JSON.stringify(k) + ' (' + all.get(k).slice(0, 3).join(', ') + ')');
  const allow = allowlist();
  const allowed = w => allow.find(a => a.where === w);
  for (const [where, why] of js.guard) { if (!allowed(where)) errs.push(where + ': ' + why); }
  for (const a of allow) { if (!a.why) errs.push('uitzondering zonder reden: ' + a.where); if (!js.guard.some(([w]) => w === a.where)) warns.push('uitzondering is niet meer nodig: ' + a.where); }
  const unused = [...keys].filter(k => !all.has(k) && !js.loose.has(k) && !js.strs.has(k));
  for (const k of unused) warns.push('ongebruikte vertaling: ' + JSON.stringify(k));
  return { errs, warns, total: all.size, translated: all.size - missing.length, dict: keys.size, unused: unused.length, guard: js.guard.length };
}
/* Ongebruikt = nergens als tekst te vinden; een losse tekenreeks ('januari' in een lijst die met t() vertaald wordt) telt als gebruikt */

module.exports = { scan, check, jsScan, javaScan, htmlScan, norm, looksDutch, jsTokens, readDict };

if (require.main === module) {
  (async () => {
    const a = process.argv.slice(2);
    if (a.includes('--lijst') || a.includes('--ontbrekend') || a.includes('--los') || a.includes('--bewaker')) {
      if (a.includes('--bewaker')) { for (const [w, why] of jsScan().guard) console.log(w + ': ' + why); return; }
      const { all, js } = await scan(), dict = readDict();
      const src = a.includes('--los') ? js.loose : all;
      const out = {};
      for (const [k, w] of src) if (!(a.includes('--ontbrekend') || a.includes('--los')) || !(k in dict)) out[k] = [...new Set(w)].slice(0, 4);
      console.log(JSON.stringify(out, null, 1));
      return;
    }
    const r = await check();
    for (const w of r.warns) console.log('waarschuwing: ' + w);
    for (const e of r.errs) console.log('✗ ' + e);
    console.log(`Teksten: ${r.total}, vertaald: ${r.translated}, in het woordenboek: ${r.dict}, ongebruikt: ${r.unused}, bewaker: ${r.guard}`);
    process.exit(r.errs.length ? 1 : 0);
  })().catch(e => { console.error(e); process.exit(2); });
}
