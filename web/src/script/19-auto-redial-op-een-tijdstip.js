/* ---------- Auto redial op een tijdstip ---------- */
let rpDays = 0;
document.querySelectorAll('#rp-days button').forEach(b => b.addEventListener('click', () => { rpDays ^= (1 << +b.dataset.d); rpRenderDays(); }));
function rpRenderDays(){ document.querySelectorAll('#rp-days button').forEach(b => b.classList.toggle('on', !!(rpDays & (1 << +b.dataset.d)))); }
const RP_DAYN = ['ma', 'di', 'wo', 'do', 'vr', 'za', 'zo'];
function rpDaysText(d){ if (!d) return t('eenmalig'); if (d === 127) return t('elke dag'); if (d === 31) return t('werkdagen'); return RP_DAYN.filter((x, i) => d & (1 << i)).map(x => t(x)).join(', '); }
function rpRender(){
  const st = rdJson(Android.redialPlanState(), {});
  const p = st.plan;
  if (p) {
    $('#rp-time').value = String(p.hour).padStart(2, '0') + ':' + String(p.minute).padStart(2, '0'); rpDays = p.days || 0;
    $('#rp-state').innerHTML = '📅 ' + t('<b>{naam}</b> wordt gebeld {dagen} om {tijd}', { naam: esc(p.name || p.number), dagen: esc(rpDaysText(p.days)), tijd: esc(fmtHm($('#rp-time').value)) }) +
      (st.next ? '. ' + t('Volgende keer: {wanneer}', { wanneer: esc(fmtDT(st.next, {weekday:'long', day:'numeric', month:'short', hour:'2-digit', minute:'2-digit'})) }) : '') + '.';
  } else $('#rp-state').textContent = t('Laat Auto redial vanzelf beginnen, bijvoorbeeld om 08:00 de huisarts. Met het nummer en de instellingen hierboven.') + (st.last ? ' ' + t('Laatst: {wat}.', { wat: t(st.last) }) : '');
  $('#rp-save').textContent = p ? t('Planning bijwerken') : t('Inplannen');
  $('#rp-clear').style.display = p ? 'block' : 'none';
  $('#rp-exact').style.display = p && st.exact === false ? 'block' : 'none';
  rpRenderDays();
}
function rpSave(){
  const n = $('#num').value.trim(), t = ($('#rp-time').value || '').split(':');
  if (t.length < 2) { toast('Kies een tijd'); return; }
  const p = renderPerms();
  if (!p.call || !p.phoneState) { toast('Geef eerst toestemming'); askPerms(); return; }
  const iv = store.get('interval', 10), rnd = iv === -1, rmin = store.get('rmin', 10), rmax = store.get('rmax', 60);
  const err = Android.redialPlanSet(JSON.stringify({ number: n, name: nameFor, hour: +t[0], minute: +t[1], days: rpDays,
    attempts: store.get('attempts', 10), interval: rnd ? rmin : iv, randomMin: rnd ? rmin : 0, randomMax: rnd ? rmax : 0,
    stopWhenAnswered: store.get('t-stop', true), speaker: store.get('t-spk', false) }));
  if (err) { toast(err); return; }
  remember(n, nameFor); rpRender(); renderDash(); toast('Ingepland');
}
function rpClear(){ Android.redialPlanClear(); rpDays = 0; rpRender(); renderDash(); toast('Planning weggehaald'); }

