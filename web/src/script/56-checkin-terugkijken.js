/* ---------- Check-in: terugkijken (kalender, grafiek, samenvatting) en vergelijken ---------- */
let ciqId = '', ciqMonthAt = null, ciqViewV = 'month', ciqSelKey = '', ciqHist = null, ciqDaysInfo = null, ciqChartHist = null;
const CI_DAYRHYTHM = r => r === 'daily' || r === 'workdays' || r === 'weekdays' || r === 'multi';
function ciNum(v, d){ return (Math.round(v * Math.pow(10, d == null ? 1 : d)) / Math.pow(10, d == null ? 1 : d)).toString().replace('.', ','); }
function ciRange(q){ const v = ciOpts(q).map(o => +o.value); return v.length ? [Math.min(...v), Math.max(...v)] : [0, 1]; }
/** 0..1 op de schaal van de vraag (hoger = beter). Zuiver. */
function ciNorm(q, v){ const [lo, hi] = ciRange(q); return hi > lo ? Math.max(0, Math.min(1, (v - lo) / (hi - lo))) : 1; }
function ciLevel(n){ return n < .25 ? 'l1' : n < .5 ? 'l2' : n < .75 ? 'l3' : 'l4'; }
/** Het antwoord dat het dichtst bij een (gemiddelde) waarde ligt. Zuiver. */
function ciNearest(q, v){ let best = null; ciOpts(q).forEach(o => { if (!best || Math.abs(o.value - v) < Math.abs(best.value - v)) best = o; }); return best || {}; }
function ciAvg(xs){ return xs.length ? xs.reduce((a, b) => a + b, 0) / xs.length : NaN; }
function ciHistory(id, from, to){ return rdJson(Android.checkinHistory(id, from, to), {}); }
/** Antwoorden per dag (yyyy-MM-dd van het moment van de periode; "extra" op de dag van hun periode). Zuiver. */
function ciByDay(h){
  const m = {};
  (h.slots || []).forEach(s => { const d = ciDayKey(new Date(s.at)); (m[d] = m[d] || []).push(...(s.answers || [])); });
  (h.extra || []).forEach(a => { const d = /^\d{4}-\d{2}-\d{2}/.test(a.p) ? a.p.slice(0, 10) : ciDayKey(new Date(a.t)); (m[d] = m[d] || []).push(a); });
  return m;
}
/** Trend: kleinste kwadraten over de punten [x, y]; geeft de verandering over het hele bereik (y is 0..1). Zuiver. */
function ciTrend(pts){
  if (pts.length < 3) return null;
  const n = pts.length, mx = ciAvg(pts.map(p => p[0])), my = ciAvg(pts.map(p => p[1]));
  let sxy = 0, sxx = 0; pts.forEach(([x, y]) => { sxy += (x - mx) * (y - my); sxx += (x - mx) * (x - mx); });
  if (!sxx) return null;
  const b = sxy / sxx, a = my - b * mx, x0 = Math.min(...pts.map(p => p[0])), x1 = Math.max(...pts.map(p => p[0]));
  return { a, b, change: b * (x1 - x0), x0, x1, n };
}
function ciTrendText(t){ if (!t) return 'te weinig antwoorden voor een trend'; return t.change > .1 ? 'stijgend' : t.change < -.1 ? 'dalend' : 'vrij gelijk'; }

