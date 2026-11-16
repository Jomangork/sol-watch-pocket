#!/usr/bin/env bash
# Publish the current app version as a GitHub pre-release, which installed apps pick up by themselves.
#
#   tools/release.sh notes.md        # build, check, push, release (notes: Markdown for the release page)
#   tools/release.sh notes.md --no-build   # reuse android/app/build/outputs/apk/release/app-release.apk
#
# Refuses if: there are uncommitted changes, the version's tag already exists, the tests fail,
# or the APK contains anything that looks like a SOL API key.
set -euo pipefail
source "$(dirname "$0")/../env.sh"
cd "$POCKET_ROOT"

notes="${1:?usage: tools/release.sh notes.md [--no-build]}"
build=yes
[ "${2:-}" = "--no-build" ] && build=no

version=$(sed -nE 's/.*versionName = "([^"]+)".*/\1/p' android/app/build.gradle.kts)
tag="v$version"
repo="Jomangork/sol-watch-pocket"
apk="dist/sol-watch-pocket-$version.apk"

[ -n "$(git status --porcelain --untracked-files=no)" ] && { echo "Commit your changes first."; exit 1; }
# The app's "What's new" pop-up shows this file after the update.
[ -s "android/app/src/main/assets/whats_new/$version.md" ] || { echo "Add android/app/src/main/assets/whats_new/$version.md first."; exit 1; }
gh release view "$tag" --repo "$repo" >/dev/null 2>&1 && { echo "$tag is already released."; exit 1; }

python3 -m unittest discover -s tests -q
if [ "$build" = yes ]; then
    tools/build.sh testReleaseUnitTest assembleRelease
fi
mkdir -p dist
cp android/app/build/outputs/apk/release/app-release.apk "$apk"
"$ANDROID_HOME/build-tools/36.0.0/apksigner" verify "$apk"

# No API key may ever ship. Keys look like sol_XXXXXX_ followed by a long secret.
if unzip -p "$apk" 'classes*.dex' | strings | grep -qE 'sol_[A-Za-z0-9]{6}_[A-Za-z0-9]{20,}'; then
    echo "Refusing: $apk contains something that looks like an API key."
    exit 1
fi

git push origin HEAD
gh release create "$tag" "$apk" --repo "$repo" --prerelease --title "$version (work in progress)" --notes-file "$notes"
echo "Released $tag: installed apps find it within an hour on Wi-Fi."
