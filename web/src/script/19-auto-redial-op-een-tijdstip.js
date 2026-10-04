/* ---------- Auto redial op een tijdstip ---------- */
let rpDays = 0;
document.querySelectorAll('#rp-days button').forEach(b => b.addEventListener('click', () => { rpDays ^= (1 << +b.dataset.d); rpRenderDays(); }));
function rpRenderDays(){ document.querySelectorAll('#rp-days button').forEach(b => b.classList.toggle('on', !!(rpDays & (1 << +b.dataset.d)))); }
const RP_DAYN = ['ma', 'di', 'wo', 'do', 'vr', 'za', 'zo'];
function rpDaysText(d){ if (!d) return 'eenmalig'; if (d === 127) return 'elke dag'; if (d === 31) return 'werkdagen'; return RP_DAYN.filter((x, i) => d & (1 << i)).join(', '); }
function rpRender(){
  const st = rdJson(Android.redialPlanState(), {});
  const p = st.plan;
  if (p) {
    $('#rp-time').value = String(p.hour).padStart(2, '0') + ':' + String(p.minute).padStart(2, '0'); rpDays = p.days || 0;
    $('#rp-state').innerHTML = '📅 <b>' + esc(p.name || p.number) + '</b> wordt gebeld ' + esc(rpDaysText(p.days)) + ' om ' + esc($('#rp-time').value) +
      (st.next ? '. Volgende keer: ' + esc(new Date(st.next).toLocaleString('nl-NL', {weekday:'long', day:'numeric', month:'short', hour:'2-digit', minute:'2-digit'})) : '') + '.';
  } else $('#rp-state').textContent = 'Laat Auto redial vanzelf beginnen, bijvoorbeeld om 08:00 de huisarts. Met het nummer en de instellingen hierboven.' + (st.last ? ' Laatst: ' + st.last + '.' : '');
  $('#rp-save').textContent = p ? 'Planning bijwerken' : 'Inplannen';
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
  remember(n, nameFor); rpRender(); renderDash(); toast('📅 Ingepland');
}
function rpClear(){ Android.redialPlanClear(); rpDays = 0; rpRender(); renderDash(); toast('Planning weggehaald'); }

