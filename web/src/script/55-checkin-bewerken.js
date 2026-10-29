/* ---------- Check-in: een vraag maken of bewerken ---------- */
let cedQ = null, cedSnap = '', cedNew = false;
const CED_SCALES = [['buttons2', '2 knoppen'], ['buttons3', '3 knoppen'], ['smileys5', '5 smileys'], ['score10', 'Score 1–10'], ['yesno', 'Ja / nee'], ['custom', 'Zelf kiezen']];
const CED_RHYTHMS = [['daily', 'Elke dag'], ['workdays', 'Werkdagen'], ['weekdays', 'Gekozen dagen'], ['weekly', 'Elke week'], ['monthly', 'Elke maand'], ['multi', 'Meerdere keren per dag']];
const CED_COLORS = [['primary', 'Standaard'], ['blue', 'Blauw'], ['green', 'Groen'], ['orange', 'Oranje'], ['red', 'Rood'], ['purple', 'Paars'], ['grey', 'Grijs']];
const CED_EMOJI = ['🙂', '😴', '💼', '🏃', '🍎', '💧', '📚', '❤️', '🧘', '☕', '⭐'];
/** Standaardantwoorden per soort (zoals CheckinStore.defaultOptions). Zuiver. */
function cedDefaults(scale){
  const m = { buttons2: [['👎', 'Niet goed', 1], ['👍', 'Goed', 2]], buttons3: [['😕', 'Slecht', 1], ['😐', 'Gaat wel', 2], ['🙂', 'Goed', 3]],
    smileys5: [['😞', 'Heel slecht', 1], ['🙁', 'Slecht', 2], ['😐', 'Gaat wel', 3], ['🙂', 'Goed', 4], ['😄', 'Heel goed', 5]], yesno: [['✗', 'Nee', 0], ['✓', 'Ja', 1]],
    score10: Array.from({ length: 10 }, (_, i) => ['', String(i + 1), i + 1]), custom: [['', 'Keuze 1', 1], ['', 'Keuze 2', 2], ['', 'Keuze 3', 3]] };
  return (m[scale] || m.custom).map(([emoji, label, value]) => ({ emoji, label, value }));
}
function cedBlank(){
  return { text: '', emoji: '🙂', color: 'primary', scale: 'smileys5', options: cedDefaults('smileys5'), rhythm: 'daily', days: [1, 2, 3, 4, 5, 6, 7], wday: 5, dom: 1, times: ['21:00'], until: '23:59',
    quietWeekend: false, pauseUntil: '', on: true, archived: false, perPeriod: 'one', editable: true, backfill: true, note: true, voice: false, labels: false, labelList: [] };
}
const CED_KEEP = ['id', 'text', 'emoji', 'color', 'scale', 'options', 'rhythm', 'days', 'wday', 'dom', 'times', 'until', 'quietWeekend', 'pauseUntil', 'on', 'archived', 'tpl', 'perPeriod', 'editable', 'backfill', 'note', 'voice', 'labels', 'labelList'];
function cedOpen(q){
  cedNew = !q;
  const src = q || cedBlank(), x = {};
  CED_KEEP.forEach(k => { if (src[k] !== undefined) x[k] = JSON.parse(JSON.stringify(src[k])); });
  if (!x.days || !x.days.length) x.days = [1, 2, 3, 4, 5, 6, 7];
  cedQ = x; cedSnap = JSON.stringify(x);
  jumpTo('checkined');
}
function ciNew(){ cedOpen(null); }
function ciEdit(id){ const q = ciQ(id); if (q) cedOpen(q); }
function cedDirty(){ if (!cedQ) return false; cedSync(); return JSON.stringify(cedQ) !== cedSnap; }
function enterCheckinEd(){ if (!cedQ) { show('checkin'); return; } cedRender(); }

