#!/bin/bash
# ============================================================
# init-service.sh
# ============================================================
# 一键初始化新服务（v2 - 字段所有权契约版）
#
# 用法：
#   ./init-service.sh <project> <service> [type]
#   ./init-service.sh adv ad-gateway java
#
# 输出：
#   1. k8s-deploy 仓库（运维侧）：
#      - baselines/{project}/{service}/baseline-test.yaml  (按需，先不创建)
#      - baselines/{project}/{service}/baseline-prod.yaml  (按需，先不创建)
#
#   2. /tmp/<service>-init-XXXXX/（给开发，复制到业务仓库根目录）：
#      - Dockerfile
#      - Jenkinsfile
#      - deploy/values.yaml          (业务通用配置)
#      - deploy/values-test.yaml     (测试环境差异)
#      - deploy/values-prod.yaml     (生产环境差异)
#
# 设计：
#   - 默认不创建服务级 baseline（90% 服务用 _global 就够）
#   - 业务 values 模板严格遵守字段所有权契约（不含运维字段）
# ============================================================

set -euo pipefail

# ── 颜色 ────────────────────────────────────────────────────
RED='\033[0;31m'; GREEN='\033[0;32m'; YELLOW='\033[1;33m'; CYAN='\033[0;36m'; NC='\033[0m'

# ── 切换到项目根目录 ────────────────────────────────────────
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
cd "$PROJECT_ROOT"

# ── 参数 ────────────────────────────────────────────────────
PROJECT="${1:-}"
SERVICE="${2:-}"
TYPE="${3:-java}"

# 可通过环境变量覆盖
GIT_BASE_URL="${GIT_BASE_URL:-http://gitea.example.com}"
DOCKER_REGISTRY="${DOCKER_REGISTRY:-sinozo}"

# ── 用法 ────────────────────────────────────────────────────
usage() {
    cat <<EOF
用法: ./init-service.sh <project> <service> [type]

示例:
  ./init-service.sh adv ad-gateway java
  ./init-service.sh fcm fc05-api java
  ./init-service.sh adv ad-admin-fe nodejs

参数:
  project: 项目名（小写字母+数字+连字符）
  service: 服务名（小写字母+数字+连字符）
  type:    java | nodejs（默认 java）

环境变量:
  GIT_BASE_URL     Git 基础 URL（默认 http://gitea.example.com）
  DOCKER_REGISTRY  镜像仓库前缀（默认 sinozo）
EOF
    exit 1
}

# ── 校验 ────────────────────────────────────────────────────
[ -z "$PROJECT" ] || [ -z "$SERVICE" ] && usage

[[ "$PROJECT" =~ ^[a-z0-9-]+$ ]] || { echo -e "${RED}❌ project 名只能包含小写字母、数字、连字符${NC}"; exit 1; }
[[ "$SERVICE" =~ ^[a-z0-9-]+$ ]] || { echo -e "${RED}❌ service 名只能包含小写字母、数字、连字符${NC}"; exit 1; }
[[ "$TYPE" =~ ^(java|nodejs)$ ]] || { echo -e "${RED}❌ type 必须是 java 或 nodejs${NC}"; exit 1; }

# ── 输出目录 ────────────────────────────────────────────────
OUT_DIR=$(mktemp -d -t "${SERVICE}-init-XXXXXX")
mkdir -p "$OUT_DIR/deploy"

echo -e "${CYAN}🚀 初始化服务: $PROJECT/$SERVICE ($TYPE)${NC}"
echo ""

# ============================================================
# Part 1：生成业务仓库文件（开发使用）
# ============================================================
echo -e "${YELLOW}📦 生成业务仓库文件 → $OUT_DIR/${NC}"

# ── 1.1 Dockerfile ─────────────────────────────────────────
if [ "$TYPE" = "java" ]; then
    if [ -f "shared-library/templates/Dockerfile.java17" ]; then
        cp "shared-library/templates/Dockerfile.java17" "$OUT_DIR/Dockerfile"
    else
        cp "shared-library/templates/Dockerfile.java8" "$OUT_DIR/Dockerfile"
    fi
else
    cp "shared-library/templates/Dockerfile.nginx" "$OUT_DIR/Dockerfile"
fi
echo -e "  ${GREEN}✓${NC} Dockerfile"

# ── 1.2 Jenkinsfile ────────────────────────────────────────
cat > "$OUT_DIR/Jenkinsfile" <<EOF
@Library('k8s-deploy-lib@main') _

