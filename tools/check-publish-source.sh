#!/usr/bin/env bash
# Onder het publicatieslot aanroepen, na iedere merge. Alleen geteste bron mag ondertekend worden.
set -euo pipefail
tested_source=${TESTED_SOURCE_SHA:-}
if [[ ! $tested_source =~ ^[0-9a-f]{40}$ ]] || ! git cat-file -e "$tested_source^{commit}" 2>/dev/null; then
  echo "De geteste broncommit ontbreekt of is ongeldig; publiceer pas na een geslaagde telefoontest." >&2
  exit 1
fi
source_paths=(. ':!VERSION' ':!changelog.json' ':!update' ':!wijzigingen')
if ! git diff --quiet "$tested_source" HEAD -- "${source_paths[@]}"; then
  echo "De bron is sinds de telefoontest veranderd. De volgende publicatierun moet die bron eerst testen." >&2
  git diff --name-only "$tested_source" HEAD -- "${source_paths[@]}" >&2
  exit 1
fi
echo "Publicatiebron komt overeen met de geslaagde telefoontest ($tested_source)"
