#!/usr/bin/env bash
# Publishes FreeMyTV.apk as a GitHub release of this fork (origin): the TVs update to it on their own (Updater).
# Each release raises the version: android:versionName in app/AndroidManifest.xml (2.0.1, 2.1.0…) and versionCode,
# major*10000 + minor*100 + patch. Commit and push, run ./build.sh --release, then: tools/release.sh "What changed"
set -euo pipefail
cd "$(dirname "$0")/.."
fail() { echo "$1" >&2; exit 1; }
sha() { (sha256sum "$1" 2>/dev/null || shasum -a 256 "$1") | cut -d' ' -f1; }
code() { [[ "$1" =~ ^v?([0-9]+)\.([0-9]+)\.([0-9]+)$ ]] && echo $((BASH_REMATCH[1] * 10000 + BASH_REMATCH[2] * 100 + BASH_REMATCH[3])) || echo 0; }

NAME="$(sed -n 's/.*android:versionName="\([^"]*\)".*/\1/p' app/AndroidManifest.xml)"
CODE="$(sed -n 's/.*android:versionCode="\([^"]*\)".*/\1/p' app/AndroidManifest.xml)"
[ "$(code "$NAME")" != 0 ] || fail "versionName must look like 2.0.1 (it's \"$NAME\")."
[ "$CODE" = "$(code "$NAME")" ] || fail "versionCode must be $(code "$NAME") for $NAME (it's $CODE)."
# The release is public: only a ./build.sh --release APK, which carries no keys.
[ -f out/release.txt ] && [ "$(cat out/release.txt)" = "$NAME $(sha FreeMyTV.apk)" ] \
  || fail "FreeMyTV.apk isn't a release build of $NAME: run ./build.sh --release first."
[ -z "$(git status --porcelain)" ] || fail "Commit everything first: the release points at a commit."
REPO="$(git remote get-url origin | sed -E 's#^.*github\.com[:/]##; s#\.git$##')"
git fetch -q origin
git merge-base --is-ancestor HEAD origin/main || fail "Push to origin/main first."
LAST="$(gh release view --repo "$REPO" --json tagName -q .tagName 2>/dev/null || true)"
[ -z "$LAST" ] || [ "$(code "$LAST")" -lt "$CODE" ] || fail "$LAST is already out: raise the version above it."

gh release create "v$NAME" FreeMyTV.apk --repo "$REPO" --target "$(git rev-parse HEAD)" --title "Free My TV $NAME" \
  --notes "${1:-Free My TV $NAME}"
echo "Released v$NAME: the TVs install it within 6 hours, or now from More Settings > Updates."