k8sDeploy(
    projectName:  '$PROJECT',
    serviceName:  '$SERVICE',
    serviceType:  '$TYPE',
    gitUrl:       '$GIT_BASE_URL/$PROJECT/$SERVICE.git',
    gitCredId:    'git-$PROJECT-cred',
    dockerImage:  '$DOCKER_REGISTRY/$SERVICE',
    dockerCredId: 'docker-swr-cred',$([ "$TYPE" = "java" ] && echo "
    jdkTool:      'jdk 1.8',")
)
EOF
echo -e "  ${GREEN}✓${NC} Jenkinsfile"

# ── 1.3 deploy/values.yaml（业务通用配置）──────────────────
cat > "$OUT_DIR/deploy/values.yaml" <<EOF
# ============================================================
# 业务通用配置 (deploy/values.yaml)
# ============================================================
# Owner：开发
# 范围：所有环境共享的业务配置
#
# ⚠️  字段所有权契约：本文件 + values-{env}.yaml 只能写以下字段：
#   - service.replicas / service.port
#   - image.name (image.tag 由 CI 注入)
#   - java.enabled / java.opts
#   - env / config
#   - probes / monitoring
#   - hpa.minReplicas / hpa.maxReplicas / hpa.cpuTarget
#   - resources.requests
#   - ingress / configmap
#
# 禁止字段（属于运维，写了 CI 会拒绝）：
#   namespace / image.registry / image.pullSecret / resources.limits
#   securityContext / nodeSelector / pdb / strategy 等
#
# 完整契约：见 k8s-deploy/docs/FIELD_OWNERSHIP.md
# ============================================================

# ── 服务基本信息 ─
service:
  port: 8080

# ── 镜像 ─（image.tag 由 CI 自动注入，不要写）
image:
  name: $DOCKER_REGISTRY/$SERVICE

EOF

if [ "$TYPE" = "java" ]; then
cat >> "$OUT_DIR/deploy/values.yaml" <<'EOF'
# ── Java 配置 ─
java:
  enabled: true
  opts: >-
    -Dspring.application.name=SERVICE_NAME
    -Dspring.cloud.nacos.config.server-addr=nacos.internal:8848
    -Dspring.cloud.nacos.discovery.server-addr=nacos.internal:8848
    -Xms512m -Xmx1024m

# ── 健康探针 ─
probes:
  enabled: true
  type: http
  path: /actuator/health
  port: 8080

# ── Prometheus 监控 ─
monitoring:
  enabled: true
  path: /actuator/prometheus
  port: 8080
EOF
sed -i "s/SERVICE_NAME/$SERVICE/g" "$OUT_DIR/deploy/values.yaml"
fi

cat >> "$OUT_DIR/deploy/values.yaml" <<'EOF'

# ── 业务环境变量（可选）──
# env:
#   - name: LOG_LEVEL
#     value: info

# ── 业务配置（可选，会渲染为 ConfigMap）──
# config:
#   app:
#     timeout: 10
#     retries: 3
EOF

echo -e "  ${GREEN}✓${NC} deploy/values.yaml"

# ── 1.4 deploy/values-test.yaml ────────────────────────────
cat > "$OUT_DIR/deploy/values-test.yaml" <<EOF
# ============================================================
# 测试环境配置 (deploy/values-test.yaml)
# ============================================================
# 仅写与 values.yaml 不同的字段
# ============================================================

# ── 副本数（测试环境最小化）──
service:
  replicas: 1

# ── 资源 requests（测试环境节省资源）──
resources:
  enabled: true
  requests:
    cpu: 100m
    memory: 256Mi

# ── Java JVM 测试环境覆写 ──
EOF

if [ "$TYPE" = "java" ]; then
cat >> "$OUT_DIR/deploy/values-test.yaml" <<EOF
java:
  opts: >-
    -Dspring.application.name=$SERVICE
    -Dspring.profiles.active=test
    -Dspring.cloud.nacos.config.server-addr=nacos-test.internal:8848
    -Dspring.cloud.nacos.config.namespace=$SERVICE-test
    -Dspring.cloud.nacos.discovery.server-addr=nacos-test.internal:8848
    -Dspring.cloud.nacos.discovery.namespace=$SERVICE-test
    -Xms256m -Xmx512m

# ── 测试环境业务变量 ──
env:
  - name: LOG_LEVEL
    value: debug
EOF
fi

echo -e "  ${GREEN}✓${NC} deploy/values-test.yaml"

# ── 1.5 deploy/values-prod.yaml ────────────────────────────
cat > "$OUT_DIR/deploy/values-prod.yaml" <<EOF
# ============================================================
# 生产环境配置 (deploy/values-prod.yaml)
# ============================================================

# ── 副本数（生产高可用）──
service:
  replicas: 3

# ── 资源 requests ──
resources:
  enabled: true
  requests:
    cpu: 500m
    memory: 1Gi

# ── HPA（生产建议开启）──
hpa:
  enabled: true
  minReplicas: 3
  maxReplicas: 10
  cpuTarget: 70

EOF

if [ "$TYPE" = "java" ]; then
cat >> "$OUT_DIR/deploy/values-prod.yaml" <<EOF
# ── Java JVM 生产环境覆写 ──
java:
  opts: >-
    -Dspring.application.name=$SERVICE
    -Dspring.profiles.active=prod
    -Dspring.cloud.nacos.config.server-addr=nacos-prod.internal:8848
    -Dspring.cloud.nacos.config.namespace=$SERVICE-prod
    -Dspring.cloud.nacos.discovery.server-addr=nacos-prod.internal:8848
    -Dspring.cloud.nacos.discovery.namespace=$SERVICE-prod
    -Xms2g -Xmx4g
    -XX:+UseG1GC

# ── 生产环境业务变量 ──
env:
  - name: LOG_LEVEL
    value: info
EOF
fi

echo -e "  ${GREEN}✓${NC} deploy/values-prod.yaml"

# ── 校验生成的 values 是否合规 ─────────────────────────────
echo ""
echo -e "${YELLOW}🔍 校验生成的业务 values 是否符合契约...${NC}"
ALL_PASS=true
for f in "$OUT_DIR/deploy/values.yaml" "$OUT_DIR/deploy/values-test.yaml" "$OUT_DIR/deploy/values-prod.yaml"; do
    if ! "$PROJECT_ROOT/automation/values-validate.sh" "$f" >/dev/null 2>&1; then
        echo -e "  ${RED}✗${NC} $f"
        ALL_PASS=false
    else
        echo -e "  ${GREEN}✓${NC} $(basename $f)"
    fi
done
$ALL_PASS && echo -e "  ${GREEN}所有业务 values 符合字段所有权契约${NC}"

# ============================================================
# Part 2：可选 - 创建服务级 baseline 模板（默认跳过）
# ============================================================
echo ""
echo -e "${YELLOW}📋 服务级 baseline:${NC}"
echo -e "  ${CYAN}默认不创建（90% 服务用 _global.yaml 就够）${NC}"
echo -e "  仅当本服务有特殊需求（StatefulSet / 防关联 EIP / 特殊 nodeSelector）时，"
echo -e "  在 ${CYAN}baselines/$PROJECT/$SERVICE/baseline-{test,prod}.yaml${NC} 创建"

# ============================================================
# Part 3：下一步指引
# ============================================================
cat <<EOF

${GREEN}═══════════════════════════════════════════════════════════${NC}
${GREEN}✅ 初始化完成！${NC}
${GREEN}═══════════════════════════════════════════════════════════${NC}

📂 生成文件位置：
   ${CYAN}$OUT_DIR/${NC}
   ├── Dockerfile
   ├── Jenkinsfile
   └── deploy/
       ├── values.yaml
       ├── values-test.yaml
       └── values-prod.yaml

📋 下一步操作：

  ${YELLOW}【开发】${NC}把这些文件复制到业务仓库根目录：
    cp -r $OUT_DIR/{Dockerfile,Jenkinsfile,deploy} <业务仓库>/
    cd <业务仓库>
    git add Dockerfile Jenkinsfile deploy/
    git commit -m "feat: 容器化部署配置"
    git push

  ${YELLOW}【运维】${NC}创建 Jenkins Job：
    ${SERVICE}-test    (DEPLOY_ENV=test)
    ${SERVICE}-prod    (DEPLOY_ENV=prod)
    Pipeline script from SCM → 业务仓库 → Jenkinsfile

  ${YELLOW}【运维】${NC}首次部署：
    Jenkins → ${SERVICE}-test → Build with Parameters
      ACTION: deploy

🧹 完成后可清理临时目录：
    rm -rf $OUT_DIR

${GREEN}═══════════════════════════════════════════════════════════${NC}
EOF
