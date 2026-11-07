#!/usr/bin/env bash
# Bewijst dat ./publish.sh met VOORBEREIDEN alleen klaarzet (versie, wijzigingslog, vingerafdruk) en nooit pusht, dat
# twee keer voorbereiden dezelfde vingerafdruk geeft, en dat een andere wijzigingsregel een andere vingerafdruk geeft.
# Zo slaat Publiceren de interfacetests alleen over als ze op precies dezelfde bron, versie en wijzigingslog draaiden.
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
cp "$root/publish.sh" . && cp "$root/tools/check-publish-source.sh" tools/
printf 'VERSION_NAME=1.10\nVERSION_CODE=11\nNATIVE_LEVEL=5\n' > VERSION
echo '[]' > changelog.json
echo '{}' > update/update.json
echo 'build/' > .gitignore
echo een > app/bron.txt
git add -A && git commit -qm "Versie 1.10" && git push -q origin HEAD:main
echo twee >> app/bron.txt
echo "Nieuw: iets" > wijzigingen/iets.txt
git add -A && git commit -qm "Werk" && git push -q origin HEAD:main
bron=$(git rev-parse HEAD)

voorbereiden(){ # $1 = map; voert publish.sh uit zoals de taak interface op GitHub
  rm -rf "$1" && git clone -q "$tmp/origin.git" "$1" 2>/dev/null
  (cd "$1" && git checkout -q "$bron" && GITHUB_ACTIONS=true AGENT=GitHub AUTO_NOTES=1 TESTED_SOURCE_SHA="$bron" \
    VOORBEREIDEN=build/voorbereid.txt ./publish.sh > "$1.log" 2>&1) || { cat "$1.log"; fout "voorbereiden mislukt"; }
  cat "$1/build/voorbereid.txt"
}

a=$(voorbereiden "$tmp/a")
[[ $a =~ ^[0-9a-f]{64}$ ]] || fout "geen vingerafdruk: $a"
grep -q '^VERSION_NAME=1.11$' "$tmp/a/VERSION" && grep -q '^VERSION_CODE=12$' "$tmp/a/VERSION" && grep -q '^NATIVE_LEVEL=6$' "$tmp/a/VERSION" || fout "versie niet klaargezet"
python3 -c "import json,sys; e=json.load(open(sys.argv[1]))[0]; assert e['version']=='1.11' and e['changes']==['Nieuw: iets'], e" "$tmp/a/changelog.json" || fout "wijzigingslog niet klaargezet"
[ ! -e "$tmp/a/wijzigingen/iets.txt" ] || fout "wijzigingsregels niet weggehaald"
[ "$(git -C "$tmp/origin.git" rev-parse main)" = "$bron" ] || fout "voorbereiden pushte naar main"
! git -C "$tmp/origin.git" rev-parse -q --verify refs/heads/publiceren-slot >/dev/null || fout "voorbereiden nam het publicatieslot"

b=$(voorbereiden "$tmp/b")
[ "$a" = "$b" ] || fout "twee keer voorbereiden gaf een andere vingerafdruk ($a / $b)"

echo "Nieuw: iets anders" > wijzigingen/iets.txt
git commit -qam "Andere regel" && git push -q origin HEAD:main
bron=$(git rev-parse HEAD)
c=$(voorbereiden "$tmp/c")
[ "$a" != "$c" ] || fout "een andere wijzigingsregel gaf dezelfde vingerafdruk"

echo "uitleg" > LEESMIJ.txt
git add -A && git commit -qm "Versie 1.11" && git push -q origin HEAD:main  # alsof er net gepubliceerd is: update/ onveranderd,
echo '{"v":2}' > update/update.json && git commit -qam "update" && git push -q origin HEAD:main  # LASTREL = deze commit
bron=$(git rev-parse HEAD)
[ "$(voorbereiden "$tmp/d")" = geen ] || fout "zonder wijziging aan de app moet voorbereiden 'geen' geven"

if (cd "$tmp/d" && echo vuil >> app/bron.txt && VOORBEREIDEN=build/v2.txt TESTED_SOURCE_SHA="$bron" ./publish.sh > "$tmp/vuil.log" 2>&1); then
  fout "voorbereiden met een vuile werkmap ten onrechte gelukt"
fi
grep -q "schone werkmap" "$tmp/vuil.log" || { cat "$tmp/vuil.log"; fout "vuile werkmap: verkeerde melding"; }
echo 'Voorbereiden geslaagd: zelfde vingerafdruk bij dezelfde bron, andere bij een andere regel, nooit een push'
