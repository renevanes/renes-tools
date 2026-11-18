/* ---------- Startscherm aanpassen: tabbladen Tools, Overzicht en Weergave ----------
 * Tools: favorieten en groepen (31-startscherm-groepen-volgorde-verbergen.js).
 * Overzicht: welke regels bovenaan het startscherm staan, in welke volgorde, wanneer de backup-regel komt, en
 * "Sinds gisteravond" met zijn regels. Weergave: groepen, compact, uitleg onder de tegels, welke onderdelen in welke
 * volgorde op het startscherm staan (Overzicht, Recent gebruikt, Vastgezette notities, Tools) en de tip onderaan.
 * Alles staat in rt.home (homeCfg): ov, blok, notes, tip, sub, recentN; homeMigrate neemt onbekende velden gewoon mee. */
function HE_OV(){ return [ // id, naam, uitleg, pictogram
  ['backup', t('Backup'), t('Wanneer de laatste backup was en of er iets misging'), 'backup'],
  ['wekker', t('Wekker'), t('De volgende wekker van de radio'), 'clock'],
  ['herinner', t('Herinneringen'), t('Herinneringen bij notities voor vandaag'), 'bell'],
  ['redial', t('Auto redial'), t('Een geplande belpoging in de komende week'), 'phone'],
  ['radio', t('Radio'), t('De zender die nu speelt'), 'radio'],
  ['zelftest', t('Zelftest'), t('Problemen die de zelftest vond'), 'shield']
]; }
function HE_NACHT(){ return [
  ['backup', t('Backup van vannacht'), 'backup'],
  ['gemist', t('Gemiste oproepen'), 'phone'],
  ['auto', t('Automatiseringen'), 'automation'],
  ['accu', t('Beperkingen door Android'), 'battery'],
  ['controle', t('Backup-controle'), 'shield']
]; }
function HE_BLOK(){ return [
  ['ov', t('Overzicht'), t('Stand van backup, wekker, herinneringen en Sinds gisteravond'), 'info'],
  ['recent', t('Recent gebruikt'), t('De tools die je het laatst opende'), 'clock'],
  ['notes', t('Vastgezette notities'), t('Notities die je op het startscherm vastzette'), 'notes'],
  ['tools', t('Tools'), t('Je favorieten en groepen'), 'grid']
]; }
// Functies, geen const: renderHome (eerder geladen) gebruikt ze al bij het starten
function HE_BK_DAGEN(){ return [1, 2, 3, 7, 14]; }
function HE_RECENT_N(){ return [2, 4, 6, 8]; } // even: geen gat in het raster
function heIds(l){ return l.map(x => x[0]); }
/** Een volgorde netjes: alleen bekende id's, geen dubbele, ontbrekende achteraan. */
function heVolg(v, ids){ const l = (Array.isArray(v) ? v : []).filter((x, i, a) => ids.includes(x) && a.indexOf(x) === i); ids.forEach(x => { if (!l.includes(x)) l.push(x); }); return l; }
/** Instellingen van het overzicht bovenaan, aangevuld met de standaard. */
function homeOv(c){
  c = c || homeCfg(); const o = c.ov && typeof c.ov === 'object' ? c.ov : {}, ov = heIds(HE_OV()), na = heIds(HE_NACHT());
  return { volg: heVolg(o.volg, ov), uit: (Array.isArray(o.uit) ? o.uit : []).filter(x => ov.includes(x)), bk: o.bk === 'probleem' ? 'probleem' : 'altijd',
    bkDagen: HE_BK_DAGEN().includes(o.bkDagen) ? o.bkDagen : 3, nacht: o.nacht !== false, nachtUit: (Array.isArray(o.nachtUit) ? o.nachtUit : []).filter(x => na.includes(x)) };
}
function homeBlok(c){ c = c || homeCfg(); return heVolg(c.blok, heIds(HE_BLOK())); }
function homeRecentN(c){ c = c || homeCfg(); return HE_RECENT_N().includes(c.recentN) ? c.recentN : 4; }
/** Wat het startscherm toont, in de gekozen volgorde (aangeroepen vanuit renderHome). */
function homeBlokPas(c){
  const main = document.querySelector('#s-home main'), tip = $('#home-tip'); if (!main || !tip) return;
  const el = { ov: $('#home-ov'), recent: $('#home-recent'), notes: $('#home-notes'), tools: $('#home-groups') }, b = homeBlok(c);
  // Eenmalige kaarten (Nieuwe indeling, Aan de slag): na het overzicht als dat bovenaan staat (zoals altijd), anders bovenaan
  const kaarten = ['home-new', 'home-start'].map(id => document.getElementById(id)).filter(Boolean);
  const wil = (b[0] === 'ov' ? [el.ov].concat(kaarten, b.slice(1).map(k => el[k])) : kaarten.concat(b.map(k => el[k]))).filter(Boolean);
  const nu = [...main.children].filter(x => wil.includes(x));
  if (nu.length !== wil.length || nu.some((x, i) => x !== wil[i]) || wil[wil.length - 1].nextElementSibling !== tip) wil.forEach(x => main.insertBefore(x, tip)); // alleen verplaatsen als het nodig is
  tip.hidden = c.tip === false;
  $('#home-groups').classList.toggle('kaal', c.sub === false);
}