function enterCheckinQ(){
  ciLoad();
  const q = ciQ(ciqId); if (!q) { show('checkin'); return; }
  if (!ciqMonthAt) { const n = new Date(); ciqMonthAt = new Date(n.getFullYear(), q.rhythm === 'monthly' ? 0 : n.getMonth(), 1, 12); ciqSelKey = ''; }
  ciqLoad();
}
/** Het bereik van de kalender: een maand, of een jaar bij "elke maand". */
function ciqSpan(q){
  const a = ciqMonthAt, yearly = q.rhythm === 'monthly';
  const b = yearly ? new Date(a.getFullYear() + 1, 0, 1, 12) : new Date(a.getFullYear(), a.getMonth() + 1, 1, 12);
  return [ciDayKey(a), ciDayKey(b), yearly];
}
function ciqLoad(){
  ciLoad();
  const q = ciQ(ciqId); if (!q) { show('checkin'); return; }
  const [from, to] = ciqSpan(q);
  ciqHist = ciHistory(q.id, from, to);
  ciqDaysInfo = rdJson(Android.checkinDays(from, to), { days: {} });
  ciqRender();
}
function ciqRender(){
  const q = ciQ(ciqId); if (!q || !ciqHist) return;
  $('#ciq-title').textContent = (q.emoji ? q.emoji + ' ' : '') + q.text;
  $('#s-checkinq').dataset.c = q.color || 'primary';
  // Nu
  const [st, cls] = ciStatus(q);
  slHtml($('#ciq-now'), q.archived ? '<div class="card"><p class="ci-st warn" style="margin:0">Gearchiveerd: je kijkt alleen terug.</p></div>' :
    '<div class="card ci-card" data-c="' + esc(q.color || 'primary') + '"><p class="ci-st ' + cls + '" style="margin:0">' + esc(st) + '</p>' + (ciDue(q) ? ciOptBtns(q, q.cur.key, -1, false) : '') + '</div>');
  ciqRenderCal(q);
  ciqRenderDay(q);
  ciqRenderChart(q);
  ciqRenderSum(q);
}
function ciqCells(q){
  const h = ciqHist, today = ciDayKey(new Date()), [from, , yearly] = ciqSpan(q);
  const status = ss => ss.some(s => s.status === 'open') ? 'open' : ss.some(s => s.status === 'missed') ? 'miss' : ss.length && ss.every(s => s.status === 'quiet') ? 'quiet' : ss.some(s => s.status === 'upcoming') ? 'up' : 'none';
  const word = { open: 'nog open', miss: 'gemist', quiet: 'stil (telt niet)', up: 'komt nog', none: 'geen vraag' };
  const cell = (key, label, small, ans, ss, aria) => {
    const vs = ans.map(a => +a.v), avg = ciAvg(vs);
    const state = ans.length ? ciLevel(ciNorm(q, avg)) : status(ss);
    let txt = ans.length === 1 ? ciAnsText(ans[0]) : ans.length ? ans.length + ' antwoorden, gemiddeld ' + ciNum(avg) + ' (' + ciOptText(ciNearest(q, avg)) + ')' : word[state];
    const mark = ans.length === 1 ? (ans[0].e || ans[0].l || '') : ans.length > 1 ? ans.length + '×' : state === 'miss' ? '–' : '';
    return { key, label, small: small != null ? small : mark, state, aria: aria + ': ' + txt, ans, ss };
  };
  if (CI_DAYRHYTHM(q.rhythm)) {
    const by = ciByDay(h), slotsBy = {};
    (h.slots || []).forEach(s => { const d = ciDayKey(new Date(s.at)); (slotsBy[d] = slotsBy[d] || []).push(s); });
    const a = ciDate(from), cells = [], lead = (a.getDay() + 6) % 7;
    for (let i = 0; i < lead; i++) cells.push(null);
    for (let d = new Date(a); d.getMonth() === a.getMonth(); d.setDate(d.getDate() + 1)) {
      const k = ciDayKey(d), c = cell(k, String(d.getDate()), null, by[k] || [], slotsBy[k] || [], CI_DOW_L[(d.getDay() + 6) % 7 + 1] + ' ' + d.getDate() + ' ' + CI_MON[d.getMonth()]);
      c.today = k === today; cells.push(c);
    }
    return { cells, wide: false };
  }
  // Per week of per maand: een vakje per periode
  const cells = (h.slots || []).map(s => {
    const lab = q.rhythm === 'weekly' ? 'wk ' + (+s.key.slice(6)) : CI_MON[+s.key.slice(5) - 1].slice(0, 3);
    const c = cell(s.key, lab, null, s.answers || [], [s], ciKeyLabel(s.key));
    c.today = s.status === 'open'; return c;
  });
  (h.extra || []).forEach(a => { if (!cells.some(c => c.key === a.p)) cells.push(cell(a.p, ciKeyLabel(a.p), null, [a], [], ciKeyLabel(a.p))); });
  return { cells, wide: true, yearly };
}
function ciqRenderCal(q){
  const [from, , yearly] = ciqSpan(q), a = ciDate(from), now = new Date();
  $('#ciq-month').textContent = yearly ? String(a.getFullYear()) : CI_MON[a.getMonth()].replace(/^./, x => x.toUpperCase()) + ' ' + a.getFullYear();
  const isNow = yearly ? a.getFullYear() >= now.getFullYear() : a.getFullYear() * 12 + a.getMonth() >= now.getFullYear() * 12 + now.getMonth();
  $('#ciq-next').disabled = isNow;
  $('#ciq-prev').setAttribute('aria-label', yearly ? 'Vorig jaar' : 'Vorige maand'); $('#ciq-next').setAttribute('aria-label', yearly ? 'Volgend jaar' : 'Volgende maand');
  const { cells, wide } = ciqCells(q);
  const head = wide ? '' : CI_DOW.slice(1).map(d => '<div class="ci-dh" aria-hidden="true">' + d + '</div>').join('');
  const html = '<div class="ci-cal' + (wide ? ' wide' : '') + '" id="ciq-grid">' + head + cells.map(c => c ? '<button class="ci-d ' + c.state + (c.today ? ' today' : '') + (c.key === ciqSelKey ? ' sel' : '') + '" data-k="' + esc(c.key) + '" onclick="ciqPick(this.dataset.k)" aria-label="' + esc(c.aria) + '"' + (c.key === ciqSelKey ? ' aria-current="date"' : '') + '><span aria-hidden="true">' + esc(c.label) + '</span>' + (c.small ? '<small aria-hidden="true">' + esc(c.small) + '</small>' : '') + '</button>' : '<span aria-hidden="true"></span>').join('') + '</div>';
  slHtml($('#ciq-cal'), cells.filter(Boolean).length ? html : '<p class="note">Geen periodes in deze ' + (yearly ? 'tijd' : 'maand') + '.</p>');
  const [lo, hi] = ciRange(q), ol = ciNearest(q, lo), oh = ciNearest(q, hi);
  $('#ciq-legend').innerHTML = '<span><i class="l1"></i>' + esc(ciOptText(ol) || 'laag') + '</span><span><i class="l4"></i>' + esc(ciOptText(oh) || 'hoog') + '</span><span><i class="miss"></i>gemist</span><span><i class="quiet"></i>stil</span>';
  const s = ciqHist.summary || {};
  $('#ciq-calsum').textContent = (s.answered || 0) + ' ingevuld, ' + (s.missed || 0) + ' gemist. Tik op een ' + (wide ? 'vakje' : 'dag') + ' om te zien wat je antwoordde' + (q.backfill !== false && !q.archived ? ', of om achteraf in te vullen' : '') + '.';
  // Vegen: vorige of volgende maand
  const g = $('#ciq-grid');
  if (g && !g._swipe) {
    g._swipe = true; let sx = 0, sy = 0;
    g.addEventListener('touchstart', e => { sx = e.touches[0].clientX; sy = e.touches[0].clientY; }, { passive: true });
    g.addEventListener('touchend', e => { const t = e.changedTouches[0], dx = t.clientX - sx, dy = t.clientY - sy; if (Math.abs(dx) > 60 && Math.abs(dy) < 40) ciqStep(dx < 0 ? 1 : -1); });
  }
}
function ciqStep(dir){
  const q = ciQ(ciqId); if (!q) return;
  const a = ciqMonthAt, yearly = q.rhythm === 'monthly';
  const n = yearly ? new Date(a.getFullYear() + dir, 0, 1, 12) : new Date(a.getFullYear(), a.getMonth() + dir, 1, 12), now = new Date();
  if (dir > 0 && (yearly ? n.getFullYear() > now.getFullYear() : n > new Date(now.getFullYear(), now.getMonth(), 1, 12))) return;
  ciqMonthAt = n; ciqSelKey = ''; ciqLoad();
}
function ciqPick(key){ ciqSelKey = key; ciqRender(); const d = $('#ciq-day'); if (d) { d.scrollIntoView({ block: 'nearest' }); d.focus({ preventScroll: true }); } }
function ciDur(min){ min = Math.round(min); return min < 60 ? min + ' min' : Math.floor(min / 60) + ' u' + (min % 60 ? ' ' + (min % 60) + ' min' : ''); }
/** Slaap en agenda van een dag in woorden. */
function ciDayInfo(day, info){
  const x = ((info || {}).days || {})[day] || {}, out = [];
  if (x.sleep) out.push(['moon', 'Nacht ervoor (Slaappraat): luisterde ' + ciDur(x.sleep.min) + (x.sleep.heard ? ' · ' + x.sleep.heard + '× iets gehoord' + (x.sleep.talk ? ', waarvan ' + x.sleep.talk + '× gepraat' : '') : ' · niets gehoord')]);
  if ((info || {}).cal) out.push(['calendar', x.cal ? 'Agenda: ' + (x.cal.n ? x.cal.n + (x.cal.n === 1 ? ' afspraak' : ' afspraken') + (x.cal.busy ? ', ' + ciDur(x.cal.busy) + ' bezet' : '') : 'geen afspraken met een tijd') + (x.cal.allDay ? ' (+' + x.cal.allDay + ' hele dag)' : '') : 'Agenda: niets gepland']);
  return out;
}
function ciqRenderDay(q){
  const box = $('#ciq-day');
  if (!ciqSelKey) { box.style.display = 'none'; return; }
  const c = ciqCells(q).cells.find(x => x && x.key === ciqSelKey);
  if (!c) { box.style.display = 'none'; return; }
  box.style.display = '';
  const now = Date.now(), canEdit = a => q.editable !== false || now - a.t < 15 * 60000;
  let h = '<h2>' + esc(ciKeyLabel(c.key).replace(/^./, x => x.toUpperCase())) + '</h2>';
  const ss = c.ss.length ? c.ss : [{ key: c.key, status: c.ans.length ? 'answered' : 'none', answers: c.ans }];
  ss.forEach(s => {
    const ans = s.answers || [];
    if (q.rhythm === 'multi' && s.at) h += '<p class="ci-st"><b>' + esc(ciTime(s.at)) + '</b> · ' + esc(ans.length ? 'ingevuld' : { open: 'nog open', missed: 'gemist', quiet: 'stil', upcoming: 'komt nog', before: 'van vóór de vraag' }[s.status] || '') + '</p>';
    ans.forEach(a => {
      h += '<div class="ci-ans"><b>' + esc(ciAnsText(a)) + '</b> <small class="note">' + esc((a.late ? 'achteraf ingevuld' : a.src === 'notif' ? 'uit de melding' : 'ingevuld') + ' om ' + ciShort(new Date(a.t)) + ' ' + ciTime(a.t)) + '</small>' +
        ((a.labels || []).length ? '<div class="ci-tags">' + a.labels.map(l => '<span>' + esc(l) + '</span>').join('') + '</div>' : '') +
        (a.note ? '<p class="ci-note">' + (a.voice ? uiIcon('mic') + ' ' : '') + esc(a.note) + '</p>' : '') +
        (canEdit(a) && !q.archived ? '<div class="ci-acts"><button class="mini" data-k="' + esc(s.key) + '" data-a="' + esc(a.id) + '" onclick="ciqEdit(this.dataset.k, this.dataset.a)" aria-label="' + esc('Wijzigen: ' + ciAnsText(a) + ', ' + ciKeyLabel(s.key)) + '">Wijzigen</button>' +
          '<button class="mini" data-a="' + esc(a.id) + '" onclick="ciqDelAns(this.dataset.a)" aria-label="' + esc('Wissen: ' + ciAnsText(a) + ', ' + ciKeyLabel(s.key)) + '">Wissen</button></div>' : '') + '</div>';
    });
    if (q.archived) return;
    const one = q.perPeriod !== 'many', past = s.status === 'missed' || s.status === 'before' || (s.status === 'answered' && s.end && s.end <= now), todayUp = s.status === 'upcoming' && ciDayKey(new Date(s.at)) === ciDayKey(new Date());
    let act = '';
    if ((s.status === 'open' || todayUp) && !ans.length) act = 'Invullen';
    else if (s.status === 'open' && !one) act = 'Nog een antwoord';
    else if (past && q.backfill !== false && (!ans.length || !one)) act = ans.length ? 'Nog een antwoord (achteraf)' : 'Achteraf invullen';
    if (act) h += '<div class="ci-acts"><button class="mini on" data-k="' + esc(s.key) + '" data-l="' + (past ? 1 : 0) + '" onclick="ciAsk(ciqId, this.dataset.k, {late: this.dataset.l === \'1\'})" aria-label="' + esc(act + ': ' + q.text + ', ' + ciKeyLabel(s.key)) + '">' + act + '</button></div>';
    else if (!ans.length && past && q.backfill === false) h += '<p class="note">Achteraf invullen staat uit bij deze vraag.</p>';
  });
  if (CI_DAYRHYTHM(q.rhythm)) {
    ciDayInfo(c.key, ciqDaysInfo).forEach(([ic, t]) => { h += '<p class="ci-info">' + uiIcon(ic) + '<span>' + esc(t) + '</span></p>'; });
    if (ciqDaysInfo && !ciqDaysInfo.cal) h += '<p class="ci-info">' + uiIcon('calendar') + '<span>Je agenda erbij zien? <button class="linkish" onclick="Android.permRequest(\'calendar\')">Toegang geven (alleen lezen)</button></span></p>';
  }
  box.innerHTML = h;
}
function ciqEdit(key, aid){
  const q = ciQ(ciqId); if (!q || !ciqHist) return;
  const all = (ciqHist.slots || []).flatMap(s => s.answers || []).concat(ciqHist.extra || []), a = all.find(x => x.id === aid);
  ciAsk(ciqId, key, { aid, ans: a });
}
function ciqDelAns(aid){
  askConfirm('Antwoord wissen?', 'Het antwoord met de notitie en labels gaat weg.', 'Wissen', () => { const e = Android.checkinDeleteAnswer(aid); if (e) { toast(e); return; } toast('Gewist'); ciAfter(); });
}
function ciqMenu(){ if (ciqId) ciMenu(ciqId); }

