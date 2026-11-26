#!/usr/bin/env bash
# Bewijst "alleen de geteste bron" (publish.sh, tools/publicatie-samenvoegen.sh): ging main tijdens het testen verder, dan
# komt de versie van de geteste bron op main zonder iets van main terug te draaien; de volgende run ziet het nieuwere werk
# als nieuw, en een oudere run die later klaar is publiceert niets meer. Wegwerp-repositories, geen sleutel nodig.
set -euo pipefail
root=$(cd "$(dirname "$0")/.." && pwd)
tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT
fout(){ echo "FOUT: $*"; exit 1; }
export GIT_AUTHOR_NAME=Test GIT_AUTHOR_EMAIL=test@example.invalid GIT_COMMITTER_NAME=Test GIT_COMMITTER_EMAIL=test@example.invalid
unset VOORBEREIDEN UI_GETEST AUTO_NOTES MAJOR CLAUDE_SESSION_URL 2>/dev/null || true

git init -q --bare -b main "$tmp/origin.git"
git clone -q "$tmp/origin.git" "$tmp/werk" 2>/dev/null
cd "$tmp/werk"
mkdir -p app tools update wijzigingen
cp "$root/publish.sh" . && cp "$root/tools/"publicatie-* "$root/tools/check-publish-source.sh" tools/
printf 'VERSION_NAME=1.10\nVERSION_CODE=11\nNATIVE_LEVEL=5\n' > VERSION
echo '[{"version":"1.10","changes":["oud"]}]' > changelog.json
echo '{"versionName":"1.10","versionCode":11,"nativeLevel":5}' > update/update.json
echo 'build/' > .gitignore
echo een > app/bron.txt
git add -A && git commit -qm "Versie 1.10" && git push -q origin HEAD:main

# X: getest werk; daarna Y op main (nieuw werk met een eigen wijzigingsregel) terwijl X getest wordt
echo twee >> app/bron.txt; echo "Regel van X" > wijzigingen/x.txt
git add -A && git commit -qm "Werk X" && git push -q origin HEAD:main
x=$(git rev-parse HEAD)
echo "Regel van X" > wijzigingen/gedeeld.txt; git add -A; git commit -qm "Gedeelde regel" && git push -q origin HEAD:main
x=$(git rev-parse HEAD)
echo drie > app/later.txt; echo "Regel van Y" > wijzigingen/y.txt; echo "Nog een regel van Y" >> wijzigingen/gedeeld.txt
git add -A && git commit -qm "Werk Y" && git push -q origin HEAD:main
y=$(git rev-parse HEAD)

# De versiecommit op X, zoals publish.sh hem maakt (versie, wijzigingslog, update/, de verwerkte regel weg)
git checkout -q --detach "$x"
printf 'VERSION_NAME=1.11\nVERSION_CODE=12\nNATIVE_LEVEL=6\n' > VERSION
echo '[{"version":"1.11","changes":["Regel van X"]},{"version":"1.10","changes":["oud"]}]' > changelog.json
echo '{"versionName":"1.11","versionCode":12,"nativeLevel":6}' > update/update.json
git rm -q wijzigingen/x.txt wijzigingen/gedeeld.txt
git add -A && git commit -qm "Versie 1.11"
v=$(git rev-parse HEAD)
m=$(tools/publicatie-samenvoegen.sh "$y" "$v" "Versie 1.11")
[[ $m =~ ^[0-9a-f]{40}$ ]] || fout "geen samenvoegcommit"
[ "$(git rev-parse "$m^1")" = "$y" ] && [ "$(git rev-parse "$m^2")" = "$v" ] || fout "verkeerde ouders"
# Niets van main teruggedraaid; versie en update/ van de versiecommit; alleen de verwerkte regel weg
git diff --quiet "$y" "$m" -- app || fout "bron van main veranderd"
[ "$(git show "$m:VERSION")" = "$(git show "$v:VERSION")" ] && [ "$(git show "$m:update/update.json")" = "$(git show "$v:update/update.json")" ] \
  && [ "$(git show "$m:changelog.json")" = "$(git show "$v:changelog.json")" ] || fout "versie of update/ niet overgenomen"
