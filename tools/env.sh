export ROOT_TOOLS="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
export JAVA_HOME="$ROOT_TOOLS/jdk"
export PATH="$JAVA_HOME/bin:$ROOT_TOOLS/bin:$ROOT_TOOLS/jadx/bin:$PATH"