/** Wat er in de velden staat terug in cedQ (voor het opnieuw tekenen). */
function cedSync(){
  if (!cedQ || current !== 'checkined') return;
  cedQ.text = $('#ced-text').value; cedQ.emoji = $('#ced-emoji').value.trim();
  document.querySelectorAll('#ced-opts .ci-optrow').forEach((r, i) => { const o = cedQ.options[i]; if (!o) return;
    o.emoji = r.querySelector('.oe').value.trim(); o.label = r.querySelector('.ol').value; const v = parseFloat(String(r.querySelector('.ov').value).replace(',', '.')); o.value = isNaN(v) ? o.value : v; });
  const ts = [...document.querySelectorAll('#ced-times input[type=time]')].map(e => e.value).filter(Boolean);
  if (ts.length) cedQ.times = cedQ.rhythm === 'multi' ? ts : [ts[0]].concat(cedQ.times.slice(1));
  cedQ.until = $('#ced-until').value || '23:59';
  cedQ.pauseUntil = $('#ced-pause').value || '';
  cedQ.wday = +$('#ced-wday').value || 5; cedQ.dom = +$('#ced-dom').value || 1;
}
function cedRadios(id, list, val, fn){
  $(id).innerHTML = list.map(([v, l]) => '<button role="radio" aria-checked="' + (v === val) + '" class="' + (v === val ? 'on' : '') + '" data-v="' + v + '" onclick="' + fn + '(this.dataset.v)">' + esc(l) + '</button>').join('');
}
function cedSwitch(id, on){ const b = $(id); b.classList.toggle('on', !!on); b.setAttribute('aria-checked', on ? 'true' : 'false'); }
function cedRender(){
  const q = cedQ;
  $('#ced-h').textContent = cedNew ? 'Nieuwe vraag' : 'Vraag bewerken';
  $('#s-checkined').dataset.c = q.color || 'primary';
  if ($('#ced-text').value !== q.text) $('#ced-text').value = q.text;
  if ($('#ced-emoji').value !== q.emoji) $('#ced-emoji').value = q.emoji;
  $('#ced-emsug').innerHTML = CED_EMOJI.map(e => '<button class="' + (e === q.emoji ? 'on' : '') + '" aria-pressed="' + (e === q.emoji) + '" data-e="' + e + '" onclick="cedEmoji(this.dataset.e)" aria-label="Emoji ' + e + '">' + e + '</button>').join('');
  $('#ced-color').innerHTML = CED_COLORS.map(([v, l]) => '<button role="radio" data-c="' + v + '" aria-checked="' + (v === q.color) + '" aria-label="' + l + '" onclick="cedSet(\'color\', \'' + v + '\')"></button>').join('');
  cedRadios('#ced-scale', CED_SCALES, q.scale, 'cedScale');
  const n = q.options.length;
  $('#ced-scale-n').textContent = n <= 3 ? 'Met ' + n + ' antwoorden tik je je antwoord meteen in de melding (één tik).' : 'Met ' + n + ' antwoorden opent de melding een klein scherm met grote knoppen (twee tikken).';
  $('#ced-opts').innerHTML = q.options.map((o, i) => '<div class="ci-optrow" role="listitem">' +
    '<input class="ci-in oe" value="' + esc(o.emoji) + '" maxlength="16" aria-label="Emoji van antwoord ' + (i + 1) + '" autocomplete="off">' +
    '<input class="ci-in ol" value="' + esc(o.label) + '" maxlength="40" aria-label="Naam van antwoord ' + (i + 1) + '" autocomplete="off">' +
    '<input class="ci-in ov" type="text" inputmode="decimal" value="' + esc(String(o.value).replace('.', ',')) + '" aria-label="Waarde van antwoord ' + (i + 1) + '" autocomplete="off">' +
    (q.scale === 'custom' && n > 2 ? '<button class="iconbtn" data-i="' + i + '" onclick="cedOptDel(+this.dataset.i)" aria-label="Antwoord ' + (i + 1) + ' weghalen">' + uiIcon('close') + '</button>' : '') + '</div>').join('');
  $('#ced-opt-add').style.display = q.scale === 'custom' && n < 7 ? '' : 'none';
  cedRadios('#ced-rhythm', CED_RHYTHMS, q.rhythm, 'cedRhythm');
  const showDays = q.rhythm === 'weekdays' || q.rhythm === 'multi';
  $('#ced-days-w').style.display = showDays ? '' : 'none';
  $('#ced-days').innerHTML = [1, 2, 3, 4, 5, 6, 7].map(d => { const on = q.days.includes(d); return '<button class="' + (on ? 'on' : '') + '" aria-pressed="' + on + '" data-d="' + d + '" onclick="cedDay(+this.dataset.d)" aria-label="' + CI_DOW_L[d] + '">' + CI_DOW[d] + '</button>'; }).join('');
  $('#ced-wday-w').style.display = q.rhythm === 'weekly' ? '' : 'none';
  $('#ced-wday').innerHTML = [1, 2, 3, 4, 5, 6, 7].map(d => '<option value="' + d + '"' + (d === q.wday ? ' selected' : '') + '>' + CI_DOW_L[d] + '</option>').join('');
  $('#ced-dom-w').style.display = q.rhythm === 'monthly' ? '' : 'none';
  $('#ced-dom').innerHTML = Array.from({ length: 31 }, (_, i) => '<option value="' + (i + 1) + '"' + (i + 1 === q.dom ? ' selected' : '') + '>' + (i + 1) + 'e</option>').join('');
  const multi = q.rhythm === 'multi', ts = multi ? q.times : q.times.slice(0, 1);
  $('#ced-times').innerHTML = ts.map((t, i) => '<div class="ci-timerow"><label for="ced-t' + i + '">' + (multi ? 'Tijd ' + (i + 1) : 'Om') + '</label><input type="time" class="sl-time" id="ced-t' + i + '" value="' + esc(t) + '">' +
    (multi && ts.length > 1 ? '<button class="iconbtn" data-i="' + i + '" onclick="cedTimeDel(+this.dataset.i)" aria-label="Tijd ' + (i + 1) + ' weghalen">' + uiIcon('close') + '</button>' : '') + '</div>').join('');
  $('#ced-time-add').style.display = multi && ts.length < 8 ? '' : 'none';
  $('#ced-until').value = q.until || '23:59';
  cedSwitch('#ced-wk', q.quietWeekend);
  $('#ced-pause').value = q.pauseUntil || '';
  $('#ced-pause-x').style.display = q.pauseUntil ? '' : 'none';
  document.querySelectorAll('#ced-per button').forEach(b => { const on = b.dataset.v === q.perPeriod; b.classList.toggle('on', on); b.setAttribute('aria-checked', on ? 'true' : 'false'); });
  $('#ced-per-n').textContent = q.perPeriod === 'many' ? 'Je kunt per periode vaker antwoorden; alles telt mee.' : 'Een nieuw antwoord in dezelfde periode vervangt het oude (na bevestigen).';
  cedSwitch('#ced-ed', q.editable); cedSwitch('#ced-bf', q.backfill);
  cedSwitch('#ced-note', q.note); cedSwitch('#ced-voice', q.voice); cedSwitch('#ced-lab', q.labels); cedSwitch('#ced-on', q.on);
  $('#ced-labels-w').style.display = q.labels ? '' : 'none';
  $('#ced-labels').innerHTML = q.labelList.map((l, i) => '<span class="ci-lchip" role="listitem"><button class="on" data-i="' + i + '" onclick="cedLabelDel(+this.dataset.i)" aria-label="Label ' + esc(l) + ' weghalen">' + esc(l) + ' ×</button></span>').join('') || '<p class="note" style="margin:0">Nog geen labels.</p>';
}
function cedSet(k, v){ cedSync(); cedQ[k] = v; cedRender(); }
function cedTg(k){ cedSync(); cedQ[k] = !cedQ[k]; cedRender(); }
function cedEmoji(e){ cedSync(); cedQ.emoji = e; cedRender(); }
function cedScale(v){
  cedSync();
  if (v === cedQ.scale) return;
  cedQ.scale = v;
  if (v !== 'custom' || cedQ.options.length > 7) cedQ.options = cedDefaults(v);
  cedRender();
}
function cedOptAdd(){ cedSync(); if (cedQ.options.length >= 7) return; const max = Math.max(0, ...cedQ.options.map(o => +o.value || 0)); cedQ.options.push({ emoji: '', label: 'Keuze ' + (cedQ.options.length + 1), value: max + 1 }); cedRender(); }
function cedOptDel(i){ cedSync(); if (cedQ.options.length <= 2) return; cedQ.options.splice(i, 1); cedRender(); }
function cedRhythm(v){
  cedSync(); cedQ.rhythm = v;
  if (v === 'multi' && cedQ.times.length < 2) { const t = cedQ.times[0] || '09:00'; cedQ.times = t === '21:00' ? ['09:00', '21:00'] : [t, '21:00'].sort(); }
  if (v === 'weekdays' && cedQ.days.length === 7) cedQ.days = [1, 2, 3, 4, 5];
  cedRender();
}
function cedDay(d){ cedSync(); cedQ.days = cedQ.days.includes(d) ? cedQ.days.filter(x => x !== d) : cedQ.days.concat([d]).sort(); cedRender(); }
function cedTimeAdd(){ cedSync(); if (cedQ.times.length >= 8) return; const last = cedQ.times[cedQ.times.length - 1] || '12:00', h = Math.min(23, +last.slice(0, 2) + 3); cedQ.times.push(String(h).padStart(2, '0') + last.slice(2)); cedRender(); }
function cedTimeDel(i){ cedSync(); if (cedQ.times.length <= 1) return; cedQ.times.splice(i, 1); cedRender(); }
function cedPauseClear(){ cedSync(); cedQ.pauseUntil = ''; cedRender(); }
function cedLabelAdd(){
  cedSync();
  const inp = $('#ced-label-in'), v = inp.value.trim().slice(0, 30);
  if (!v) return;
  if (cedQ.labelList.some(l => l.toLowerCase() === v.toLowerCase())) { toast('Dat label is er al'); return; }
  if (cedQ.labelList.length >= 16) { toast('Hooguit 16 labels'); return; }
  cedQ.labelList.push(v); inp.value = ''; cedRender(); inp.focus();
}
function cedLabelDel(i){ cedSync(); cedQ.labelList.splice(i, 1); cedRender(); }
function cedSave(){
  cedSync();
  const q = cedQ;
  if (!q.text.trim()) { toast('Vul de vraag in'); $('#ced-text').focus(); return; }
  if (q.on && ciSt.notifOk === false) Android.permRequest('notif'); // Android 13+: toestemming voor de meldingen
  const r = rdJson(Android.checkinSave(JSON.stringify(q)), {});
  if (r.error) { toast(r.error); return; }
  cedQ = null; cedSnap = '';
  ciLoad();
  const saved = ciQ(r.id);
  toast(cedNew ? '✓ Vraag bewaard' + (saved && saved.on ? ': ' + ciRhythmText(saved).replace(/^./, x => x.toLowerCase()) : '') : '✓ Bewaard');
  goBack();
  ciAfter();
}
function cedCancel(){ cedQ = null; cedSnap = ''; goBack(); }
