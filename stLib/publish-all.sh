#!/usr/bin/env bash
# Generates the ScalablyTyped facades and publishes the whole stLib project
# (root jorlan-stlib plus every auto-generated per-npm-library artifact:
# react, mui, emotion, etc.) to the LOCAL ivy cache.
#
# ExternalNpmPlugin manufactures the per-npm-library artifacts directly via
# Ivy during codegen -- they are not separate sbt projects, so there is no
# sbt task (and no `*/publish` wildcard) that reaches them individually or
# remotely. `publishLocal` is the only supported entry point that produces
# all of them, which is why downstream builds (web, server/debian:packageBin)
# only ever need the local ivy cache, not a remote repository.
set -euo pipefail
cd "$(dirname "$0")"

npm ci
sbt publishLocal
