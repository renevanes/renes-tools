#!/usr/bin/env bash
# Wordt door publish.sh ingelezen; alle wijzigingen aan het slot gebruiken compare-and-swap.
SLOT_TIP=""; SLOT_ERR=""
slot_read(){
  if git fetch -q --no-write-fetch-head origin "+$LOCK:refs/remotes/origin/publiceren-slot" 2>/dev/null; then
    git rev-parse --verify refs/remotes/origin/publiceren-slot
  else
    # Een onbereikbare server is geen vrij slot. Alleen een bevestigde ontbrekende ref is vrij.
    local rc=0
    git ls-remote --exit-code origin "$LOCK" >/dev/null 2>&1 || rc=$?
    [ "$rc" -eq 2 ] && return 0
    echo 'Publicatieslot niet leesbaar; publicatie gestopt' >&2; return 1
  fi
}
slot_push(){ # $1 = verwachte tip ("" = bestaat nog niet), $2 = boodschap; zet SLOT_TIP bij succes
  local parent=() c
  [ -n "$1" ] && parent=(-p "$1")
  c=$(git "${GIT_ID[@]}" commit-tree "$EMPTY_TREE" "${parent[@]}" -m "$2")
  SLOT_ERR=$(git push -q origin "$c:$LOCK" --force-with-lease="$LOCK:$1" 2>&1) && SLOT_TIP=$c
}
slot_take(){
  local start=$(date +%s) tip msg="" since fails=0
  while true; do
    [ $(( $(date +%s) - start )) -gt 7200 ] && { echo "Na 2 uur wachten is het slot nog bezet ($msg). Later opnieuw proberen."; exit 1; }
    tip=$(slot_read) || return 1
    msg=$([ -n "$tip" ] && git log -1 --format=%s "$tip" || echo vrij)
    if [[ "$msg" == *"[$TOKEN]"* ]]; then SLOT_TIP=$tip; echo "Publicatieslot genomen"; return 0; fi # eigen push kwam toch aan
    since=$(sed -n 's/^bezet \([0-9]*\) .*/\1/p' <<<"$msg"); since=${since:-0}
    # Eén poging (samenvoegen, bouwen, testen, publiceren) duurt 10-25 minuten; bij elke poging wordt de tijd in het
    # slot vernieuwd (slot_refresh). Een slot dat al 45 minuten niet vernieuwd is, is achtergebleven (bijv. vrijgeven
    # mislukt of de workflow gestopt) en wordt overgenomen.
    if [[ "$msg" == vrij* ]] || [ $(( $(date +%s) - since )) -gt 2700 ]; then
      [[ "$msg" == vrij* ]] || echo "Het slot staat al meer dan 45 minuten bezet ($msg): overnemen"
      if slot_push "$tip" "bezet $(date -u +%s) door $WHO [$TOKEN]"; then echo "Publicatieslot genomen"; return 0; fi
      fails=$((fails+1))
      [ $fails -ge 10 ] && { echo "Kan het publicatieslot niet zetten (pushrechten of netwerk?)"; exit 1; }
      sleep $((5 + RANDOM % 10))
    else
      fails=0; echo "Wacht op een andere publicatie: $msg"; sleep 30
    fi
  done
}
slot_free(){ # een paar keer proberen; alleen vrijgeven zolang het slot nog van ons is
  [ -n "$SLOT_TIP" ] || return 0
  local i tip
  for i in 1 2 3 4 5; do
    if tip=$(slot_read); then
      if [ -z "$tip" ] || [[ "$(git log -1 --format=%s "$tip")" != *"[$TOKEN]"* ]]; then
        echo "Publicatieslot is intussen vrij of van een ander; niet gewijzigd"; SLOT_TIP=""; return 0
      fi
      if slot_push "$tip" "vrij $(date -u +%s)"; then echo "Publicatieslot vrijgegeven"; SLOT_TIP=""; return 0; fi
    fi
    [ "$i" -lt 5 ] && sleep $((i * 5))
  done
  echo "::warning::Publicatieslot vrijgeven mislukt (${SLOT_ERR//$'\n'/ }); een volgende publicatie neemt het na 45 minuten over"
  SLOT_TIP=""
}
slot_refresh(){ # bij elke poging de tijd in het slot vernieuwen, zodat een lange publicatie niet wordt overgenomen
  [ -z "${VOORBEREIDEN:-}" ] || return 0
  local tip
  tip=$(slot_read) || return 1
  if [ -z "$SLOT_TIP" ] || [ "$tip" != "$SLOT_TIP" ] || [[ "$(git log -1 --format=%s "$tip")" != *"[$TOKEN]"* ]]; then
    echo 'Publicatieslot verloren; publicatie gestopt' >&2; return 1
  fi
  if ! slot_push "$tip" "bezet $(date -u +%s) door $WHO [$TOKEN]"; then
    echo "Publicatieslot niet vernieuwd; publicatie gestopt (${SLOT_ERR//$'\n'/ })" >&2; return 1
  fi
}
