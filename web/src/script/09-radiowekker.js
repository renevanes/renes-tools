/* ---------- Radiowekker ---------- */
let alDays = 31;
function enterAlarm(){
  const a = rdJson(Android.alarmState(), {});
  $('#al-on').checked = !!a.on;
  $('#al-time').value = String(a.hour != null ? a.hour : 7).padStart(2, '0') + ':' + String(a.minute || 0).padStart(2, '0');
  alDays = a.days != null ? a.days : 31; alRenderDays();
  // Zenders: favorieten, de huidige en de laatst gebruikte
  const list = [], seen = new Set();
  const add = s => { if (s && s.url && !seen.has(s.url)) { seen.add(s.url); list.push(s); } };
  add(a.station); rdJson(Android.radioFavorites(), []).forEach(add); add(rdState.station); add(rdState.last); rdStations.slice(0, 20).forEach(add);
  alStations = list;
  $('#al-station').innerHTML = list.map((s, i) => '<option value="' + i + '">' + esc(s.name) + '</option>').join('') || '<option value="">Kies eerst een zender in Radio</option>';
  $('#al-next').textContent = a.on && a.nextAt ? 'Gaat af: ' + new Date(a.nextAt).toLocaleString('nl-NL', {weekday:'long', day:'numeric', month:'long', hour:'2-digit', minute:'2-digit'}) : '';
  $('#al-exact').style.display = a.exact === false ? 'block' : 'none';
}
let alStations = [];
function alRenderDays(){ document.querySelectorAll('#al-days button').forEach(b => b.classList.toggle('on', !!(alDays & (1 << +b.dataset.d)))); }
document.querySelectorAll('#al-days button').forEach(b => b.addEventListener('click', () => { alDays ^= (1 << +b.dataset.d); alRenderDays(); }));
function alSave(){
  const [h, m] = ($('#al-time').value || '07:00').split(':').map(Number);
  const st = alStations[+$('#al-station').value];
  const e = Android.alarmSet($('#al-on').checked, h, m, alDays, st ? JSON.stringify(st) : '');
  if (e) { toast(e); return false; }
  enterAlarm();
  toast($('#al-on').checked ? '⏰ Wekker opgeslagen' : 'Wekker uit');
  return true;
}
function alTest(){ if (!alSave()) return; const e = Android.alarmTest(); toast(e || 'De wekker gaat over 10 seconden af'); }

