#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
api_classes="$repo_root/picacomic-api/target/classes"
core_classes="$repo_root/picacomic-core/target/classes"
signature_file="$repo_root/api-signature/public-api.txt"
classpath="$api_classes:$core_classes"

if [[ ! -d "$api_classes" || ! -d "$core_classes" ]]; then
  printf 'Build the Maven reactor before checking the public surface.\n' >&2
  exit 1
fi

mapfile -t api_types <<'EOF'
io.github.jukomu.picacomic.api.client.DownloadResult
io.github.jukomu.picacomic.api.client.IPicaClient
io.github.jukomu.picacomic.api.client.PicaImageRequest
io.github.jukomu.picacomic.api.client.PicaRequest
io.github.jukomu.picacomic.api.enums.Category
io.github.jukomu.picacomic.api.enums.ImageQuality
io.github.jukomu.picacomic.api.enums.OrderBy
io.github.jukomu.picacomic.api.enums.PicaSessionState
io.github.jukomu.picacomic.api.enums.TimeOption
io.github.jukomu.picacomic.api.exception.ImageFetchException
io.github.jukomu.picacomic.api.exception.ImageFetchException$Reason
io.github.jukomu.picacomic.api.exception.NetworkException
io.github.jukomu.picacomic.api.exception.ParseResponseException
io.github.jukomu.picacomic.api.exception.PicaApiException
io.github.jukomu.picacomic.api.exception.PicaApiException$Reason
io.github.jukomu.picacomic.api.exception.PicaComicException
io.github.jukomu.picacomic.api.exception.ResponseException
io.github.jukomu.picacomic.api.model.PicaAlbum
io.github.jukomu.picacomic.api.model.PicaContentPage
io.github.jukomu.picacomic.api.model.PicaImage
io.github.jukomu.picacomic.api.model.PicaPhoto
io.github.jukomu.picacomic.api.model.PicaSessionSnapshot
io.github.jukomu.picacomic.api.model.PicaUserInfo
io.github.jukomu.picacomic.api.model.SearchQuery
io.github.jukomu.picacomic.api.model.SearchQuery$Builder
io.github.jukomu.picacomic.api.strategy.IAlbumPathGenerator
io.github.jukomu.picacomic.api.strategy.IImagePathGenerator
io.github.jukomu.picacomic.api.strategy.IPhotoPathGenerator
io.github.jukomu.picacomic.core.PicaComic
io.github.jukomu.picacomic.core.config.PicaConfiguration
io.github.jukomu.picacomic.core.config.PicaConfiguration$Builder
EOF

tmp_signature="$(mktemp)"
trap 'rm -f "$tmp_signature"' EXIT
for type in "${api_types[@]}"; do
  printf '## %s\n' "$type" >> "$tmp_signature"
  javap -classpath "$classpath" -public "$type" \
    | sed '1{/^Compiled from /d;}' >> "$tmp_signature"
done

if [[ "${UPDATE_API_SIGNATURE:-0}" == "1" ]]; then
  mkdir -p "$(dirname "$signature_file")"
  cp "$tmp_signature" "$signature_file"
else
  diff -u "$signature_file" "$tmp_signature"
fi

while IFS= read -r class_file; do
  relative="${class_file#"$core_classes/"}"
  type="${relative%.class}"
  type="${type//\//.}"
  case "$type" in
    io.github.jukomu.picacomic.core.internal.*)
      continue
      ;;
    io.github.jukomu.picacomic.core.PicaComic|io.github.jukomu.picacomic.core.config.PicaConfiguration|io.github.jukomu.picacomic.core.config.PicaConfiguration\$Builder)
      continue
      ;;
  esac

  declaration="$(javap -classpath "$classpath" -public "$type" 2>/dev/null | sed -n '2p')"
  if [[ "$declaration" == public\ * ]]; then
    printf 'Unexpected public core type: %s\n' "$type" >&2
    exit 1
  fi
done < <(find "$core_classes" -type f -name '*.class' | sort)

printf 'Public API signature and core surface checks passed.\n'
