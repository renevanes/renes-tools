/* ---------- App-slot ---------- */
function setInert(on){ document.querySelectorAll('.screen, #modal, #sheet').forEach(e => { e.inert = on; }); if (on && document.activeElement) document.activeElement.blur(); }
function showLock(){
  setInert(true); $('#lockscr').classList.add('on'); $('#lock-msg').textContent = '';
  const lk = rdJson(Android.lockState(), {});
  lockCode = '';
  // Met app-code: cijfers intikken (de vingerafdruk alleen als je erop tikt); zonder: de vergrendeling van de telefoon
  $('#lock-pad').style.display = lk.code ? '' : 'none';
  $('#lock-btn').style.display = lk.code ? 'none' : '';
  $('#lock-bio').style.display = lk.code && lk.bio ? '' : 'none';
  if (lk.code) { lockRenderPad(); lockDots(); setTimeout(() => { const k = document.querySelector('#lock-keys button'); if (k) k.focus(); }, 50); }
  else setTimeout(() => Android.lockUnlock(), 250);
}
window.onLock = function(){ if (!$('#lockscr').classList.contains('on')) showLock(); };
window.onUnlocked = function(){ lockCode = ''; $('#lockscr').classList.remove('on'); setInert(false); };

/* ---------- App-code ---------- */
let lockCode = '', lockBusy = false;
function lockRenderPad(){
  if ($('#lock-keys').childElementCount) return;
  const keys = ['1','2','3','4','5','6','7','8','9','⌫','0','✓'];
  $('#lock-keys').innerHTML = keys.map(k => '<button type="button" data-k="' + k + '" aria-label="' + (k === '⌫' ? 'Wissen' : k === '✓' ? 'Ontgrendelen' : k) + '">' + k + '</button>').join('');
  $('#lock-keys').addEventListener('click', e => { const b = e.target.closest('button'); if (b) lockKey(b.dataset.k); });
}
function lockDots(){
  const n = lockCode.length;
  $('#lock-dots').textContent = n ? '●'.repeat(n) : '';
  $('#lock-dots').classList.toggle('empty', !n);
}
function lockKey(k){
  if (lockBusy) return;
  if (k === '⌫') lockCode = lockCode.slice(0, -1);
  else if (k === '✓') { lockSubmit(); return; }
  else if (lockCode.length < 12) lockCode += k;
  $('#lock-msg').textContent = '';
  lockDots();
}
function lockSubmit(){
  if (!lockCode) return;
  lockBusy = true;
  const code = lockCode; lockCode = ''; lockDots();
  Android.lockTryCode(code); // antwoord via onLockResult (het rekenwerk gebeurt in de app)
}
window.onLockResult = function(r){
  lockBusy = false;
  if (r === '' || r === 'neutral') return; // open, of de app gaat naar de neutrale versie
  $('#lock-msg').textContent = r;
  const p = $('#lock-dots'); p.classList.remove('shake'); void p.offsetWidth; p.classList.add('shake');
};
document.addEventListener('keydown', e => {
  if (!$('#lockscr').classList.contains('on') || $('#lock-pad').style.display === 'none') return;
  if (/^[0-9]$/.test(e.key)) lockKey(e.key);
  else if (e.key === 'Backspace') lockKey('⌫');
  else if (e.key === 'Enter') lockKey('✓');
  else return;
  e.preventDefault();
});
window.onLockFail = function(msg){ $('#lock-msg').textContent = msg || 'Niet ontgrendeld'; };

