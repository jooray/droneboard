#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-only
# Droneboard release helper.
#
#   tools/release.sh prepare 0.2.0   bump the version, build the signed APK, check its certificate
#   tools/release.sh publish         commit, tag, push and create the GitHub release (asks first)
#
# "prepare" expects docs/RELEASE_NOTES_<version>.md to exist. The version code is
# 5000 + (minor - 1) * 100 + patch for 0.x (0.1.18 -> 5018, 0.2.0 -> 5100), and must grow.
# Signing reads ~/.apk-signing-keystore/signing.properties (or $APP_SIGNING_PROPERTIES), see
# app/build.gradle.kts. Set EXPECTED_CERT_SHA256 to have the certificate of the APK checked.
# Publishing to app stores is not part of this script.
set -euo pipefail
cd "$(dirname "$0")/.."

GRADLE_FILE=app/build.gradle.kts
die() { echo "release: $*" >&2; exit 1; }

current_version() { sed -n 's/^ *versionName = "\(.*\)"/\1/p' "$GRADLE_FILE" | head -1; }
current_code() { sed -n 's/^ *versionCode = \([0-9]*\)/\1/p' "$GRADLE_FILE" | head -1; }

version_code() { # 0.MINOR.PATCH -> 5000 + (MINOR-1)*100 + PATCH
    local major minor patch
    IFS=. read -r major minor patch <<<"$1"
    [[ "$major" == 0 && "$minor" =~ ^[1-9][0-9]*$ && "$patch" =~ ^[0-9]+$ && "$patch" -lt 100 ]] \
        || die "version must look like 0.MINOR.PATCH with PATCH below 100 (for 1.x extend version_code)"
    echo $((5000 + (minor - 1) * 100 + patch))
}

apk_path() { echo "app/build/outputs/apk/release/Droneboard_$(current_version)-release.apk"; }

find_apksigner() {
    local sdk="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Library/Android/sdk}}"
    ls "$sdk"/build-tools/*/apksigner 2>/dev/null | sort -V | tail -1
}

prepare() {
    local version="${1:-}"; [[ -n "$version" ]] || die "usage: tools/release.sh prepare <version>"
    local code; code=$(version_code "$version")
    local notes="docs/RELEASE_NOTES_$version.md"
    [[ -z "$(git status --porcelain)" ]] || die "the working tree is not clean, commit or stash first"
    [[ -f "$notes" ]] || die "$notes is missing, write the release notes first"
    [[ "$code" -gt "$(current_code)" ]] || die "version code $code is not above the current $(current_code)"
    git rev-parse -q --verify "refs/tags/v$version" >/dev/null && die "tag v$version already exists"

    # the same three places a release touched by hand
    sed -i.bak -E "s/^( *versionCode = )[0-9]+/\1$code/; s/^( *versionName = )\".*\"/\1\"$version\"/" "$GRADLE_FILE"
    sed -i.bak -E "s#^release_notes: .*#release_notes: ./$notes#" zapstore.yaml
    rm -f "$GRADLE_FILE.bak" zapstore.yaml.bak
    [[ "$(current_version)" == "$version" && "$(current_code)" == "$code" ]] || die "could not set the version in $GRADLE_FILE"

    ./gradlew :app:assembleRelease
    local apk; apk=$(apk_path)
    [[ -f "$apk" ]] || die "no APK at $apk"

    local signer cert; signer=$(find_apksigner)
    [[ -n "$signer" ]] || die "apksigner not found, set ANDROID_HOME"
    cert=$("$signer" verify --print-certs "$apk" | sed -n 's/.*certificate SHA-256 digest: //p' | head -1)
    [[ -n "$cert" ]] || die "the APK is not signed (no signing.properties?)"
    if [[ -n "${EXPECTED_CERT_SHA256:-}" && "$cert" != "$EXPECTED_CERT_SHA256" ]]; then
        die "wrong signing certificate: $cert"
    fi
    if unzip -l "$apk" | grep -q 'lib/x86'; then die "the APK contains x86 libraries"; fi

    echo
    echo "Built $apk ($(du -h "$apk" | cut -f1)), version $version ($code)"
    echo "Signing certificate SHA-256: $cert"
    echo "Test it on a phone (adb install -r --user 0 $apk), then run: tools/release.sh publish"
}

publish() {
    local version code apk notes
    version=$(current_version); code=$(current_code); apk=$(apk_path); notes="docs/RELEASE_NOTES_$version.md"
    [[ -f "$apk" ]] || die "$apk is missing, run prepare first"
    [[ -f "$notes" ]] || die "$notes is missing"
    [[ "$(git rev-parse --abbrev-ref HEAD)" == main ]] || die "not on main"
    git rev-parse -q --verify "refs/tags/v$version" >/dev/null && die "tag v$version already exists"
    # only the version bump may be uncommitted
    local other; other=$(git status --porcelain | grep -v -E " ($GRADLE_FILE|zapstore.yaml)$" || true)
    [[ -z "$other" ]] || die "unexpected uncommitted changes:
$other"
    [[ "$apk" -nt "$GRADLE_FILE" ]] || die "the APK is older than $GRADLE_FILE, run prepare again"

    local title; title="${RELEASE_TITLE:-$(sed -n 's/^[-*] *//p' "$notes" | head -1)}"
    echo "About to commit, tag v$version, push main and the tag, and create a GitHub release with"
    echo "  $apk"
    echo "  commit message: $version: $title"
    read -r -p "Continue? [y/N] " answer
    [[ "$answer" == y ]] || die "stopped"

    git add "$GRADLE_FILE" zapstore.yaml
    git diff --cached --quiet || git commit -m "$version: $title"
    git tag "v$version"
    git push origin main "v$version"
    gh release create "v$version" "$apk" --title "Droneboard $version" --notes-file "$notes"
    echo "Released v$version ($code)."
}

case "${1:-}" in
    prepare) shift; prepare "$@" ;;
    publish) publish ;;
    *) sed -n '3,12p' "$0" | sed 's/^# \{0,1\}//'; exit 1 ;;
esac
