#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
consumer_source="$repo_root/smoke/android-consumer"
prepublish=false

if [[ "${1:-}" == "--prepublish" ]]; then
  prepublish=true
  shift
fi

if (($# == 0)); then
  set -- :app:assembleDebug :app:lintDebug :app:assembleRelease
fi

gradle_options=(--no-daemon --no-build-cache)
if [[ "${PICA_OFFLINE:-0}" == "1" ]]; then
  gradle_options+=(--offline)
elif [[ "${PICA_REFRESH_DEPENDENCIES:-1}" == "1" ]]; then
  gradle_options+=(--refresh-dependencies)
fi

if [[ "$prepublish" == true ]]; then
  local_repository="$(mvn -q help:evaluate -Dexpression=settings.localRepository -DforceStdout)"
  mvn -B -ntp -DskipTests install

  staging_directory="$(mktemp -d)"
  trap 'rm -rf "$staging_directory"' EXIT
  mkdir -p "$staging_directory/repository/io/github"
  cp -a "$local_repository/io/github/jukomu" "$staging_directory/repository/io/github/"
  cp -a "$consumer_source" "$staging_directory/consumer"

  # The temporary copy is explicitly a pre-publish D8/R8 smoke. The checked-in
  # consumer remains Central-only and is used for public-artifact acceptance.
  perl -0pi -e 's/(dependencyResolutionManagement\s*\{.*?repositories\s*\{\n)/$1        maven { url = uri(System.getenv("PICA_PREPUBLISH_REPO")) }\n/s' \
    "$staging_directory/consumer/settings.gradle.kts"
  PICA_PREPUBLISH_REPO="$staging_directory/repository" \
    gradle -p "$staging_directory/consumer" "${gradle_options[@]}" \
    --dependency-verification=off "$@"
  if [[ -d "$staging_directory/consumer/app/build" ]]; then
    rm -rf "$consumer_source/app/build"
    cp -a "$staging_directory/consumer/app/build" "$consumer_source/app/build"
  fi
  if [[ "${PICA_GENERATE_VERIFICATION_METADATA:-0}" == "1" ]]; then
    cp "$staging_directory/consumer/gradle/verification-metadata.xml" \
      "$consumer_source/gradle/verification-metadata.xml"
  fi
else
  gradle -p "$consumer_source" "${gradle_options[@]}" \
    --dependency-verification=strict "$@"
fi
