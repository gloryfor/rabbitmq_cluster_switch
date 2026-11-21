#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
HOSTS_FILE="${SCRIPT_DIR}/../docker/coredns/hosts"
TARGET="${1:-}"

if [[ -z "$TARGET" ]]; then
  echo "Usage: $0 <a|b|status>"
  exit 1
fi

case "$TARGET" in
  a|A|cluster-a)
    cat << 'EOF' > "$HOSTS_FILE"
172.28.1.11 rabbitmq.cluster.local
172.28.1.12 rabbitmq.cluster.local
172.28.1.13 rabbitmq.cluster.local
EOF
    echo "[DNS Switch] rabbitmq.cluster.local -> Cluster A (172.28.1.11, 172.28.1.12, 172.28.1.13)"
    ;;
  b|B|cluster-b)
    cat << 'EOF' > "$HOSTS_FILE"
172.28.2.11 rabbitmq.cluster.local
172.28.2.12 rabbitmq.cluster.local
172.28.2.13 rabbitmq.cluster.local
EOF
    echo "[DNS Switch] rabbitmq.cluster.local -> Cluster B (172.28.2.11, 172.28.2.12, 172.28.2.13)"
    ;;
  status)
    echo "[Current CoreDNS hosts]"
    cat "$HOSTS_FILE"
    ;;
  *)
    echo "Invalid target: '$TARGET'. Use 'a', 'b', or 'status'."
    exit 1
    ;;
esac