! git cat-file -e "$m:wijzigingen/x.txt" 2>/dev/null || fout "verwerkte regel bleef staan"
git cat-file -e "$m:wijzigingen/y.txt" || fout "regel van later werk weg"
[ "$(git show "$m:wijzigingen/gedeeld.txt")" = "Nog een regel van Y" ] || fout "aangevulde regel op main: alleen de nieuwe regel hoort te blijven"
# De volgende run (bron Y, main = M): de laatste versie is V, dus Y telt als nieuw
git push -q origin "$m:refs/heads/main"
laatste=$(git log -1 --format=%H "$m" -- update/update.json)
[ "$laatste" = "$v" ] || fout "laatste versie niet gevonden ($laatste)"
! git diff --quiet "$laatste" "$m" -- app || fout "het werk van Y telt niet als nieuw"

# Weigeren: een versiecommit die ook de bron verandert, en een bron die niet op main staat
git checkout -q --detach "$x"; echo stiekem >> app/bron.txt; git commit -qam "Versie met bron"
if tools/publicatie-samenvoegen.sh "$y" "$(git rev-parse HEAD)" x > /dev/null 2>&1; then fout "versiecommit met bron toegelaten"; fi
leeg=$(git hash-object -t tree /dev/null); los=$(git commit-tree "$leeg" -m los); vlos=$(git commit-tree "$leeg" -p "$los" -m "Versie los")
if tools/publicatie-samenvoegen.sh "$y" "$vlos" x > /dev/null 2>&1; then fout "bron buiten main toegelaten"; fi

# Daarna Z op main (terwijl Y nog getest wordt): de run van Y publiceert dan alleen de geteste bron
git checkout -q --detach "$m"; echo vier > app/z.txt; echo "Regel van Z" > wijzigingen/z.txt
git add -A && git commit -qm "Werk Z" && git push -q origin HEAD:main

# publish.sh: een run van X die pas klaar is nadat V er al staat, publiceert niets meer; een run van Y wel
probeer(){ # $1 = map, $2 = geteste bron
  rm -rf "$1" && git clone -q "$tmp/origin.git" "$1" 2>/dev/null
  (cd "$1" && git checkout -q "$2" && GITHUB_ACTIONS=true AGENT=GitHub AUTO_NOTES=1 TESTED_SOURCE_SHA="$2" \
    VOORBEREIDEN=build/voorbereid.txt ./publish.sh > "$1.log" 2>&1) || { cat "$1.log"; fout "voorbereiden mislukt"; }
  cat "$1/build/voorbereid.txt"
}
[ "$(probeer "$tmp/oud" "$x")" = geen ] || { cat "$tmp/oud.log"; fout "een al gepubliceerde bron opnieuw klaargezet"; }
grep -q "al in een nieuwere versie" "$tmp/oud.log" || { cat "$tmp/oud.log"; fout "geen melding bij een al gepubliceerde bron"; }
f=$(probeer "$tmp/y" "$y")
grep -q "geteste bron" "$tmp/y.log" || { cat "$tmp/y.log"; fout "de run van Y ging niet via alleen de geteste bron"; }
[[ $f =~ ^[0-9a-f]{64}$ ]] || fout "het werk van Y niet klaargezet"
grep -q '^VERSION_NAME=1.12$' "$tmp/y/VERSION" || fout "versie na 1.11 niet 1.12"
python3 -c "import json,sys; e=json.load(open(sys.argv[1]))[0]; assert sorted(e['changes'])==['Nog een regel van Y','Regel van Y'] and e['version']=='1.12', e" "$tmp/y/changelog.json" || fout "wijzigingslog van Y klopt niet"
echo 'Alleen de geteste bron geslaagd: niets teruggedraaid, nieuwer werk volgt, oudere run publiceert niets'
