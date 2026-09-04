#!/usr/bin/env bash
# Idempotent environment bootstrap for Arena Agents Cloud Agents.
#
# The default base image already ships Node.js 22, but Arena Agents compiles
# against Java 25 (see gradle.properties: options.release = 25). This script
# installs the Temurin 25 JDK once (skipped when already present), refreshes the
# locked coordinator dependencies, and warms the Gradle/Fabric build cache.
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$repo_root"

JAVA_DIR=/opt/java
JDK_LINK="$JAVA_DIR/current"
TEMURIN_VERSION="25.0.4.1"
TEMURIN_BUILD="1"
TEMURIN_ARCHIVE="OpenJDK25U-jdk_x64_linux_hotspot_${TEMURIN_VERSION}_${TEMURIN_BUILD}.tar.gz"
TEMURIN_URL="https://github.com/adoptium/temurin25-binaries/releases/download/jdk-${TEMURIN_VERSION}%2B${TEMURIN_BUILD}/${TEMURIN_ARCHIVE}"
TEMURIN_CHECKSUM_FILE="$repo_root/.cursor/temurin25.sha256"

install_java25() {
  if [ -x "$JDK_LINK/bin/java" ] && "$JDK_LINK/bin/java" -version 2>&1 | grep -q "\"${TEMURIN_VERSION}\""; then
    echo "Temurin 25 already installed at $JDK_LINK"
    return 0
  fi

  echo "Installing Temurin ${TEMURIN_VERSION}+${TEMURIN_BUILD} JDK..."
  local tmp
  tmp="$(mktemp -d)"
  local archive="$tmp/$TEMURIN_ARCHIVE"
  if ! curl -fsSL --proto '=https' --tlsv1.2 -o "$archive" "$TEMURIN_URL"; then
    rm -rf "$tmp"
    return 1
  fi
  if ! (cd "$tmp" && sha256sum --check "$TEMURIN_CHECKSUM_FILE" --status); then
    echo "Temurin archive checksum verification failed." >&2
    rm -rf "$tmp"
    return 1
  fi
  sudo mkdir -p "$JAVA_DIR"
  sudo tar -xzf "$archive" -C "$JAVA_DIR"
  local extracted="$JAVA_DIR/jdk-${TEMURIN_VERSION}+${TEMURIN_BUILD}"
  if [ ! -x "$extracted/bin/java" ]; then
    echo "Temurin archive did not contain the expected JDK directory." >&2
    rm -rf "$tmp"
    return 1
  fi
  sudo ln -sfn "$extracted" "$JDK_LINK"
  sudo update-alternatives --install /usr/bin/java java "$JDK_LINK/bin/java" 2500
  sudo update-alternatives --install /usr/bin/javac javac "$JDK_LINK/bin/javac" 2500
  sudo update-alternatives --set java "$JDK_LINK/bin/java"
  sudo update-alternatives --set javac "$JDK_LINK/bin/javac"
  printf 'export JAVA_HOME=%s\nexport PATH="$JAVA_HOME/bin:$PATH"\n' "$JDK_LINK" \
    | sudo tee /etc/profile.d/java25.sh >/dev/null
  rm -rf "$tmp"
}

install_java25

export JAVA_HOME="$JDK_LINK"
export PATH="$JAVA_HOME/bin:$PATH"

echo "== Toolchain =="
java -version
echo "node $(node --version), npm $(npm --version)"

echo "== Installing locked coordinator dependencies =="
(cd coordinator && npm ci --ignore-scripts --omit=dev)

echo "== Warming Gradle / Fabric build cache (compiles main + test sources) =="
./gradlew --no-daemon --console=plain testClasses

echo "Arena Agents environment install complete."