/* ---------- grafiek ---------- */
function ciqView(v){ ciqViewV = v; store.set('ciView', v); const q = ciQ(ciqId); if (q) ciqRenderChart(q); }
/** Punten voor de grafiek: [{label, long, v (gemiddelde) of null}]. */
function ciqPoints(q){
  const now = new Date(), view = ciqViewV;
  if (view === 'year') {
    const y = (ciqMonthAt || now).getFullYear(), h = ciHistory(q.id, y + '-01-01', (y + 1) + '-01-01'), by = ciByDay(h);
    ciqChartHist = h;
    return CI_MON.map((m, i) => { const vs = []; Object.keys(by).forEach(d => { if (+d.slice(5, 7) === i + 1 && +d.slice(0, 4) === y) by[d].forEach(a => vs.push(+a.v)); });
      return { label: m.slice(0, 1).toUpperCase(), long: m + ' ' + y, v: vs.length ? ciAvg(vs) : null, n: vs.length }; });
  }
  let h, days = [];
  if (view === 'week') {
    const b = new Date(now.getFullYear(), now.getMonth(), now.getDate() + 1, 12), a = new Date(b); a.setDate(a.getDate() - 7);
    h = ciHistory(q.id, ciDayKey(a), ciDayKey(b));
    for (let d = new Date(a); d < b; d.setDate(d.getDate() + 1)) days.push(new Date(d));
  } else {
    const [from, to] = ciqSpan(q); h = q.rhythm === 'monthly' ? ciHistory(q.id, from.slice(0, 7) + '-01', to) : ciqHist;
    const a = ciDate(from); for (let d = new Date(a); d.getMonth() === a.getMonth(); d.setDate(d.getDate() + 1)) days.push(new Date(d));
  }
  ciqChartHist = h;
  if (!CI_DAYRHYTHM(q.rhythm)) // per periode
    return (h.slots || []).map(s => { const vs = (s.answers || []).map(a => +a.v); return { label: q.rhythm === 'weekly' ? 'wk' + (+s.key.slice(6)) : CI_MON[+s.key.slice(5) - 1].slice(0, 3), long: ciKeyLabel(s.key), v: vs.length ? ciAvg(vs) : null, n: vs.length }; });
  const by = ciByDay(h);
  return days.map(d => { const k = ciDayKey(d), vs = (by[k] || []).map(a => +a.v); return { label: view === 'week' ? CI_DOW[(d.getDay() + 6) % 7 + 1] : String(d.getDate()), long: ciShort(d), v: vs.length ? ciAvg(vs) : null, n: vs.length }; });
}
/** Staafjes met het gemiddelde (streepjes) en de trend; met een tabel als tekst. */
function ciChartSvg(q, pts, title){
  const W = 320, H = 150, L = 24, B = 18, T = 8, n = pts.length, bw = (W - L - 4) / Math.max(1, n);
  const y = v => T + (1 - ciNorm(q, v)) * (H - T - B), [lo, hi] = ciRange(q);
  const got = pts.map((p, i) => [i, p.v]).filter(p => p[1] != null);
  const avg = got.length ? ciAvg(got.map(p => p[1])) : NaN, tr = ciTrend(got.map(([i, v]) => [i, ciNorm(q, v)]));
  let s = '<line class="ci-gl" x1="' + L + '" x2="' + W + '" y1="' + y(hi) + '" y2="' + y(hi) + '"/><line class="ci-gl" x1="' + L + '" x2="' + W + '" y1="' + y(lo) + '" y2="' + y(lo) + '"/>' +
    '<text x="0" y="' + (y(hi) + 4) + '">' + esc(ciNum(hi)) + '</text><text x="0" y="' + (y(lo) + 4) + '">' + esc(ciNum(lo)) + '</text>';
  pts.forEach((p, i) => {
    const x = L + 2 + i * bw;
    if (p.v != null) { const top = y(p.v), hgt = Math.max(2, H - B - top); s += '<rect class="ci-bar" x="' + (x + bw * .15).toFixed(1) + '" y="' + top.toFixed(1) + '" width="' + Math.max(1, bw * .7).toFixed(1) + '" height="' + hgt.toFixed(1) + '" rx="2"/>'; }
    if (n <= 12 || i % Math.ceil(n / 10) === 0) s += '<text x="' + (x + bw / 2).toFixed(1) + '" y="' + (H - 4) + '" text-anchor="middle">' + esc(p.label) + '</text>';
  });
  if (!isNaN(avg)) s += '<line class="ci-avg" x1="' + L + '" x2="' + W + '" y1="' + y(avg).toFixed(1) + '" y2="' + y(avg).toFixed(1) + '"/>';
  if (tr) { const yy = v => T + (1 - Math.max(0, Math.min(1, v))) * (H - T - B), xx = i => L + 2 + i * bw + bw / 2; s += '<line class="ci-tr" x1="' + xx(tr.x0).toFixed(1) + '" x2="' + xx(tr.x1).toFixed(1) + '" y1="' + yy(tr.a + tr.b * tr.x0).toFixed(1) + '" y2="' + yy(tr.a + tr.b * tr.x1).toFixed(1) + '"/>'; }
  const desc = got.length ? 'Gemiddeld ' + ciNum(avg) + ' (' + ciOptText(ciNearest(q, avg)) + ') op een schaal van ' + ciNum(lo) + ' tot ' + ciNum(hi) + ', ' + got.length + (got.length === 1 ? ' antwoord' : ' keer ingevuld') + '; trend: ' + ciTrendText(tr) + '.' : 'Nog geen antwoorden in deze periode.';
  return '<p class="ci-big-txt">' + esc(desc) + '</p>' + (got.length ? '<svg viewBox="0 0 ' + W + ' ' + H + '" role="img" aria-label="' + esc(title + '. ' + desc) + '">' + s + '</svg>' +
    '<p class="note"><span class="ci-key"><i style="background:var(--qc)"></i>antwoord</span><span class="ci-key"><i style="border-top:2px dashed var(--ink)"></i>gemiddeld</span>' + (tr ? '<span class="ci-key"><i style="background:var(--warn)"></i>trend</span>' : '') + '</p>' +
    '<details><summary>Als tabel</summary><table class="ci-tbl"><thead><tr><th scope="col">Periode</th><th scope="col">Antwoord</th></tr></thead><tbody>' +
    pts.filter(p => p.v != null).map(p => '<tr><td>' + esc(p.long) + '</td><td>' + esc((p.n > 1 ? 'gem. ' + ciNum(p.v) + ' ' : '') + ciOptText(ciNearest(q, p.v))) + '</td></tr>').join('') + '</tbody></table></details>' : '');
}
function ciqRenderChart(q){
  ciqViewV = store.get('ciView', ciqViewV);
  if (!['week', 'month', 'year'].includes(ciqViewV)) ciqViewV = 'month';
  if (!CI_DAYRHYTHM(q.rhythm) && ciqViewV === 'week') ciqViewV = 'month';
  if (q.rhythm === 'monthly') ciqViewV = 'year';
  document.querySelectorAll('#ciq-view button').forEach(b => { const on = b.dataset.v === ciqViewV, hide = (b.dataset.v === 'week' && !CI_DAYRHYTHM(q.rhythm)) || (b.dataset.v === 'month' && q.rhythm === 'monthly');
    b.classList.toggle('on', on); b.setAttribute('aria-checked', on ? 'true' : 'false'); b.style.display = hide ? 'none' : ''; });
  const pts = ciqPoints(q), [from] = ciqSpan(q), a = ciDate(from);
  const title = ciqViewV === 'week' ? 'De afgelopen 7 dagen' : ciqViewV === 'year' ? 'Per maand in ' + (ciqMonthAt || new Date()).getFullYear() : 'Per dag in ' + CI_MON[a.getMonth()] + ' ' + a.getFullYear();
  slHtml($('#ciq-chart'), '<div class="ci-chart" data-c="' + esc(q.color || 'primary') + '"><p class="note" style="margin:6px 2px 4px">' + esc(title) + '</p>' + ciChartSvg(q, pts, title) + '</div>');
}