/* ---------- Tabbladen ---------- */
var heTabNu = 'tools'; // var: kan al gelezen worden voordat dit bestand geladen is
function heTab(tab){
  heTabNu = ['tools', 'ov', 'weer'].includes(tab) && !(tab === 'ov' && isPlay()) ? tab : 'tools';
  { const ov = $('#he-tab-ov'), l = ov && ov.parentElement; if (ov) { ov.hidden = isPlay(); l.classList.toggle('two', isPlay()); } } // de Play-versie heeft geen overzicht bovenaan
  document.querySelectorAll('#s-homeedit .pctabs [role=tab]').forEach(b => { const on = b.dataset.tab === heTabNu; b.setAttribute('aria-selected', on ? 'true' : 'false'); b.tabIndex = on ? 0 : -1; });
  ['tools', 'ov', 'weer'].forEach(k => { const p = $('#he-p-' + k); if (p) p.hidden = k !== heTabNu; });
  if (typeof heDragCancel === 'function' && heTabNu !== 'tools') heDragCancel();
}
/** Vanaf een ander scherm: altijd op Tools beginnen. */
function heOpen(){ heTab('tools'); enterHomeEdit(); }
(function(){ const l = document.querySelector('#s-homeedit .pctabs'); if (!l) return;
  l.addEventListener('keydown', e => { // pijltjes tussen de tabbladen
    const tabs = [...l.querySelectorAll('[role=tab]')], i = tabs.findIndex(b => b.dataset.tab === heTabNu);
    const j = e.key === 'ArrowRight' ? i + 1 : e.key === 'ArrowLeft' ? i - 1 : e.key === 'Home' ? 0 : e.key === 'End' ? tabs.length - 1 : -2;
    if (j === -2) return; e.preventDefault(); const b = tabs[(j + tabs.length) % tabs.length]; heTab(b.dataset.tab); b.focus();
  });
})();

/* ---------- Bewaren ---------- */
function heOvSet(f){ const c = homeCfg(), o = homeOv(c); f(o); c.ov = o; homeSave(c); if (current === 'homeedit') enterHomeEdit(); }
function heOvAan(id, aan){ heOvSet(o => { o.uit = o.uit.filter(x => x !== id); if (!aan) o.uit.push(id); }); heFocus('#he-ov-list [data-id="' + id + '"] .tg'); }
function heOvMove(id, dir){ heOvSet(o => { const i = o.volg.indexOf(id), j = i + dir; if (i < 0 || j < 0 || j >= o.volg.length) return; o.volg.splice(i, 1); o.volg.splice(j, 0, id); }); heFocus('#he-ov-list [data-id="' + id + '"] .mv[data-dir="' + dir + '"]'); }
function heNachtAan(id, aan){ heOvSet(o => { o.nachtUit = o.nachtUit.filter(x => x !== id); if (!aan) o.nachtUit.push(id); }); heFocus('#he-nacht-list [data-id="' + id + '"] .tg'); }
function heOvStandaard(){ askConfirm(t('Overzicht standaard?'), t('Alle regels gaan weer aan, in de standaardvolgorde.'), t('Herstellen'), () => { const c = homeCfg(); delete c.ov; c.dash = true; homeSave(c); enterHomeEdit(); toast(t('Overzicht hersteld')); }); }
function heBlokMove(id, dir){ const c = homeCfg(), l = homeBlok(c), i = l.indexOf(id), j = i + dir; if (i < 0 || j < 0 || j >= l.length) return; l.splice(i, 1); l.splice(j, 0, id); c.blok = l; homeSave(c); enterHomeEdit(); heFocus('#he-blok-list [data-id="' + id + '"] .mv[data-dir="' + dir + '"]'); }
function heBlokStandaard(){ const c = homeCfg(); delete c.blok; homeSave(c); enterHomeEdit(); toast(t('Standaardvolgorde terug')); }
function heFocus(sel){ const el = document.querySelector('#s-homeedit ' + sel); if (el && !el.disabled) el.focus(); else { const r = el && el.closest('.herow'), alt = r && r.querySelector('.mv:not(:disabled), .tg'); if (alt) alt.focus(); } }
function heRecentN(n){ const c = homeCfg(); c.recentN = HE_RECENT_N().includes(+n) ? +n : 4; homeSave(c); enterHomeEdit(); }

