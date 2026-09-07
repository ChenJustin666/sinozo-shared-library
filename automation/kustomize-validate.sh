#!/usr/bin/env bash
# Reject plaintext secret material before Kustomize apply.
set -euo pipefail

if [[ $# -eq 0 ]]; then
  echo "Usage: $0 <kustomize-dir-or-manifest> [more-paths...]" >&2
  exit 2
fi
for path in "$@"; do
  if [[ ! -e "$path" ]]; then
    echo "ERROR: validation path does not exist: $path" >&2
    exit 2
  fi
done

has_invalid_sealed_secret() {
  awk '
    function indentation(line) {
      match(line, /[^ \t]/)
      return RSTART ? RSTART - 1 : 9999
    }
    function finish_document() {
      if (sealed && !encrypted) invalid = 1
      sealed = 0
      encrypted = 0
      active = 0
    }
    /^---[ \t]*$/ {
      finish_document()
      next
    }
    /^[ \t]*kind:[ \t]*SealedSecret([ \t]|$)/ {
      sealed = 1
      next
    }
    /^[ \t]*encryptedData:[ \t]*/ {
      line = $0
      sub(/^[^:]*:[ \t]*/, "", line)
      if (line ~ /^\{[ \t]*[^}]+\}[ \t]*$/) encrypted = 1
      active = 1
      base = indentation($0)
      next
    }
    active {
      if ($0 ~ /^[ \t]*(#.*)?$/) next
      if (indentation($0) <= base) {
        active = 0
        next
      }
      line = $0
      sub(/^[ \t]*/, "", line)
      if (line ~ /^[A-Za-z0-9_.-]+:[ \t]*[^ \t]/) encrypted = 1
    }
    END {
      finish_document()
      exit(invalid ? 0 : 1)
    }
  ' "$1"
}

has_sensitive_configmap_key() {
  awk '
    function indentation(line) {
      match(line, /[^ \t]/)
      return RSTART ? RSTART - 1 : 9999
    }
    function sensitive(name) {
      name = toupper(name)
      return name ~ /(PASSWORD|PASSWD|SECRET|SECRET_KEY|TOKEN|PRIVATE_KEY|ACCESS_KEY|API_KEY|CLIENT_SECRET|CREDENTIALS?|(^|[_-])(AK|SK))$/
    }
    function finish_document() {
      if (configmap && sensitive_data) found = 1
      configmap = 0
      sensitive_data = 0
      in_data = 0
    }
    /^---[ \t]*$/ {
      finish_document()
      next
    }
    /^[ \t]*kind:[ \t]*ConfigMap([ \t]|$)/ {
      configmap = 1
      next
    }
    /^[ \t]*(data|binaryData):[ \t]*$/ {
      in_data = 1
      base = indentation($0)
      next
    }
    in_data {
      if ($0 ~ /^[ \t]*(#.*)?$/) next
      if (indentation($0) <= base) {
        in_data = 0
        next
      }
      key = $0
      sub(/^[ \t]*/, "", key)
      sub(/:.*/, "", key)
      gsub(/[^A-Za-z0-9_.-]/, "", key)
      if (sensitive(key)) sensitive_data = 1
    }
    END {
      finish_document()
      exit(found ? 0 : 1)
    }
  ' "$1"
}

has_plaintext_sensitive_env() {
  awk '
    function indentation(line) {
      match(line, /[^ \t]/)
      return RSTART ? RSTART - 1 : 9999
    }
    function sensitive(name) {
      gsub(/[^A-Za-z0-9_-]/, "", name)
      name = toupper(name)
      return name ~ /(PASSWORD|PASSWD|SECRET|SECRET_KEY|TOKEN|PRIVATE_KEY|ACCESS_KEY|API_KEY|CLIENT_SECRET|CREDENTIALS?|(^|[_-])(AK|SK))$/
    }
    /^[ \t]*-[ \t]*name:[ \t]*/ {
      pending = 0
      name = $0
      sub(/^[^:]*:[ \t]*/, "", name)
      sub(/[ \t]+#.*/, "", name)
      if (sensitive(name)) {
        pending = 1
        base = indentation($0)
      }
      next
    }
    pending {
      if ($0 ~ /^[ \t]*(#.*)?$/) next
      if (indentation($0) <= base) {
        pending = 0
        next
      }
      if ($0 ~ /^[ \t]*valueFrom:[ \t]*$/) pending = 0
      if ($0 ~ /^[ \t]*value:[ \t]*/) found = 1
    }
    END { exit(found ? 0 : 1) }
  ' "$1"
}

failed=0
while IFS= read -r -d '' file; do
  # Templates and documentation examples are intentionally incomplete and never referenced.
  [[ "$file" == *.example.yaml ]] && continue

  if grep -Eq '^[[:space:]]*kind:[[:space:]]*Secret([[:space:]]|$)' "$file"; then
    echo "ERROR: plaintext Kubernetes Secret is forbidden: $file" >&2
    failed=1
  fi
  if grep -Eq '^[[:space:]]*stringData:[[:space:]]*' "$file"; then
    echo "ERROR: stringData is forbidden in Git: $file" >&2
    failed=1
  fi
  if grep -Eq '^[[:space:]]*secretGenerator:[[:space:]]*' "$file"; then
    echo "ERROR: Kustomize secretGenerator is forbidden in Git: $file" >&2
    failed=1
  fi
  if has_sensitive_configmap_key "$file"; then
    echo "ERROR: secret-like key found in ConfigMap: $file" >&2
    failed=1
  fi
  if has_plaintext_sensitive_env "$file"; then
    echo "ERROR: sensitive environment variable uses plaintext value: $file" >&2
    failed=1
  fi
  if has_invalid_sealed_secret "$file"; then
    echo "ERROR: SealedSecret encryptedData is missing or empty: $file" >&2
    failed=1
  fi
  if grep -Eq '^[[:space:]]*kind:[[:space:]]*SealedSecret([[:space:]]|$)' "$file"; then
    if grep -Eiq '(<sealed-value>|replace-me|changeme|example-ciphertext)' "$file"; then
      echo "ERROR: SealedSecret contains placeholder ciphertext: $file" >&2
      failed=1
    fi
  fi
done < <(find "$@" -type f \( -name '*.yaml' -o -name '*.yml' \) -print0)

if [[ $failed -ne 0 ]]; then
  exit 1
fi
echo "Kustomize security validation passed"
