#!/usr/bin/env bash
# Publishes jorlan-stlib (root) and every ScalablyTyped-generated per-npm-library
# facade (react, mui, emotion, etc.) to GitHub Packages, so CI (and any other
# consumer) resolves them remotely without ever needing to run npm or the
# ScalablyTyped codegen itself.
#
# Run this once per stLib change, by hand -- it is NOT wired into CI. The whole
# point of stLib being a separate project is that codegen is infrequent; CI
# should only ever resolve already-published artifacts.
#
# Background: ExternalNpmPlugin publishes each per-npm-library facade directly
# into the local Ivy cache during codegen; they are not separate sbt projects,
# so plain `sbt publish` (root only) or a wildcard project reference
# (`*/publish`, which errors: "No such setting/task") cannot reach them. This
# script re-uploads the already-generated local Ivy artifacts to the same
# GitHub Package Registry that `publishTo` in build.sbt already points the
# root project at.
set -euo pipefail
cd "$(dirname "$0")"

: "${GITHUB_TOKEN:=$(gh auth token)}"
REMOTE="https://maven.pkg.github.com/rleibman/jorlan"

echo "==> Publishing root jorlan-stlib artifact"
sbt publish

echo "==> Publishing generated per-library facades"
IVY_BASE="$HOME/.ivy2/local/org.scalablytyped.net.leibman.jorlan"
GROUP_PATH="org/scalablytyped/net/leibman/jorlan"

upload() {
  local src=$1 url=$2
  [[ -f "$src" ]] || return 0
  local code
  code=$(curl -s -o /dev/null -w "%{http_code}" -u "rleibman:$GITHUB_TOKEN" "$url")
  if [[ "$code" == "200" ]]; then
    echo "    skip (exists): $(basename "$url")"
  else
    echo "    upload: $(basename "$url")"
    curl -sf -u "rleibman:$GITHUB_TOKEN" -T "$src" "$url" >/dev/null
  fi
}

for module_dir in "$IVY_BASE"/*/; do
  module=$(basename "$module_dir")
  for version_dir in "$module_dir"*/; do
    version=$(basename "$version_dir")
    dest="$REMOTE/$GROUP_PATH/$module/$version"

    echo "-- $module $version"
    upload "$version_dir/poms/$module.pom"        "$dest/$module-$version.pom"
    upload "$version_dir/jars/$module.jar"        "$dest/$module-$version.jar"
    upload "$version_dir/srcs/$module-sources.jar" "$dest/$module-$version-sources.jar"
    upload "$version_dir/docs/$module-javadoc.jar" "$dest/$module-$version-javadoc.jar"
  done
done

echo "==> Done"