/* ---------- Tekenen ---------- */
function heSwitch(aan, label, data){ return '<button class="tg' + (aan ? ' on' : '') + '" role="switch" aria-checked="' + (aan ? 'true' : 'false') + '" aria-label="' + esc(label) + '"' + (data || '') + '></button>'; }
function heMv(id, dir, uit, naam){ return '<button class="mv" data-id="' + id + '" data-dir="' + dir + '"' + (uit ? ' disabled' : '') + ' aria-label="' + esc(dir < 0 ? t('{naam} omhoog', { naam }) : t('{naam} omlaag', { naam })) + '">' + (dir < 0 ? '↑' : '↓') + '</button>'; }
function heOvRender(c){
  const o = homeOv(c), def = HE_OV(), n = o.volg.length;
  $('#he-dash-stand').textContent = c.dash === false ? t('Uit: geen regels bovenaan (Sinds gisteravond stel je hieronder apart in)') : tn(n - o.uit.length, 'Eén regel aan', '{n} regels aan');
  $('#he-ov-list').innerHTML = o.volg.map((id, i) => { const d = def.find(x => x[0] === id), aan = !o.uit.includes(id);
    return '<div class="herow heov' + (aan ? '' : ' off') + '" data-id="' + id + '"><span class="stic">' + uiIcon(d[3]) + '</span><span class="nm">' + esc(d[1]) + '<small>' + esc(d[2]) + '</small></span>'
      + heMv(id, -1, i === 0, d[1]) + heMv(id, 1, i === n - 1, d[1]) + heSwitch(aan, t('{naam} tonen', { naam: d[1] }), ' data-id="' + id + '"') + '</div>'; }).join('');
  $('#he-ov-list').classList.toggle('uit', c.dash === false);
  $('#he-ov-bk').value = o.bk; $('#he-ov-bkdagen').value = String(o.bkDagen);
  const nacht = $('#he-nacht'); nacht.classList.toggle('on', o.nacht); nacht.setAttribute('aria-checked', o.nacht ? 'true' : 'false');
  $('#he-nacht-list').innerHTML = HE_NACHT().map(d => { const aan = !o.nachtUit.includes(d[0]);
    return '<div class="herow heov' + (aan ? '' : ' off') + '" data-id="' + d[0] + '"><span class="stic">' + uiIcon(d[2]) + '</span><span class="nm">' + esc(d[1]) + '</span>' + heSwitch(aan, t('{naam} tonen', { naam: d[1] }), ' data-id="' + d[0] + '"') + '</div>'; }).join('');
  $('#he-nacht-list').hidden = !o.nacht;
}
function heWeerRender(c){
  [['grouped', !!c.grouped], ['compact', !!c.compact], ['sub', c.sub !== false], ['tip', c.tip !== false]].forEach(([k, on]) => { const b = $('#he-' + k); b.classList.toggle('on', on); b.setAttribute('aria-checked', on ? 'true' : 'false'); });
  const l = homeBlok(c).filter(id => !(id === 'ov' && isPlay())), def = HE_BLOK(), n = l.length; // de Play-versie heeft geen overzicht
  $('#he-blok-list').innerHTML = l.map((id, i) => { const d = def.find(x => x[0] === id);
    const sw = id === 'recent' ? heSwitch(c.recent !== false, t('Recent gebruikt'), ' id="he-recent"') : id === 'notes' ? heSwitch(c.notes !== false, t('Vastgezette notities'), ' id="he-notes"') : '';
    const sub = id === 'ov' ? (c.dash === false && !homeOv(c).nacht ? t('Uit; aanzetten bij Overzicht') : t('Wat erin staat, stel je in bij Overzicht')) : id === 'tools' ? t('Staat altijd op het startscherm') : d[2];
    return '<div class="herow heov" data-id="' + id + '"><span class="stic">' + uiIcon(d[3]) + '</span><span class="nm">' + esc(d[1]) + '<small>' + esc(sub) + '</small></span>' + heMv(id, -1, i === 0, d[1]) + heMv(id, 1, i === n - 1, d[1]) + sw + '</div>'; }).join('');
  $('#he-recentn').value = String(homeRecentN(c)); $('#he-recentn-rij').hidden = c.recent === false;
}
/* Eén luisteraar per lijst: knoppen worden telkens opnieuw getekend */
(function(){
  const op = (sel, f) => { const el = document.querySelector(sel); if (el) el.addEventListener('click', e => { const b = e.target.closest && e.target.closest('button'); if (b && !b.disabled) f(b); }); };
  op('#he-ov-list', b => b.classList.contains('tg') ? heOvAan(b.dataset.id, !b.classList.contains('on')) : b.dataset.dir && heOvMove(b.dataset.id, +b.dataset.dir));
  op('#he-nacht-list', b => { if (b.classList.contains('tg')) heNachtAan(b.dataset.id, !b.classList.contains('on')); });
  op('#he-blok-list', b => { if (b.id === 'he-recent') { heRecent(!b.classList.contains('on')); heFocus('#he-recent'); }
    else if (b.id === 'he-notes') { heSet('notes', !b.classList.contains('on')); heFocus('#he-notes'); }
    else if (b.dataset.dir) heBlokMove(b.dataset.id, +b.dataset.dir); });
})();
