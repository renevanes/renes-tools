/* Bankzaken: vragen aan de lokale AI. De AI krijgt alleen een samenvatting met cijfers van de gekozen periode (en bij
 * een vraag over een winkel of categorie de bijbehorende boekingen), draait op de telefoon zelf (LocalAi.privateChat),
 * onthoudt niets en het gesprek wordt nergens bewaard. Antwoorden worden als platte tekst getoond. */

function bkAiRender(){
  const ai = (bk.st && bk.st.ai) || {};
  const ok = ai.kan && ai.klaar;
  $('#bk-ai-model').textContent = !ai.kan ? 'De lokale AI werkt niet op deze telefoon (er is een 64-bits ARM-processor nodig). De rapportages werken wel gewoon.'
    : !ai.klaar ? 'Download eerst een taalmodel (Instellingen → Taalmodel). Daarna kun je hier vragen stellen over je uitgaven; de AI draait op deze telefoon en onthoudt niets.'
    : 'Model: ' + (ai.model || 'lokaal') + '. De AI draait op deze telefoon, krijgt alleen een samenvatting van de gekozen periode, onthoudt niets en bewaart het gesprek niet.';
  for (const id of ['#bk-ai-stuur', '#bk-ai-sam']) $(id).disabled = !ok || bk.aiBezig;
  document.querySelectorAll('#bk-ai-chips button').forEach(b => { b.disabled = !ok || bk.aiBezig; });
  $('#bk-ai-stop').hidden = !bk.aiBezig;
}

/** De cijfers voor de AI: totalen, vergelijking, categorieën, tegenpartijen, maanden, vaste lasten, grootste uitgaven. */
function bkFeiten(vraag){
  const [van, tot, label] = bkPeriodeVan(), lijst = bkSelectie(van, tot), tt = bkTotalen(lijst);
  const [pv, pt] = bkVorige(van, tot), v = bkTotalen(bkSelectie(pv, pt));
  const e = c => bkEuro(c);
  const r = [];
  r.push('Periode: ' + label + ' (' + van + ' t/m ' + tot + ')' + (bkRekening() !== 'alle' ? ', alleen rekening ' + bkRekNaam(bkRekening()) : ', alle rekeningen') + '. Vandaag: ' + bkVandaag() + '.');
  r.push('Inkomsten ' + e(tt.in) + ', uitgaven ' + e(tt.uit) + ', gespaard/belegd ' + e(tt.sparen) + ', over ' + bkEuro(tt.netto, true) + ' (' + tt.n + ' boekingen).');
  r.push('Vorige periode (' + pv + ' t/m ' + pt + '): inkomsten ' + e(v.in) + ', uitgaven ' + e(v.uit) + ', gespaard ' + e(v.sparen) + '.');
  const cats = [...t.cats.entries()].filter(([, x]) => x.soort === 'uit').sort((a, b) => a[1].som - b[1].som).slice(0, 16);
  if (cats.length) r.push('Uitgaven per categorie (nu | vorige periode):\n' + cats.map(([c, x]) => '- ' + c + ': ' + e(-x.som) + ' (' + x.n + '×) | ' + e(v.cats.get(c) ? -v.cats.get(c).som : 0)).join('\n'));
  const inc = [...t.cats.entries()].filter(([, x]) => x.soort === 'in' && x.som > 0);
  if (inc.length) r.push('Inkomsten per categorie:\n' + inc.map(([c, x]) => '- ' + c + ': ' + e(x.som)).join('\n'));
  const tp = bkTegenpartijen(lijst).slice(0, 12);
  if (tp.length) r.push('Grootste ontvangers:\n' + tp.map(x => '- ' + bkKort(x.naam) + ' (' + x.cat + '): ' + e(x.som) + ', ' + x.n + '×').join('\n'));
  const pm = bkPerMaand(lijst).slice(-12);
  if (pm.length > 1) r.push('Per maand (inkomsten / uitgaven / gespaard):\n' + pm.map(([m, x]) => '- ' + m + ': ' + e(x.in) + ' / ' + e(x.uit) + ' / ' + e(x.sparen)).join('\n'));
  const vast = bkTerugkerend().slice(0, 15);
  if (vast.length) r.push('Terugkerende betalingen (vaste lasten, abonnementen):\n' + vast.map(x => '- ' + bkKort(x.naam) + ' (' + x.cat + '): ' + e(x.bedrag) + ' ' + x.soort + (x.gestopt ? ', mogelijk gestopt, laatst ' + x.laatst : '')).join('\n'));
  const groot = lijst.filter(x => x.a < 0 && bkCatSoort(bkCat(x)) === 'uit').sort((a, b) => a.a - b.a).slice(0, 8);
  if (groot.length) r.push('Grootste uitgaven:\n' + groot.map(x => '- ' + x.d + ' ' + bkKort(x.cp || x.ci) + ' (' + bkCat(x) + '): ' + e(x.a)).join('\n'));
  // Gaat de vraag over een bepaalde winkel, naam of categorie: die boekingen erbij
  if (vraag) {
    const w = vraag.toLowerCase().split(/[^a-z0-9À-ɏ&]+/).filter(x => x.length >= 4 && !BK_STOP.has(x));
    if (w.length) {
      const hits = lijst.filter(x => { const s = ((x.cp || '') + ' ' + (x.ds || '') + ' ' + bkCat(x)).toLowerCase(); return w.some(k => s.includes(k)); });
      if (hits.length) {
        let som = 0; for (const x of hits) som += x.a;
        r.push('Boekingen die bij de vraag passen (' + hits.length + ', samen ' + bkEuro(som, true) + '):\n' + hits.slice(0, 40).map(x => '- ' + x.d + ' ' + bkKort(x.cp || x.ci) + ' (' + bkCat(x) + '): ' + bkEuro(x.a, true)).join('\n') + (hits.length > 40 ? '\n(… en nog ' + (hits.length - 40) + ' meer)' : ''));
      }
    }
  }
  return r.join('\n\n').slice(0, 9000);
}
const BK_STOP = new Set(['hoeveel', 'heb', 'waar', 'mijn', 'deze', 'maand', 'vorige', 'periode', 'uitgegeven', 'uitgaven', 'inkomsten', 'geld', 'aan', 'welke', 'wat', 'waarom', 'zijn', 'kost', 'kosten', 'meeste', 'besparen', 'hoe', 'kan', 'ik', 'het', 'een', 'van', 'voor', 'met', 'bij', 'per', 'jaar', 'week', 'totaal', 'veranderd', 'opzichte', 'vaste', 'lasten', 'abonnementen', 'makkelijkst', 'heeft', 'zijn', 'waren', 'gaat', 'mijn', 'allemaal']);
/** Namen van buiten (afschriften) ingekort en zonder regeleinden: het blijven gegevens in de lijst. */
const bkKort = s => String(s || 'onbekend').replace(/[\r\n]+/g, ' ').slice(0, 50);