/* ---------- samenvatting ---------- */
function ciqRenderSum(q){
  const s = (ciqHist || {}).summary || {}, opts = ciOpts(q), counts = s.counts || [], [from, , yearly] = ciqSpan(q), a = ciDate(from), now = new Date();
  const thisOne = yearly ? a.getFullYear() === now.getFullYear() : a.getFullYear() === now.getFullYear() && a.getMonth() === now.getMonth();
  const when = yearly ? (thisOne ? 'Dit jaar' : 'In ' + a.getFullYear()) : thisOne ? 'Deze maand' : 'In ' + CI_MON[a.getMonth()] + ' ' + a.getFullYear();
  const order = opts.map((o, i) => i).reverse(), max = Math.max(1, ...counts);
  const parts = order.filter(i => counts[i]).map(i => counts[i] + '× ' + (opts[i].emoji || opts[i].label));
  let h = '<p class="ci-big-txt">' + esc(parts.length ? when + ' ' + parts.join(', ') : when + ' nog niets ingevuld') + '</p>';
  h += order.map(i => '<div class="ci-cnt" data-c="' + esc(q.color || 'primary') + '"><span>' + esc(ciOptText(opts[i])) + '</span><span class="b" aria-hidden="true"><i style="width:' + Math.round((counts[i] || 0) / max * 100) + '%"></i></span><b>' + (counts[i] || 0) + '×</b></div>').join('');
  const unit = { daily: ['dag', 'dagen'], workdays: ['werkdag', 'werkdagen'], weekdays: ['dag', 'dagen'], weekly: ['week', 'weken'], monthly: ['maand', 'maanden'], multi: ['keer', 'keer'] }[q.rhythm] || ['keer', 'keer'];
  h += '<p class="note">' + esc((s.answered || 0) + ' ingevuld, ' + (s.missed || 0) + ' gemist' + (s.avg != null ? ' · gemiddeld ' + ciNum(s.avg) + ' (' + ciOptText(ciNearest(q, s.avg)) + ')' : '')) + '</p>';
  h += '<p class="ci-st ok">' + esc('Reeks: ' + (s.streak || 0) + ' ' + ((s.streak || 0) === 1 ? unit[0] : unit[1]) + ' achter elkaar ingevuld') + '</p>';
  slHtml($('#ciq-sum'), h);
}

