#!/usr/bin/env bash
# Generate a complete business Kustomize layout (base + test/prod overlays).
set -euo pipefail

usage() {
  echo "Usage: $0 <project> <service> <java|nodejs> [port]"
  echo "Example: $0 adv ad-gateway java"
}

PROJECT="${1:-}"
SERVICE="${2:-}"
TYPE="${3:-}"
PORT="${4:-}"

if [[ -z "$PROJECT" || -z "$SERVICE" || ! "$TYPE" =~ ^(java|nodejs)$ ]]; then
  usage
  exit 2
fi
if [[ ! "$PROJECT" =~ ^[a-z0-9]([-a-z0-9]*[a-z0-9])?$ ]] ||
   [[ ! "$SERVICE" =~ ^[a-z0-9]([-a-z0-9]*[a-z0-9])?$ ]]; then
  echo "project and service must be valid lowercase Kubernetes names"
  exit 2
fi

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
KIND="$TYPE"
DEFAULT_PORT=8080
if [[ "$TYPE" == "nodejs" ]]; then
  KIND="nodejs-pm2"
fi
PORT="${PORT:-$DEFAULT_PORT}"

OUT_ROOT="${TMPDIR:-/tmp}/k8s-init/${PROJECT}-${SERVICE}"
BUSINESS_DIR="$OUT_ROOT/business"
PROD_DIR="$BUSINESS_DIR/deploy/kustomize/overlays/prod"
TEMPLATE="$ROOT/kustomize/examples/$KIND"

if [[ ! -f "$TEMPLATE/base/kustomization.yaml" ]]; then
  echo "template not found: $TEMPLATE"
  exit 1
fi
if [[ -e "$BUSINESS_DIR" ]]; then
  echo "output already exists: $BUSINESS_DIR"
  echo "move it aside before generating again"
  exit 1
fi
if [[ -e "$PROD_DIR" ]]; then
  echo "prod overlay already exists: $PROD_DIR"
  echo "existing production configuration was not overwritten"
  exit 1
fi

mkdir -p "$BUSINESS_DIR/deploy/kustomize/overlays" "$PROD_DIR"
cp -R "$TEMPLATE/base" "$BUSINESS_DIR/deploy/kustomize/base"
cp -R "$TEMPLATE/overlays/test" "$BUSINESS_DIR/deploy/kustomize/overlays/test"
cp -R "$TEMPLATE/overlays/prod/." "$PROD_DIR/"

while IFS= read -r -d '' file; do
  sed -i "s/SERVICE_NAME/$SERVICE/g" "$file"
done < <(find "$BUSINESS_DIR/deploy/kustomize" -type f -print0)

if [[ "$PORT" != "$DEFAULT_PORT" ]]; then
  while IFS= read -r -d '' file; do
    sed -i "s/containerPort: $DEFAULT_PORT/containerPort: $PORT/g; s/port: $DEFAULT_PORT/port: $PORT/g; s/value: \"$DEFAULT_PORT\"/value: \"$PORT\"/g; s/prometheus.io\/port: \"$DEFAULT_PORT\"/prometheus.io\/port: \"$PORT\"/g" "$file"
  done < <(find "$BUSINESS_DIR/deploy/kustomize" -type f -print0)
fi

if [[ "$TYPE" == "java" ]]; then
  cp "$ROOT/shared-library/templates/Dockerfile.java17" "$BUSINESS_DIR/Dockerfile"
  JDK_LINE="    jdkTool:       'jdk 17',"
else
  cp "$ROOT/shared-library/templates/Dockerfile.nodejs-pm2" "$BUSINESS_DIR/Dockerfile"
  JDK_LINE=""
fi
if [[ "$PORT" != "$DEFAULT_PORT" ]]; then
  sed -i "s/EXPOSE $DEFAULT_PORT/EXPOSE $PORT/g; s/PORT=$DEFAULT_PORT/PORT=$PORT/g" "$BUSINESS_DIR/Dockerfile"
fi

cat > "$BUSINESS_DIR/Jenkinsfile" <<EOF
@Library('k8s-deploy-lib@main') _

k8sDeploy(
    projectName:   '$PROJECT',
    serviceName:   '$SERVICE',
    serviceType:   '$TYPE',
    dockerImage:   '$SERVICE',
    dockerCredId:  'docker-swr-cred',
$JDK_LINE
)
EOF

cat > "$BUSINESS_DIR/.dockerignore" <<'EOF'
.git
.business-source
.k8s-deploy-runtime
.kustomize-render
deploy
node_modules
target/*.original
EOF

cat > "$BUSINESS_DIR/.gitignore" <<'EOF'
.kustomize-render/
.business-source/
.k8s-deploy-runtime/
EOF

echo "Generated business files: $BUSINESS_DIR"
echo "Generated business prod overlay: $PROD_DIR"
echo "One Jenkinsfile is generated for test/prod; protect the production branch and prod overlay with CODEOWNERS."
echo "Review names, ports, probes, resources, JAVA_OPTS/NODE_ENV, then commit the business repository."
