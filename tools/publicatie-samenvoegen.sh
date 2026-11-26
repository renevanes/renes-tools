#!/usr/bin/env bash
# Een versie van een geteste, oudere bron op main zetten terwijl main intussen verder ging (publish.sh, "alleen de
# geteste bron"). De versiecommit staat op de geteste bron; deze samenvoegcommit zet hem op main zonder iets van main
# terug te draaien: de bron blijft precies die van main, alleen VERSION, changelog.json en update/ komen uit de
# versiecommit, en de wijzigingsregels die in deze versie zitten gaan weg (die van later werk blijven staan).
#
#   tools/publicatie-samenvoegen.sh <main> <versiecommit> <bericht>   → drukt de nieuwe commit af (niets gepusht)
set -euo pipefail
[ "$#" -eq 3 ] || { echo 'Gebruik: publicatie-samenvoegen.sh main versiecommit bericht' >&2; exit 1; }
basis=$1; versie=$2; bericht=$3
[[ $basis =~ ^[0-9a-f]{40}$ && $versie =~ ^[0-9a-f]{40}$ ]] || { echo 'Ongeldige commit' >&2; exit 1; }
bron=$(git rev-parse "$versie^")
# Alleen een versie van een bron die al op main staat (de geteste commit is een voorouder van main)
git merge-base --is-ancestor "$bron" "$basis" || { echo 'De geteste bron staat niet op main' >&2; exit 1; }
# De versiecommit mag alleen versie, wijzigingslog, update/ en wijzigingsregels veranderen
anders=$(git diff --name-only "$bron" "$versie" -- . ':!VERSION' ':!changelog.json' ':!update' ':!wijzigingen')
[ -z "$anders" ] || { echo "De versiecommit verandert ook de bron: $anders" >&2; exit 1; }
index=$(mktemp)
trap 'rm -f "$index"' EXIT
export GIT_INDEX_FILE=$index
git read-tree "$basis"
git rm -r -q -f --cached --ignore-unmatch -- update VERSION changelog.json > /dev/null
git ls-tree -r "$versie" -- VERSION changelog.json update | git update-index --index-info
# Wijzigingsregels die deze versie verwerkte (weg in de versiecommit), ook weg op main. Per regel: wat op main staat en
# niet in de verwerkte versie van het bestand stond (later erbij gekomen), blijft staan voor de volgende versie.
verwerkt_lijst=$(mktemp); oud=$(mktemp)
trap 'rm -f "$index" "$verwerkt_lijst" "$oud"' EXIT
git diff -z --name-only --diff-filter=D "$bron" "$versie" -- wijzigingen > "$verwerkt_lijst"
while IFS= read -r -d '' f; do
  git cat-file -e "$basis:$f" 2>/dev/null || continue
  git cat-file blob "$bron:$f" > "$oud"
  rest=$(git cat-file blob "$basis:$f" | grep -Fvx -f "$oud" || true)
  if [ -z "$rest" ]; then
    git rm -q -f --cached --ignore-unmatch -- "$f" > /dev/null
  else
    blob=$(printf '%s\n' "$rest" | git hash-object -w --stdin)
    git update-index --cacheinfo "100644,$blob,$f"
  fi
done < "$verwerkt_lijst"
boom=$(git write-tree)
git commit-tree "$boom" -p "$basis" -p "$versie" -m "$bericht"
