#!/usr/bin/env bash
# User-space toolchain for Android APK reverse engineering (no sudo required).
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
TOOLS="$ROOT/tools"
mkdir -p "$TOOLS/dl" "$TOOLS/bin"
cd "$TOOLS"

say() { printf '\n=== %s ===\n' "$*"; }

# ---------- JDK 21 (Temurin) via Adoptium API ----------
if [ ! -x "$TOOLS/jdk/bin/java" ]; then
  say "Downloading Temurin JDK 21"
  curl -fsSL -o dl/jdk21.tar.gz \
    "https://api.adoptium.net/v3/binary/latest/21/ga/linux/x64/jdk/hotspot/normal/eclipse"
  mkdir -p jdk
  tar -xzf dl/jdk21.tar.gz -C jdk --strip-components=1
fi
export JAVA_HOME="$TOOLS/jdk"
export PATH="$JAVA_HOME/bin:$PATH"
java -version

# ---------- jadx ----------
if [ ! -d "$TOOLS/jadx" ]; then
  say "Downloading jadx (latest)"
  tag=$(curl -fsSL https://api.github.com/repos/skylot/jadx/releases/latest | grep -oP '"tag_name":\s*"\K[^"]+')
  ver="${tag#v}"
  curl -fsSL -o dl/jadx.zip \
    "https://github.com/skylot/jadx/releases/download/${tag}/jadx-${ver}.zip"
  mkdir -p jadx
  unzip -o -q dl/jadx.zip -d jadx
  chmod +x jadx/bin/* 2>/dev/null || true
fi
"$TOOLS/jadx/bin/jadx" --version || true

# ---------- apktool ----------
if [ ! -f "$TOOLS/apktool.jar" ]; then
  say "Downloading apktool (latest)"
  curl -fsSL -o apktool.jar \
    "https://github.com/iBotPeaches/Apktool/releases/latest/download/apktool.jar"
fi
printf '#!/usr/bin/env bash\nexec "%s/jdk/bin/java" -jar "%s/apktool.jar" "$@"\n' "$TOOLS" "$TOOLS" > bin/apktool
chmod +x bin/apktool
"$TOOLS/bin/apktool" --version || true

# ---------- Python venv + analysis libs ----------
if [ ! -x "$TOOLS/venv/bin/python" ]; then
  say "Creating Python venv + androguard"
  python3 -m venv venv
  ./venv/bin/pip install -q --upgrade pip
  ./venv/bin/pip install -q androguard protobuf fitparse
fi
./venv/bin/python -c "import androguard; print('androguard', androguard.__version__)" || true

say "DONE. Tools at $TOOLS"
