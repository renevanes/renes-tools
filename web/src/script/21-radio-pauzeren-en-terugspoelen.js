/* ---------- Radio pauzeren en terugspoelen ---------- */
function rdShift(w){ Android.radioShift(w); setTimeout(rdPollOnce, 250); }
function fmtBehind(s){ s = Math.round(s); return (s >= 3600 ? Math.floor(s / 3600) + ':' + String(Math.floor(s / 60) % 60).padStart(2, '0') : Math.floor(s / 60)) + ':' + String(s % 60).padStart(2, '0'); }
