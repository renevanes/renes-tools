/* ---------- Radio pauzeren en terugspoelen ---------- */
function rdShift(w){ Android.radioShift(w); setTimeout(rdPollOnce, 250); }
function fmtBehind(s){ s = Math.round(s); return (s >= 3600 ? Math.floor(s / 3600) + ':' + String(Math.floor(s / 60) % 60).padStart(2, '0') : Math.floor(s / 60)) + ':' + String(s % 60).padStart(2, '0'); }
function rdShowShift(){
  const sh = rdState.shift, st = rdState.status;
  const on = !!sh && (st === 'playing' || st === 'paused' || st === 'connecting');
  $('#rd-shift').style.display = on ? 'flex' : 'none';
  if (!on) return;
  const behind = sh.behind > 8; // de speler loopt bewust een paar seconden achter (voorraadje)
  const pos = $('#rd-shpos');
  pos.textContent = behind ? '−' + fmtBehind(sh.behind) : 'Live';
  pos.classList.toggle('behind', behind);
  pos.setAttribute('aria-label', behind ? fmtBehind(sh.behind) + ' achter live' : 'Live');
  $('#rd-fwd').disabled = !behind; $('#rd-live').disabled = !behind;
}

