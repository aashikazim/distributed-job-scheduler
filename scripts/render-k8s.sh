#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
overlay=${1:?Usage: render-k8s.sh local|prod|smoke [git-sha]}
tag=${2:-$(git rev-parse --short HEAD)}
[[ "$overlay" == local || "$overlay" == prod || "$overlay" == smoke ]] || { echo 'Overlay must be local, prod, or smoke' >&2; exit 1; }
[[ "$tag" =~ ^[0-9a-f]{7,40}$ ]] || { echo 'Image tag must be a git SHA (7-40 lowercase hex characters)' >&2; exit 1; }
if [[ "$overlay" == local || "$overlay" == smoke ]]; then
  directory="k8s/overlays/local"
  [[ "$overlay" != smoke ]] || directory="k8s/smoke"
  kubectl kustomize "$directory" | sed -e "s|image: job-scheduler:local|image: job-scheduler:$tag|g" \
    -e "s|image: job-scheduler-smoke:local|image: job-scheduler-smoke:$tag|g"
else
  kubectl kustomize k8s/overlays/prod | sed "s|:REPLACE_WITH_GIT_SHA|:$tag|g"
fi
