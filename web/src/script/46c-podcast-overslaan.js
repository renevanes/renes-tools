/* ---------- Podcasts: reclame, begin en einde overslaan (PodcastOverslaan.java) ----------
 * Reclame: hoofdstukken uit de feed (Podcasting 2.0) die reclame of sponsor heten. Begin en einde: per podcast een
 * vast aantal seconden. De speler meldt wat hij oversloeg (pcSt.skip); bij reclame en begin met Terug. */
const PK_SEC = [0, 5, 10, 15, 20, 30, 45, 60, 90, 120, 180, 300];
let pkPod = null, pkGezien = 0;

function pkInst(){ return typeof Android.podOverslaan === 'function' ? rdJson(Android.podOverslaan(), {}) : null; }

/** "45 seconden", "1 minuut", "1 min 30 s"; 0 = Niet. */
function pkSecTxt(s){
  s = +s || 0;
  if (!s) return t('Niet');
  if (s < 60) return tn(s, '{n} seconde', '{n} seconden');
  if (s % 60 === 0) return tn(s / 60, '{n} minuut', '{n} minuten');
  return t('{m} min {s} s', { m: Math.floor(s / 60), s: s % 60 });
}

/* Het scherm Overslaan van één podcast (vanuit ⋯ op de podcast) */
function pkOpen(){
  if (!pdPod || !pkInst()) return;
  pkPod = { id: pdPod.id, title: pdPod.title || '' };
  show('podskip');
}
function enterPodSkip(){ pkRender(); }
function pkRender(){
  const inst = pkInst() || {}, eigen = (inst.per || {})[pkPod && pkPod.id] || {}, std = inst.reclame !== false;
  $('#pk-pod').textContent = pkPod ? pkPod.title : '';
  const r = $('#pk-reclame');
  r.options[0].textContent = std ? t('Zoals bij alle podcasts (aan)') : t('Zoals bij alle podcasts (uit)');
  r.value = 'reclame' in eigen ? (eigen.reclame ? '1' : '0') : '';
  for (const [id, v] of [['#pk-intro', eigen.intro || 0], ['#pk-outro', eigen.outro || 0]]) {
    const sel = $(id), waarden = PK_SEC.includes(v) ? PK_SEC : PK_SEC.concat([v]).sort((a, b) => a - b);
    sel.innerHTML = waarden.map(s => '<option value="' + s + '">' + esc(pkSecTxt(s)) + '</option>').join('');
    sel.value = String(v);
  }
  // Geeft de feed hoofdstukken mee? Alleen dan kan reclame herkend worden
  const items = (pdFeed && pdFeed.pod && pkPod && pdFeed.pod.id === pkPod.id && pdFeed.items) || [];
  const met = items.filter(e => e.ch).length;
  $('#pk-ch').textContent = !items.length ? '' : met
    ? t('Deze podcast geeft bij {n} van de {totaal} afleveringen hoofdstukken mee: daaraan herkent de speler reclame.', { n: met, totaal: items.length })
    : t('Deze podcast geeft geen hoofdstukken mee, dus reclame herkennen lukt hier (nog) niet. Begin en einde overslaan werkt wel.');
}
function pkSave(){
  const inst = pkInst(); if (!inst || !pkPod || !pkPod.id) return;
  const per = inst.per || {}, e = {};
  const r = $('#pk-reclame').value;
  if (r) e.reclame = r === '1';
  const intro = +$('#pk-intro').value || 0, outro = +$('#pk-outro').value || 0;
  if (intro) e.intro = intro;
  if (outro) e.outro = outro;
  if (Object.keys(e).length) per[pkPod.id] = e; else delete per[pkPod.id];
  inst.per = per;
  const fout = Android.podOverslaanZet(JSON.stringify(inst));
  if (fout) toast(t(fout));
  pkRender();
}

/* Algemeen (Instellingen van Podcasts) */
function psOvRender(){
  const inst = pkInst(), kaart = $('#ps-ov');
  if (!kaart) return;
  kaart.hidden = !inst;
  if (!inst) return;
  const b = $('#ps-reclame'), on = inst.reclame !== false;
  b.classList.toggle('on', on); b.setAttribute('aria-checked', on ? 'true' : 'false');
  const w = $('#ps-woorden');
  if (document.activeElement !== w) w.value = inst.woorden || '';
}
function psOvZet(wijzig){
  const inst = pkInst(); if (!inst) return;
  wijzig(inst);
  const fout = Android.podOverslaanZet(JSON.stringify(inst));
  if (fout) toast(t(fout));
  psOvRender();
}
function psReclame(){ psOvZet(i => { i.reclame = i.reclame === false; }); }
function psWoorden(){ const v = $('#ps-woorden').value; psOvZet(i => { i.woorden = String(v || '').slice(0, 300); }); }

/* De melding als de speler iets oversloeg (vanuit pcPoll). Alleen nieuwe, en niet van lang geleden (de app was weg). */
function pkMeld(st){
  const sk = st && st.skip;
  if (!sk || !(+sk.t > pkGezien)) return;
  pkGezien = +sk.t;
  if (Date.now() - sk.t > 15000) return;
  const duur = pkSecTxt(Math.max(1, Math.round((sk.tot - sk.van) / 1000)));
  const tekst = sk.waarom === 'intro' ? t('Begin overgeslagen ({duur})', { duur })
    : sk.waarom === 'outro' ? t('Einde overgeslagen ({duur})', { duur })
    : t('Reclame overgeslagen ({duur})', { duur });
  // Terug alleen bij wat nu speelt (na het einde speelt er al iets anders)
  if (sk.waarom !== 'outro' && st.ep && st.ep.key === sk.key) undoToast(tekst, () => Android.podOverslaanTerug(+sk.van || 0), t('Terug'));
  else toast(tekst);
}
