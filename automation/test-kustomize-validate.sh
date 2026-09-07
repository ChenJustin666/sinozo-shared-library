#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
VALIDATOR="$SCRIPT_DIR/kustomize-validate.sh"
FIXTURES="$(mktemp -d)"
trap 'rm -rf "$FIXTURES"' EXIT

expect_pass() {
  local name="$1"
  if ! "$VALIDATOR" "$FIXTURES/$name.yaml" >/dev/null 2>&1; then
    echo "FAIL: expected validation to pass: $name" >&2
    "$VALIDATOR" "$FIXTURES/$name.yaml" || true
    exit 1
  fi
}

expect_fail() {
  local name="$1"
  if "$VALIDATOR" "$FIXTURES/$name.yaml" >/dev/null 2>&1; then
    echo "FAIL: expected validation to fail: $name" >&2
    exit 1
  fi
}

cat > "$FIXTURES/configmap-ok.yaml" <<'YAML'
apiVersion: v1
kind: ConfigMap
metadata:
  name: app-config
data:
  SPRING_PROFILES_ACTIVE: test
  NACOS_SERVERS: nacos-test.internal:8848
YAML

cat > "$FIXTURES/plain-secret.yaml" <<'YAML'
apiVersion: v1
kind: Secret
metadata:
  name: forbidden
data:
  PASSWORD: cGFzc3dvcmQ=
YAML

cat > "$FIXTURES/secret-generator.yaml" <<'YAML'
apiVersion: kustomize.config.k8s.io/v1beta1
kind: Kustomization
secretGenerator:
  - name: forbidden
    literals:
      - PASSWORD=password
YAML

cat > "$FIXTURES/configmap-secret-key.yaml" <<'YAML'
apiVersion: v1
kind: ConfigMap
metadata:
  name: forbidden
data:
  DB_PASSWORD: password
YAML

cat > "$FIXTURES/plain-env.yaml" <<'YAML'
apiVersion: apps/v1
kind: Deployment
metadata:
  name: forbidden
spec:
  template:
    spec:
      containers:
        - name: app
          env:
            - name: NACOS_PASSWORD
              value: password
YAML

cat > "$FIXTURES/value-from-ok.yaml" <<'YAML'
apiVersion: apps/v1
kind: Deployment
metadata:
  name: app
spec:
  template:
    spec:
      containers:
        - name: app
          env:
            - name: NACOS_PASSWORD
              valueFrom:
                secretKeyRef:
                  name: app-runtime
                  key: NACOS_PASSWORD
YAML

cat > "$FIXTURES/sealed-empty.yaml" <<'YAML'
apiVersion: bitnami.com/v1alpha1
kind: SealedSecret
metadata:
  name: invalid
spec:
  encryptedData: {}
YAML

cat > "$FIXTURES/sealed-ok.yaml" <<'YAML'
apiVersion: bitnami.com/v1alpha1
kind: SealedSecret
metadata:
  name: app-runtime
spec:
  encryptedData:
    NACOS_PASSWORD: AgByActualCiphertextForValidation
YAML

cat > "$FIXTURES/multi-document-secret.yaml" <<'YAML'
apiVersion: v1
kind: ConfigMap
metadata:
  name: app-config
data:
  PROFILE: test
---
apiVersion: v1
kind: Secret
metadata:
  name: forbidden
data:
  TOKEN: dG9rZW4=
YAML

expect_pass configmap-ok
expect_pass value-from-ok
expect_pass sealed-ok
expect_fail plain-secret
expect_fail secret-generator
expect_fail configmap-secret-key
expect_fail plain-env
expect_fail sealed-empty
expect_fail multi-document-secret

echo "All Kustomize security validator tests passed"
