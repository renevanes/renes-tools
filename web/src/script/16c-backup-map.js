/* ---------- Backup-map controleren en opruimen (dubbele mappen en bestanden, achtergebleven tijdelijke bestanden) ---------- */
let bmPoll = null;
function bmState(){ try { return JSON.parse(Android.backupMapState()); } catch(e){ return {}; } }
function bmShow(sel, on){ const e = $(sel); if (e) e.style.display = on ? '' : 'none'; }
/* Samenvatting van een uitkomst, voor de rij in Alles back-uppen en de stand op het scherm */
function bmSum(l){
  if (!l || !l.t) return t('Dubbele mappen en bestanden opruimen');
  const p = l.problems || 0;
  if (l.repair) return l.errors ? t('Opgeruimd {wanneer}, met {n} fouten', { wanneer: fmtD(l.t), n: l.errors }) : t('✓ Opgeruimd {wanneer}', { wanneer: fmtD(l.t) });
  return p ? tn(p, 'Eén punt om op te ruimen', '{n} punten om op te ruimen') : t('✓ In orde · {wanneer}', { wanneer: fmtD(l.t) });
}
function bmRender(){
  const s = bmState(), l = s.last, sum = $('#bk-sum-map');
  if (sum) sum.textContent = s.running ? t('Bezig…') : bmSum(l);
  if (current !== 'bkmap') return;
  const tg = $('#bm-auto'); tg.setAttribute('aria-checked', s.auto !== false ? 'true' : 'false');
  bmShow('#bm-stop', !!s.running); $('#bm-check').disabled = !!s.running;
  if (s.running) {
    $('#bm-state').textContent = t('Bezig met de backup-map… {waar}', { waar: s.current || '' });
    bmShow('#bm-fix', false);
    if (!bmPoll) bmPoll = setInterval(() => { if (current !== 'bkmap') { clearInterval(bmPoll); bmPoll = null; return; } bmRender(); }, 1000);
    return;
  }
  if (bmPoll) { clearInterval(bmPoll); bmPoll = null; }
  if (!s.dest) { $('#bm-state').textContent = t('Kies eerst een backup-map.'); $('#bm-res').innerHTML = ''; bmShow('#bm-fix', false); return; }
  if (!l || !l.t) { $('#bm-state').textContent = t('Nog niet gecontroleerd.'); $('#bm-res').innerHTML = ''; bmShow('#bm-fix', false); return; }
  $('#bm-state').textContent = l.repair
    ? t('Opgeruimd {wanneer}: {mappen} mappen en {bestanden} bestanden nagelopen.', { wanneer: fmtDT(l.t), mappen: fmtNum(l.dirs || 0), bestanden: fmtNum(l.files || 0) })
    : t('Gecontroleerd {wanneer}: {mappen} mappen en {bestanden} bestanden nagelopen.', { wanneer: fmtDT(l.t), mappen: fmtNum(l.dirs || 0), bestanden: fmtNum(l.files || 0) });
  const row = (b, v, cls) => '<div class=bkres><b>' + esc(b) + '</b><span class="' + (cls || '') + '">' + esc(v) + '</span></div>';
  let h = '';
  if (l.repair) {
    h += row(t('Dubbele mappen samengevoegd'), fmtNum(l.merged || 0), l.merged ? 'good' : '');
    h += row(t('Dubbele bestanden weg (zelfde inhoud)'), fmtNum(l.removed || 0), l.removed ? 'good' : '');
    h += row(t('Dubbele bestanden bewaard als naam~datum'), fmtNum(l.renamed || 0), '');
    h += row(t('Tijdelijke bestanden opgeruimd'), fmtNum(l.cleaned || 0), l.cleaned ? 'good' : '');
    if (l.errors) h += row(t('Niet gelukt'), fmtNum(l.errors), 'bad');
  } else {
    h += row(t('Dubbele mappen'), fmtNum(l.dupDirs || 0), l.dupDirs ? 'bad' : 'good');
    h += row(t('Dubbele bestanden'), fmtNum(l.dupFiles || 0), l.dupFiles ? 'bad' : 'good');
    h += row(t('Achtergebleven tijdelijke bestanden'), fmtNum(l.temps || 0), l.temps ? 'bad' : 'good');
  }
  if (l.stopped) h += '<p class="note warn">' + esc(t('Gestopt voordat alles was nagelopen; de volgende keer gaat het verder.')) + '</p>';
  const items = (l.items || []).filter(i => !l.repair || !i.done);
  if (items.length) h += '<p class="note" style="margin-bottom:4px">' + esc(t(l.repair ? 'Bleef staan:' : 'Gevonden:')) + '</p>' +
    items.slice(0, 30).map(i => '<div class=bkres><b translate="no">' + esc(i.path) + '</b><span>' + esc(t(i.what)) + '</span></div>').join('') +
    (items.length > 30 ? '<p class="note">' + esc(t('… en nog {n}', { n: fmtNum(items.length - 30) })) + '</p>' : '');
  if ((l.unknown || []).length) h += '<p class="note">' + esc(t('Mappen die de app niet zelf maakt (blijven staan): {namen}', { namen: l.unknown.join(', ') })) + '</p>';
  $('#bm-res').innerHTML = h;
  const todo = !l.repair && (l.problems || 0) > 0;
  bmShow('#bm-fix', todo);
  if (todo) $('#bm-fix').textContent = tn(l.problems, 'Nu opruimen (1 punt)', 'Nu opruimen ({n} punten)');
}
function bmRun(repair){
  const go = () => { Android.backupMapRun(repair); setTimeout(bmRender, 200); };
  if (repair) askConfirm('Backup-map opruimen?', t('Dubbele mappen worden samengevoegd, dubbele bestanden met dezelfde inhoud worden er één, en achtergebleven tijdelijke bestanden gaan weg. Er gaat nooit iets weg wat nergens anders staat.'), 'Opruimen', go);
  else go();
}
window.onBackupMap = function(r){
  if (r && r.error) toast(r.error);
  else if (r) toast(r.repair ? (r.errors ? t('Opgeruimd, maar niet alles lukte') : t('✓ Backup-map opgeruimd')) : (r.problems ? tn(r.problems, 'Eén punt gevonden', '{n} punten gevonden') : t('✓ Backup-map is in orde')));
  bmRender();
};
function bmAuto(){ const on = $('#bm-auto').getAttribute('aria-checked') !== 'true'; Android.backupMapAuto(on); bmRender(); }
