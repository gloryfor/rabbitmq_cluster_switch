#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"
DEFAULT_OUTPUT_DIR="${PROJECT_ROOT}/heapdumps"

CONTAINER_NAME="rmq-switch-app"
OUTPUT_DIR="${DEFAULT_OUTPUT_DIR}"
INCLUDE_ALL=false
LOCAL_MODE=false

usage() {
  cat << EOF
Usage: $(basename "$0") [OPTIONS]

Collects a JVM heap dump (.hprof) from the running Spring Boot application.

Options:
  -c, --container <name>   Target Docker container name (default: rmq-switch-app)
  -o, --output-dir <dir>   Host directory to save the heap dump (default: ./heapdumps)
      --all                Include unreachable (dead) objects in the heap dump (jcmd -all)
      --local              Collect from a locally running JVM process instead of Docker
  -h, --help               Show this help message
EOF
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    -c|--container)
      CONTAINER_NAME="${2:-}"
      if [[ -z "$CONTAINER_NAME" ]]; then
        echo "Error: --container requires a value." >&2
        exit 1
      fi
      shift 2
      ;;
    -o|--output-dir)
      OUTPUT_DIR="${2:-}"
      if [[ -z "$OUTPUT_DIR" ]]; then
        echo "Error: --output-dir requires a value." >&2
        exit 1
      fi
      shift 2
      ;;
    --all)
      INCLUDE_ALL=true
      shift
      ;;
    --local)
      LOCAL_MODE=true
      shift
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    *)
      echo "Unknown option: $1" >&2
      usage >&2
      exit 1
      ;;
  esac
done

TIMESTAMP="$(date +%Y%m%d_%H%M%S)"
mkdir -p "$OUTPUT_DIR"
OUTPUT_DIR="$(cd "$OUTPUT_DIR" && pwd)"

if [[ "$LOCAL_MODE" == true ]]; then
  JDK_BIN=""
  if [[ -n "${JAVA_HOME:-}" && -x "${JAVA_HOME}/bin/jcmd" ]]; then
    JDK_BIN="${JAVA_HOME}/bin"
  elif [[ -x "/Library/Java/JavaVirtualMachines/temurin-17.jdk/Contents/Home/bin/jcmd" ]]; then
    JDK_BIN="/Library/Java/JavaVirtualMachines/temurin-17.jdk/Contents/Home/bin"
  elif command -v jcmd >/dev/null 2>&1; then
    JDK_BIN="$(dirname "$(command -v jcmd)")"
  else
    echo "Error: jcmd not found. Set JAVA_HOME to a JDK 17 installation." >&2
    exit 1
  fi

  LOCAL_PID="$("${JDK_BIN}/jps" -l | awk '/RmqSwitchApplication|app\.jar/{if (!pid) pid=$1} END{print pid}')"
  if [[ -z "$LOCAL_PID" ]]; then
    echo "Error: No running RmqSwitchApplication JVM process found." >&2
    exit 1
  fi

  FILENAME="heapdump_local_${LOCAL_PID}_${TIMESTAMP}.hprof"
  HOST_FILE="${OUTPUT_DIR}/${FILENAME}"

  echo "[HeapDump] Collecting from local JVM PID=${LOCAL_PID} (all=${INCLUDE_ALL})..."
  if [[ "$INCLUDE_ALL" == true ]]; then
    "${JDK_BIN}/jcmd" "$LOCAL_PID" GC.heap_dump -all "$HOST_FILE"
  else
    "${JDK_BIN}/jcmd" "$LOCAL_PID" GC.heap_dump "$HOST_FILE"
  fi

  echo "[HeapDump] Saved: ${HOST_FILE}"
  ls -lh "$HOST_FILE"
  exit 0
fi

RUNNING_CONTAINER="$(docker ps --filter "name=^/${CONTAINER_NAME}$" --filter "status=running" --format "{{.Names}}")"
if [[ -z "$RUNNING_CONTAINER" ]]; then
  echo "Error: Container '${CONTAINER_NAME}' is not running." >&2
  exit 1
fi

if ! docker exec "$CONTAINER_NAME" sh -c 'command -v jcmd >/dev/null 2>&1'; then
  echo "Error: 'jcmd' not found inside '${CONTAINER_NAME}'. Rebuild with JDK base image (docker-compose up -d --build rabbit-app)." >&2
  exit 1
fi

JAVA_PID="$(docker exec "$CONTAINER_NAME" jps -l | awk '/app\.jar|RmqSwitchApplication/{if (!pid) pid=$1} END{print pid}')"
if [[ -z "$JAVA_PID" ]]; then
  JAVA_PID="1"
fi

FILENAME="heapdump_${CONTAINER_NAME}_${TIMESTAMP}.hprof"
HOST_FILE="${OUTPUT_DIR}/${FILENAME}"

HAS_MOUNT=false
if [[ "$OUTPUT_DIR" == "$DEFAULT_OUTPUT_DIR" ]] && \
   docker inspect -f '{{range .Mounts}}{{.Destination}}{{"\n"}}{{end}}' "$CONTAINER_NAME" | grep -Fx "/app/heapdumps" >/dev/null; then
  HAS_MOUNT=true
fi

echo "[HeapDump] Collecting from container=${CONTAINER_NAME}, PID=${JAVA_PID} (all=${INCLUDE_ALL})..."

if [[ "$HAS_MOUNT" == true ]]; then
  CONTAINER_FILE="/app/heapdumps/${FILENAME}"
  if [[ "$INCLUDE_ALL" == true ]]; then
    docker exec "$CONTAINER_NAME" jcmd "$JAVA_PID" GC.heap_dump -all "$CONTAINER_FILE"
  else
    docker exec "$CONTAINER_NAME" jcmd "$JAVA_PID" GC.heap_dump "$CONTAINER_FILE"
  fi
else
  CONTAINER_FILE="/tmp/${FILENAME}"
  if [[ "$INCLUDE_ALL" == true ]]; then
    docker exec "$CONTAINER_NAME" jcmd "$JAVA_PID" GC.heap_dump -all "$CONTAINER_FILE"
  else
    docker exec "$CONTAINER_NAME" jcmd "$JAVA_PID" GC.heap_dump "$CONTAINER_FILE"
  fi
  docker cp "${CONTAINER_NAME}:${CONTAINER_FILE}" "$HOST_FILE"
  docker exec "$CONTAINER_NAME" rm -f "$CONTAINER_FILE"
fi

echo "[HeapDump] Saved: ${HOST_FILE}"
ls -lh "$HOST_FILE"
