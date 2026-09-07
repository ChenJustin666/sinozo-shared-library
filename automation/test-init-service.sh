#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
TEST_ROOT="$(mktemp -d)"
trap 'rm -rf "$TEST_ROOT"' EXIT

export TMPDIR="$TEST_ROOT/tmp"
mkdir -p "$TMPDIR"

"$SCRIPT_DIR/init-service.sh" verify java-app java
"$SCRIPT_DIR/init-service.sh" verify node-app nodejs

for service in java-app node-app; do
  business="$TMPDIR/k8s-init/verify-$service/business"
  "$SCRIPT_DIR/kustomize-validate.sh" \
    "$business/deploy/kustomize/base" \
    "$business/deploy/kustomize/overlays/test" >/dev/null
  kubectl kustomize "$business/deploy/kustomize/overlays/test" >/dev/null

  render="$TEST_ROOT/render-$service"
  mkdir -p "$render/overlays"
  cp -R "$business/deploy/kustomize/base" "$render/base"
  kubectl kustomize "$business/deploy/kustomize/overlays/prod" > "$render/prod.yaml"
  "$SCRIPT_DIR/kustomize-validate.sh" "$render/prod.yaml" >/dev/null

  test -f "$business/Dockerfile"
  test -f "$business/Jenkinsfile"
done

rbac="$TEST_ROOT/rbac"
mkdir -p "$rbac"
sed -e 's/PROJECT_ENV/verify-test/g' \
    -e 's/SERVICE_ACCOUNT_NAME/jenkins-test-deployer/g' \
    "$ROOT/kustomize/examples/common/deployer-rbac.example.yaml" > "$rbac/rbac.yaml"
cat > "$rbac/kustomization.yaml" <<'YAML'
apiVersion: kustomize.config.k8s.io/v1beta1
kind: Kustomization
resources:
  - rbac.yaml
YAML
kubectl kustomize "$rbac" >/dev/null

echo "Java, Node.js and deployer RBAC generation tests passed"