/* ---------- vergelijken ---------- */
let cicRangeV = 30, cicPre = '';
function ciCompare(id){ cicPre = id || ''; jumpTo('checkincmp'); }
function enterCheckinCmp(){
  ciLoad();
  const qs = ciSt.questions || [];
  const a = $('#cic-a'), prevA = a.value, prevB = $('#cic-b').value;
  a.innerHTML = qs.map(q => '<option value="' + esc(q.id) + '">' + esc((q.emoji ? q.emoji + ' ' : '') + q.text) + '</option>').join('');
  a.value = cicPre && qs.some(q => q.id === cicPre) ? cicPre : prevA && qs.some(q => q.id === prevA) ? prevA : (qs.find(q => q.total) || qs[0] || {}).id || '';
  cicPre = '';
  cicFillB(prevB);
  cicRender();
}
function cicFillB(prev){
  const qs = ciSt.questions || [], a = $('#cic-a').value, b = $('#cic-b');
  b.innerHTML = '<option value="__sleep">🌙 Slaap (Slaappraat)</option><option value="__cal">📅 Agenda (hoe druk)</option>' + qs.filter(q => q.id !== a).map(q => '<option value="' + esc(q.id) + '">' + esc((q.emoji ? q.emoji + ' ' : '') + q.text) + '</option>').join('');
  b.value = prev && [...b.options].some(o => o.value === prev) ? prev : '__sleep';
}
function cicRange(v){ cicRangeV = v; document.querySelectorAll('#cic-range button').forEach(b => { const on = +b.dataset.v === v; b.classList.toggle('on', on); b.setAttribute('aria-checked', on ? 'true' : 'false'); }); cicRender(); }
/** Gemiddelde per dag: {dag: [ruw gemiddelde, 0..1]}. Zuiver. */
function ciDayValues(q, h){ const by = ciByDay(h), out = {}; Object.keys(by).forEach(d => { const vs = by[d].map(a => +a.v); if (vs.length) { const v = ciAvg(vs); out[d] = [v, ciNorm(q, v)]; } }); return out; }
/** Twee groepen dagen naast elkaar; te weinig dagen = eerlijk zeggen. */
function cicGroups(q, ga, gb, la, lb){
  const show = (g, l) => '<div><small>' + esc(l) + '</small>' + (g.length >= 3 ? '<b>' + esc(ciNum(ciAvg(g)) + ' ' + (ciNearest(q, ciAvg(g)).emoji || '')) + '</b><small>gemiddeld, ' + g.length + ' dagen</small>' : '<b>–</b><small>te weinig dagen (' + g.length + ')</small>') + '</div>';
  return '<div class="ci-grp">' + show(ga, la) + show(gb, lb) + '</div>' + (ga.length >= 3 && gb.length >= 3 ? '<p class="note">Verschil: ' + esc(ciNum(Math.abs(ciAvg(ga) - ciAvg(gb)))) + ' punt op een schaal van ' + esc(ciNum(ciRange(q)[0])) + ' tot ' + esc(ciNum(ciRange(q)[1])) + '. Bij weinig dagen kan dat ook toeval zijn.</p>' : '');
}
function cicRender(){
  const out = $('#cic-out'), qa = ciQ($('#cic-a').value);
  if ($('#cic-b').options.length && [...$('#cic-b').options].some(o => o.value === $('#cic-a').value)) cicFillB($('#cic-b').value);
  if (!qa) { out.innerHTML = '<div class="card"><p class="note" style="margin:0">Maak eerst een vraag en vul hem een paar keer in.</p></div>'; return; }
  const bv = $('#cic-b').value, now = new Date(), to = new Date(now.getFullYear(), now.getMonth(), now.getDate() + 1, 12), from = new Date(to); from.setDate(from.getDate() - cicRangeV);
  const F = ciDayKey(from), T = ciDayKey(to), A = ciDayValues(qa, ciHistory(qa.id, F, T)), an = qa.text;
  let h = '<div class="card ci-chart" data-c="' + esc(qa.color || 'primary') + '">';
  if (bv === '__sleep' || bv === '__cal') {
    const info = rdJson(Android.checkinDays(F, T), { days: {} }), days = info.days || {};
    if (bv === '__cal' && !info.cal) {
      out.innerHTML = '<div class="card"><h2>Agenda</h2><p class="note">Om je agenda ernaast te zetten, mag de app hem lezen: alleen lezen, het blijft op je telefoon.</p><button class="big" onclick="Android.permRequest(\'calendar\')">Toegang tot de agenda geven</button></div>';
      return;
    }
    const ga = [], gb = [];
    Object.keys(A).forEach(d => { const x = days[d] || {};
      if (bv === '__sleep') { if (x.sleep) (x.sleep.min < 420 ? ga : gb).push(A[d][0]); }
      else (((x.cal || {}).busy || 0) >= 240 ? ga : gb).push(A[d][0]); });
    if (bv === '__sleep') {
      h += '<h2>' + esc(an) + ' en je slaap</h2><p class="ci-big-txt">' + esc(ga.length + gb.length ? 'Op ' + (ga.length + gb.length) + ' dagen is er een antwoord én een nacht van Slaappraat.' : 'Nog geen dagen met een antwoord én een nacht van Slaappraat.') + '</p>' +
        cicGroups(qa, ga, gb, 'Na een korte nacht (Slaappraat luisterde minder dan 7 uur)', 'Na een langere nacht (7 uur of meer)') +
        '<p class="note">Slaappraat meet hoe lang hij luisterde, niet hoe goed je sliep. Hij bewaart nachten 1 tot 4 weken.</p>';
    } else {
      h += '<h2>' + esc(an) + ' en je agenda</h2><p class="ci-big-txt">' + esc(Object.keys(A).length + ' dagen met een antwoord.') + '</p>' + cicGroups(qa, ga, gb, 'Drukke dagen (4 uur of meer in je agenda)', 'Rustigere dagen (minder dan 4 uur)');
    }
  } else {
    const qb = ciQ(bv); if (!qb) { out.innerHTML = ''; return; }
    const B = ciDayValues(qb, ciHistory(qb.id, F, T)), both = Object.keys(A).filter(d => B[d]).sort();
    h += '<h2>' + esc(an) + ' en ' + esc(qb.text) + '</h2><p class="ci-big-txt">' + esc(both.length ? 'Op ' + both.length + ' dagen allebei ingevuld.' : 'Nog geen dagen waarop je allebei invulde.') + '</p>';
    if (both.length) {
      const W = 320, H = 120, x = i => 6 + i * (W - 12) / Math.max(1, both.length - 1), y = v => 8 + (1 - v) * (H - 24);
      const line = (M, cls) => '<polyline class="' + cls + '" points="' + both.map((d, i) => x(i).toFixed(1) + ',' + y(M[d][1]).toFixed(1)).join(' ') + '"/>';
      const desc = an + ' en ' + qb.text + ' per dag, allebei van laag naar hoog op hun eigen schaal.';
      h += '<svg viewBox="0 0 ' + W + ' ' + H + '" role="img" aria-label="' + esc(desc) + '"><line class="ci-gl" x1="0" x2="' + W + '" y1="' + y(1) + '" y2="' + y(1) + '"/><line class="ci-gl" x1="0" x2="' + W + '" y1="' + y(0) + '" y2="' + y(0) + '"/>' + line(A, 'ci-la') + line(B, 'ci-lb') + '</svg>' +
        '<p class="note"><span class="ci-key"><i style="background:var(--qc)"></i>' + esc(an) + '</span><span class="ci-key"><i style="background:var(--ci-orange)"></i>' + esc(qb.text) + '</span></p>' +
        '<details><summary>Als tabel</summary><table class="ci-tbl"><thead><tr><th scope="col">Dag</th><th scope="col">' + esc(an) + '</th><th scope="col">' + esc(qb.text) + '</th></tr></thead><tbody>' +
        both.map(d => '<tr><td>' + esc(ciShort(ciDate(d))) + '</td><td>' + esc(ciOptText(ciNearest(qa, A[d][0]))) + '</td><td>' + esc(ciOptText(ciNearest(qb, B[d][0]))) + '</td></tr>').join('') + '</tbody></table></details>';
      const hi = both.filter(d => A[d][1] >= .5).map(d => B[d][0]), lo = both.filter(d => A[d][1] < .5).map(d => B[d][0]);
      h += '<p class="note" style="margin-top:10px">' + esc(qb.text) + ', gemiddeld:</p>' + cicGroups(qb, hi, lo, 'Op dagen dat "' + an + '" in de bovenste helft zat', 'Op de andere dagen');
    }
  }
  out.innerHTML = h + '<p class="note">Dit laat alleen zien wat samen voorkomt, niet wat de oorzaak is.</p></div>';
}

/* Vanuit Zoeken ({q, p}) of de assistent ({q, month}): die vraag, in die maand, bij die dag */
function ciKeyDay(key){ if (/^\d{4}-W\d{2}$/.test(key)) return ciDayKey(ciWeekStart(key)); if (/^\d{4}-\d{2}$/.test(key)) return key + '-01'; return String(key || '').slice(0, 10); }
function ciFromHit(x){
  ciLoad();
  const q = ciQ(x.q); if (!q) { jumpTo('checkin'); return; }
  const d = ciDate(x.p ? ciKeyDay(x.p) : x.month ? x.month + '-01' : '') || new Date();
  ciqId = q.id;
  ciqMonthAt = new Date(d.getFullYear(), q.rhythm === 'monthly' ? 0 : d.getMonth(), 1, 12);
  ciqSelKey = x.p ? (CI_DAYRHYTHM(q.rhythm) ? x.p.slice(0, 10) : x.p) : '';
  jumpTo('checkinq');
}
