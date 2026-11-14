# Toolchain for SOL-Watch Pocket. Use with:  source ~/sol-bridge-app/env.sh
# Everything lives under ~/sol-bridge-app/toolchain; nothing is installed system-wide.
POCKET_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
export POCKET_ROOT
export JAVA_HOME="$POCKET_ROOT/toolchain/jdk"
export ANDROID_HOME="$POCKET_ROOT/toolchain/android-sdk"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export GRADLE_USER_HOME="$POCKET_ROOT/toolchain/gradle-home"
# /tmp is RAM-backed on this machine: keep build temp files on disk.
export TMPDIR="$POCKET_ROOT/toolchain/tmp"
mkdir -p "$TMPDIR" "$GRADLE_USER_HOME"
# GitHub CLI, with its login kept in this folder too (never committed: toolchain/ is ignored).
export GH_CONFIG_DIR="$POCKET_ROOT/toolchain/gh-config"
export PATH="$POCKET_ROOT/toolchain/gh/bin:$JAVA_HOME/bin:$POCKET_ROOT/toolchain/gradle/bin:$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools:$PATH"