function bkVraag(vraag){
  if (bk.aiBezig || !bk.store) return;
  const q = String(vraag || $('#bk-ai-in').value || '').trim().slice(0, 600);
  if (!q) { $('#bk-ai-in').focus(); return; }
  if (vraag) $('#bk-ai-in').value = q;
  bkAiStart('vraag', 'Mijn vraag: ' + q + '\n\nMijn cijfers:\n' + bkFeiten(q));
}
function bkSamenvatting(){
  if (bk.aiBezig || !bk.store) return;
  bkAiStart('samenvatting', 'Maak een analyse van mijn geldzaken in deze periode.\n\nMijn cijfers:\n' + bkFeiten(''));
}
function bkAiStart(mode, inhoud){
  bk.aiTekst = ''; bk.aiDoel = mode;
  $('#bk-ai-uit-kaart').hidden = false; $('#bk-ai-uit').textContent = ''; $('#bk-ai-status').textContent = 'Nadenken…';
  const r = Android.bankAi(JSON.stringify({ mode, messages: [{ role: 'user', content: inhoud }] }));
  if (r) { $('#bk-ai-status').textContent = r; return; }
  bk.aiBezig = true; bkAiRender();
}
window.onBankAiStatus = function(s){ if (bk.aiDoel === 'categorie') { $('#bk-ai-cat-uit').textContent = s || 'Bezig…'; return; } const e = $('#bk-ai-status'); if (e) e.textContent = s || ''; };
window.onBankAiToken = function(tt){
  if (!bk.store) return;
  bk.aiTekst += String(tt || '');
  if (bk.aiDoel === 'categorie') { $('#bk-ai-cat-uit').textContent = 'Bezig… (' + bk.aiTekst.split('\n').length + ' regels)'; return; }
  $('#bk-ai-uit').textContent = bkSchoon(bk.aiTekst); // altijd als tekst, nooit als HTML
};
window.onBankAiDone = function(info){
  bk.aiBezig = false;
  if (!bk.store) return;
  if (bk.aiDoel === 'categorie') { bkVoorstellen(bk.aiTekst); bkAiRender(); return; }
  $('#bk-ai-status').textContent = info && info.cancelled ? 'Gestopt.' : '';
  $('#bk-ai-uit').textContent = bkSchoon(bk.aiTekst);
  bkAiRender();
};
window.onBankAiError = function(m){
  bk.aiBezig = false;
  if (!bk.store) return;
  if (bk.aiDoel === 'categorie') $('#bk-ai-cat-uit').textContent = String(m || 'Er ging iets mis');
  else $('#bk-ai-status').textContent = String(m || 'Er ging iets mis');
  bkAiRender();
};
/** Denkstappen (<think>) en opmaaktekens weg; de rest blijft platte tekst. */
const bkSchoon = s => String(s).replace(/<think>[\s\S]*?(<\/think>|$)/g, '').replace(/\*\*(.+?)\*\*/g, '$1').replace(/^#{1,4}\s*/gm, '').trim();

// ---------- categorieën laten voorstellen ----------
function bkAiCategoriseren(){
  if (bk.aiBezig || !bk.store) return;
  const ai = (bk.st && bk.st.ai) || {};
  if (!ai.kan || !ai.klaar) { toast('Download eerst een taalmodel (Instellingen → Taalmodel)'); return; }
  const groep = new Map();
  for (const tt of bk.store.tx) {
    const c = bkCat(tt); if (c !== 'Overig' && c !== 'Overige inkomsten') continue;
    const k = bkSleutel(tt); if (!k || k === '?' || k.length < 2) continue;
    const x = groep.get(k) || { k, naam: tt.cp || tt.ci, n: 0, som: 0, plus: 0 }; x.n++; x.som += tt.a; if (tt.a > 0) x.plus++; groep.set(k, x);
  }
  const lijst = [...groep.values()].sort((a, b) => b.n - a.n || a.som - b.som).slice(0, 30);
  if (!lijst.length) { toast('Er is niets meer in te delen'); return; }
  bk.voorstellen = { lijst, keuze: [] };
  const cats = bkAlleCats().filter(c => c !== 'Eigen rekeningen');
  const inhoud = 'Categorieën: ' + cats.join(', ') + '\n\nNamen:\n' + lijst.map((x, i) => (i + 1) + ': ' + bkKort(x.naam) + ' (' + x.n + '×, ' + (x.plus > x.n / 2 ? 'bijschrijving' : 'afschrijving') + ', gemiddeld ' + bkEuro(Math.round(x.som / x.n)) + ')').join('\n');
  bk.aiTekst = ''; bk.aiDoel = 'categorie';
  $('#bk-ai-cat-uit').textContent = 'Bezig…';
  const r = Android.bankAi(JSON.stringify({ mode: 'categorie', messages: [{ role: 'user', content: inhoud }] }));
  if (r) { $('#bk-ai-cat-uit').textContent = r; return; }
  bk.aiBezig = true; bkAiRender();
}
/** Het antwoord ("3: Boodschappen") lezen; alleen bestaande categorieën tellen. Jij kiest wat je overneemt. */
function bkVoorstellen(tekst){
  const v = bk.voorstellen; if (!v) return;
  const cats = bkAlleCats().filter(c => c !== 'Eigen rekeningen'), lower = new Map(cats.map(c => [c.toLowerCase(), c]));
  const keuze = [];
  for (const regel of bkSchoon(tekst).split('\n')) {
    const m = regel.match(/^\s*(\d{1,2})\s*[:.)\-–]\s*(.+?)\s*$/);
    if (!m) continue;
    const x = v.lijst[Number(m[1]) - 1], c = lower.get(m[2].replace(/[.*"“”]/g, '').trim().toLowerCase());
    if (x && c && c !== 'Overig' && c !== 'Overige inkomsten' && !keuze.some(k => k.x === x)) keuze.push({ x, c });
  }
  v.keuze = keuze;
  const box = $('#bk-ai-cat-uit');
  if (!keuze.length) { box.textContent = 'De AI kwam niet met bruikbare voorstellen. Probeer het nog eens of deel zelf in vanuit een boeking.'; return; }
  box.innerHTML = '<p class="note">Kies per naam of je het voorstel overneemt. Er komt dan een regel bij (te zien en te verwijderen onder Mijn regels).</p>'
    + keuze.map((k, i) => '<label class="bk-voorstel"><input type="checkbox" checked data-i="' + i + '"><span>' + esc(bkKort(k.x.naam)) + ' <small class="note">(' + k.x.n + '×)</small></span><select class="sel" data-i="' + i + '" aria-label="Categorie voor ' + esc(bkKort(k.x.naam)) + '">'
      + cats.map(c => '<option' + (c === k.c ? ' selected' : '') + '>' + esc(c) + '</option>').join('') + '</select></label>').join('')
    + '<button class="big" style="margin-top:10px" onclick="bkVoorstellenOvernemen()">Overnemen</button>';
}
function bkVoorstellenOvernemen(){
  const v = bk.voorstellen; if (!v || !bk.store) return;
  let n = 0;
  document.querySelectorAll('#bk-ai-cat-uit input[type=checkbox]').forEach(cb => {
    if (!cb.checked) return;
    const i = Number(cb.dataset.i), k = v.keuze[i], sel = document.querySelector('#bk-ai-cat-uit select[data-i="' + i + '"]');
    if (!k) return;
    const c = sel ? sel.value : k.c;
    if (!bkAlleCats().includes(c)) return;
    const tt = k.x.plus > k.x.n / 2 ? '+' : '-';
    bk.store.rules = bk.store.rules.filter(g => !(g.p === k.x.k && g.t === tt));
    bk.store.rules.push({ p: k.x.k, cat: c, t: tt });
    n++;
  });
  bk.voorstellen = null;
  bkHerbereken();
  if (bkSave()) toast(n + (n === 1 ? ' regel' : ' regels') + ' toegevoegd');
  $('#bk-ai-cat-uit').replaceChildren();
  bkRender();
}
