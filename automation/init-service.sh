#!/bin/bash
# ============================================================
# init-service.sh
# ============================================================
# 一键初始化新服务（v5 - 字段对齐 chart 真实结构）
#
# 用法：
#   ./init-service.sh <project> <service> [type]
#
# 设计原则:
#   1. 默认只开必要字段: service.replicas=1 / resources / java.opts
#   2. env 留注释占位 (业务按需填)
#   3. 其他全部 enabled: false，但保留完整结构（一眼看到能调啥）
#   4. 探针默认 TCP（路径不确定时最稳，不会因为路径错误导致 pod 一直重启）
#   5. test / prod 副本默认都 1 个（先跑通，prod 副本数运维 review 时再改）
# ============================================================

set -euo pipefail

RED='\033[0;31m'; GREEN='\033[0;32m'; YELLOW='\033[1;33m'; CYAN='\033[0;36m'; NC='\033[0m'

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
cd "$PROJECT_ROOT"

PROJECT="${1:-}"
SERVICE="${2:-}"
TYPE="${3:-java}"

usage() {
    cat <<EOF
用法: ./init-service.sh <project> <service> [type]

示例:
  ./init-service.sh adv ad-gateway java
  ./init-service.sh adv ad-admin-fe nodejs
EOF
    exit 1
}

[ -z "$PROJECT" ] || [ -z "$SERVICE" ] && usage
[[ "$PROJECT" =~ ^[a-z0-9-]+$ ]] || { echo -e "${RED}❌ project 名只能小写字母+数字+连字符${NC}"; exit 1; }
[[ "$SERVICE" =~ ^[a-z0-9-]+$ ]] || { echo -e "${RED}❌ service 名只能小写字母+数字+连字符${NC}"; exit 1; }
[[ "$TYPE" =~ ^(java|nodejs)$ ]] || { echo -e "${RED}❌ type 必须是 java 或 nodejs${NC}"; exit 1; }

OUT_DIR=$(mktemp -d -t "${SERVICE}-init-XXXXXX")
mkdir -p "$OUT_DIR/deploy"

echo -e "${CYAN}🚀 初始化服务: $PROJECT/$SERVICE ($TYPE)${NC}"
echo ""

# 默认端口（Java 8080；Nodejs 后端 3000；前端 Nginx 80 -- 让用户改）
if [ "$TYPE" = "java" ]; then
    DEFAULT_PORT=8080
else
    DEFAULT_PORT=3000   # Node.js 后端常用，前端改成 80
fi

# ============================================================
# Part 1：业务仓库文件
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
    dockerImage:  '$SERVICE',
    dockerCredId: 'docker-swr-cred',
EOF

if [ "$TYPE" = "java" ]; then
cat >> "$OUT_DIR/Jenkinsfile" <<'EOF'

    // ── Java 构建工具（Jenkins Manage → Tools 里的名字）──
    jdkTool:      'jdk 1.8',           // 改成实际 JDK Tool 名（如 'JDK 17'）
    mavenTool:    'Maven 3.8.8',       // 改成实际 Maven Tool 名

EOF
fi

cat >> "$OUT_DIR/Jenkinsfile" <<EOF
    // ── kubeconfig 凭据（按环境分别指定）──
    kubeconfigCredId: [
        test: 'test-k8s-cluster',      // ⚠️ 改成实际 Jenkins 凭据 ID
        prod: 'prod-k8s-cluster',      // ⚠️ 改成实际 Jenkins 凭据 ID
    ],
)
EOF
echo -e "  ${GREEN}✓${NC} Jenkinsfile"

# ── 1.3 deploy/values.yaml（业务通用配置）──────────────────
cat > "$OUT_DIR/deploy/values.yaml" <<EOF
# ============================================================
# 业务通用配置 (deploy/values.yaml)  Owner: 开发
# ============================================================
# ⚠️ CI 自动注入字段（不要写）：
#    image.name / image.tag / image.registry / namespace
# ============================================================

# ── 服务端口 ──
service:
  port: $DEFAULT_PORT

EOF

if [ "$TYPE" = "java" ]; then
cat >> "$OUT_DIR/deploy/values.yaml" <<EOF
# ── Java JVM 通用（环境特化在 values-test.yaml 覆写）──
java:
  enabled: true
  opts: >-
    -Dspring.application.name=$SERVICE
    -Xms512m -Xmx1024m

EOF
fi

cat >> "$OUT_DIR/deploy/values.yaml" <<EOF
# ============================================================
# 探针（Liveness/Readiness/Startup）
# ============================================================
# ⚠️ 探针配错会导致 pod 一直重启，所以默认关闭，业务方根据实际接口情况开启
#
# 如何选 type：
#   tcp  : 推荐，只探测端口能连通即可（最稳，不依赖业务接口）
#   http : 业务有专门健康检查接口（如 /actuator/health）才用
#
# ⚠️ chart 限制：liveness / readiness / startup 三个探针共用同一个 path
#                如需差异化路径请联系运维改 chart
#
# 启用：改 enabled: true，根据实际改 type / path / port
# ============================================================
probes:
  enabled: false               # 默认关，业务确定路径后改 true
  type: tcp                    # tcp 不依赖 path（推荐先用 tcp 跑通）
  path: /health                # type=http 时生效（启动健康检查接口）
  port: $DEFAULT_PORT
  liveness:                    # 存活探针：失败会重启容器
    periodSeconds: 10
    timeoutSeconds: 5
    failureThreshold: 3        # 连续失败 3 次重启
  readiness:                   # 就绪探针：失败会从 Service 摘除流量
    periodSeconds: 5
    timeoutSeconds: 3
    failureThreshold: 3
  startup:                     # 启动探针：通过前 liveness/readiness 不工作（保护慢启动）
    periodSeconds: 10
    timeoutSeconds: 5
    failureThreshold: 30       # 30 次 * 10 秒 = 5 分钟启动窗口

# ============================================================
# Prometheus 监控（默认关，要采集指标改 enabled: true）
# 启用后会在 Pod 加 prometheus.io/scrape 等 annotations
# ============================================================
monitoring:
  enabled: false
  path: /actuator/prometheus   # Java 默认 actuator 路径；Node 改成自己的 /metrics
  port: $DEFAULT_PORT
EOF

echo -e "  ${GREEN}✓${NC} deploy/values.yaml"

# ── 1.4 deploy/values-test.yaml ────────────────────────────
cat > "$OUT_DIR/deploy/values-test.yaml" <<EOF
# ============================================================
# 测试环境配置 (deploy/values-test.yaml)  Owner: 开发(自由调)
# 仅写与 values.yaml 不同的字段
# ============================================================

# ── 副本数（test 默认 1）──
service:
  replicas: 1

# ── 资源（已开启，根据实际调）──
resources:
  enabled: true
  requests:
    cpu: 300m
    memory: 1Gi
  limits:
    cpu: 800m
    memory: 2Gi


EOF

if [ "$TYPE" = "java" ]; then
cat >> "$OUT_DIR/deploy/values-test.yaml" <<EOF
# ── Java JVM 测试覆写（连测试 Nacos）──
java:
  enabled: true
  opts: >-
    -Dspring.application.name=$SERVICE
    -Dspring.profiles.active=test
    -Dspring.cloud.nacos.config.server-addr=nacos-test.internal:8848
    -Dspring.cloud.nacos.config.namespace=$SERVICE-test
    -Dspring.cloud.nacos.discovery.server-addr=nacos-test.internal:8848
    -Dspring.cloud.nacos.discovery.namespace=$SERVICE-test
    -Xms256m -Xmx512m

EOF
fi


cat >> "$OUT_DIR/deploy/values-test.yaml" <<EOF
# ── 业务环境变量（按需填，默认留空）──
env: []
# 示例:
# env:
#   - name: LOG_LEVEL
#     value: debug
#   - name: API_BASE_URL
#     value: https://api-test.internal

# ============================================================
# 探针（默认关，确认健康检查接口后再开）
# ⚠️ 配错会导致 pod 一直重启，建议先 type:tcp 跑稳再换 http
# ============================================================
probes:
  enabled: false
  type: tcp                    # tcp / http
  path: /actuator/health       # type=http 时生效
  port: $DEFAULT_PORT
  liveness:
    periodSeconds: 10
    timeoutSeconds: 5
    failureThreshold: 3
  readiness:
    periodSeconds: 5
    timeoutSeconds: 3
    failureThreshold: 3
  startup:
    periodSeconds: 10
    timeoutSeconds: 5
    failureThreshold: 30       # 5 分钟启动窗口

# ── Prometheus 监控（默认关）──
monitoring:
  enabled: false
  path: /actuator/prometheus
  port: $DEFAULT_PORT

# ============================================================
# HPA 自动伸缩（默认关闭）
# 启用：改 enabled: true，按业务流量定 min/max
# 注意：开 HPA 后副本数由 K8s 自动调，service.replicas 失效
# ============================================================
hpa:
  enabled: false
  minReplicas: 1
  maxReplicas: 3
  cpuTarget: 70                # CPU 利用率超 70% 触发扩容（按 requests 算）
  memoryTarget: 0              # 0=不启用内存指标；填 80 表示内存占用 80%

# ============================================================
# PDB 中断保护（默认关，关键服务建议开）
# 启用：改 enabled: true（前提：service.replicas >= 2）
# ============================================================
pdb:
  enabled: false
  minAvailable: 1              # 至少保留 1 个；高可用可设为 50%
  maxUnavailable: 0            # 二选一，跟 minAvailable 互斥

# ============================================================
# 节点调度 nodeSelector（默认空）
# 用法：填实际节点 label 把服务限定调度到指定节点
# 查看节点标签: kubectl get nodes --show-labels
# ============================================================
nodeSelector: {}
# 示例:
# nodeSelector:
#   node-pool: test-app

# ============================================================
# 节点调度 tolerations（默认空）
# 用法：节点打了 taint 后必须 tolerate 才能调度
# 查看 taint: kubectl describe node <name> | grep Taints
# ============================================================
tolerations: []
# 示例:
# tolerations:
#   - key: dedicated
#     operator: Equal
#     value: test
#     effect: NoSchedule

# ============================================================
# 节点调度 affinity（默认空）
# 常见: podAntiAffinity（多副本互斥）/ podAffinity / nodeAffinity
# ============================================================
affinity: {}
# 示例（多副本互斥）:
# affinity:
#   podAntiAffinity:
#     preferredDuringSchedulingIgnoredDuringExecution:
#       - weight: 100
#         podAffinityTerm:
#           labelSelector:
#             matchLabels:
#               app: $SERVICE
#           topologyKey: kubernetes.io/hostname

# ============================================================
# Ingress 入口（默认关闭，前端 / 对外 API 才需要）
# 启用：改 enabled: true 并填 items
# ============================================================
ingress:
  enabled: false
  items: []
  # 示例:
  # items:
  #   - host: $SERVICE-test.example.com
  #     paths:
  #       - path: /
  #         pathType: Prefix
  #     tls: false              # HTTPS 改 true（先建 TLS Secret）
EOF


echo -e "  ${GREEN}✓${NC} deploy/values-test.yaml"

echo ""
echo -e "${YELLOW}📋 业务文件清单：${NC}"
echo -e "  ${GREEN}✓${NC} $OUT_DIR/Dockerfile"
echo -e "  ${GREEN}✓${NC} $OUT_DIR/Jenkinsfile"
echo -e "  ${GREEN}✓${NC} $OUT_DIR/deploy/values.yaml"
echo -e "  ${GREEN}✓${NC} $OUT_DIR/deploy/values-test.yaml"

# ============================================================
# Part 2：运维仓库 prod 占位
# ============================================================
PROD_DIR="$PROJECT_ROOT/baselines/prod-values/$PROJECT/$SERVICE"
PROD_FILE="$PROD_DIR/values-prod.yaml"

echo ""
echo -e "${YELLOW}🔒 生成 prod 配置占位（运维仓库）：${NC}"

if [ -f "$PROD_FILE" ]; then
    echo -e "  ${YELLOW}⚠ 已存在，跳过：${NC} $PROD_FILE"
    echo -e "  ${CYAN}（如需重新生成，先 rm 后再运行）${NC}"
else
    mkdir -p "$PROD_DIR"

    cat > "$PROD_FILE" <<EOF
# ============================================================
# 生产配置 - $PROJECT/$SERVICE   Owner: 运维
# 路径: baselines/prod-values/$PROJECT/$SERVICE/values-prod.yaml
#
# ⚠️ 占位文件，运维 review 后改实际值再 push
# ============================================================

# ── 副本数（先 1 跑通，稳定后按业务流量调）──
service:
  replicas: 1

# ── 资源（基于实际压测填写，limits 防失控）──
resources:
  enabled: true
  requests:
    cpu: 500m
    memory: 1Gi
  limits:
    cpu: 2000m
    memory: 4Gi

EOF

    if [ "$TYPE" = "java" ]; then
cat >> "$PROD_FILE" <<EOF
# ── Java JVM 生产环境（连生产 Nacos）──
java:
  enabled: true
  opts: >-
    -Dspring.application.name=$SERVICE
    -Dspring.profiles.active=prod
    -Dspring.cloud.nacos.config.server-addr=nacos-prod.internal:8848
    -Dspring.cloud.nacos.config.namespace=$SERVICE-prod
    -Dspring.cloud.nacos.discovery.server-addr=nacos-prod.internal:8848
    -Dspring.cloud.nacos.discovery.namespace=$SERVICE-prod
    -Xms2g -Xmx4g
    -XX:+UseG1GC

EOF
    fi

    cat >> "$PROD_FILE" <<EOF
# ── 业务环境变量（按实际填）──
env: []
# 示例:
# env:
#   - name: LOG_LEVEL
#     value: info

# ============================================================
# 探针（默认关，确认健康检查接口后再开）
# ⚠️ prod 探针配错风险大，建议先 type: tcp 跑稳再换 http
# ============================================================
probes:
  enabled: false
  type: tcp                    # tcp / http
  path: /actuator/health       # type=http 时生效
  port: $DEFAULT_PORT
  liveness:
    periodSeconds: 10
    timeoutSeconds: 5
    failureThreshold: 3
  readiness:
    periodSeconds: 5
    timeoutSeconds: 3
    failureThreshold: 3
  startup:
    periodSeconds: 10
    timeoutSeconds: 5
    failureThreshold: 30       # 5 分钟启动窗口

# ── Prometheus 监控（默认关）──
monitoring:
  enabled: false
  path: /actuator/prometheus
  port: $DEFAULT_PORT

# ============================================================
# HPA 自动伸缩（默认关，运行稳定后再开）
# 启用：改 enabled: true
# ============================================================
hpa:
  enabled: false
  minReplicas: 2
  maxReplicas: 10
  cpuTarget: 70
  memoryTarget: 0              # 0=不用；填 80=内存 80% 触发

# ============================================================
# PDB 中断保护（默认关，关键服务建议开）
# 作用：节点维护/驱逐时保留至少 minAvailable 个 pod
# 启用：改 enabled: true（前提：service.replicas >= 2）
# ============================================================
pdb:
  enabled: false
  minAvailable: 1              # 至少保留 1 个；高可用可设为 50%
  maxUnavailable: 0            # 二选一，跟 minAvailable 互斥

# ============================================================
# 节点调度 nodeSelector（默认空）
# 用法：填实际节点 label 把服务限定调度到指定节点
# 查看节点标签: kubectl get nodes --show-labels
# ============================================================
nodeSelector: {}
# 示例:
# nodeSelector:
#   node-pool: prod-app
#   disk-type: ssd

# ============================================================
# 节点调度 tolerations（默认空）
# 用法：节点打了 taint 后必须 tolerate 才能调度
# 查看 taint: kubectl describe node <name> | grep Taints
# ============================================================
tolerations: []
# 示例:
# tolerations:
#   - key: dedicated
#     operator: Equal
#     value: prod
#     effect: NoSchedule

# ============================================================
# 节点调度 affinity（默认空）
# 常见场景:
#   1. podAntiAffinity - 同服务多副本不调度同节点（高可用，推荐生产开）
#   2. podAffinity     - 跟某服务部署同节点（降低跨节点延迟）
#   3. nodeAffinity    - 复杂节点选择（比 nodeSelector 灵活）
# ============================================================
affinity: {}
# 推荐示例（多副本互斥，生产建议开）:
# affinity:
#   podAntiAffinity:
#     preferredDuringSchedulingIgnoredDuringExecution:
#       - weight: 100
#         podAffinityTerm:
#           labelSelector:
#             matchLabels:
#               app: $SERVICE
#           topologyKey: kubernetes.io/hostname

# ============================================================
# Ingress 入口（默认关）
# 启用：改 enabled: true 并填 items
# ============================================================
ingress:
  enabled: false
  items: []
  # 示例:
  # items:
  #   - host: $SERVICE.example.com
  #     paths:
  #       - path: /
  #         pathType: Prefix
  #     tls: true             # HTTPS（需要先有 TLS Secret）
EOF

    echo -e "  ${GREEN}✓${NC} $PROD_FILE"
fi

# ============================================================
# Part 3：下一步指引
# ============================================================
cat <<EOF

${GREEN}═══════════════════════════════════════════════════════════${NC}
${GREEN}✅ 初始化完成！${NC}
${GREEN}═══════════════════════════════════════════════════════════${NC}

📂 已生成：

  【业务仓库】 → $OUT_DIR/
     ├── Dockerfile / Jenkinsfile
     └── deploy/
         ├── values.yaml          (端口 / JVM / 探针骨架 / 监控骨架)
         └── values-test.yaml     (副本=1 / 资源 / JVM-test / env / HPA / Ingress)

  【运维仓库 prod 占位】 → $PROD_FILE
     默认开启:
       ✓ replicas: 1
       ✓ resources (含 limits)
       ✓ java.opts (生产 Nacos)
     默认关闭（结构都在，按需改 enabled: true 即可）:
       ✗ probes / monitoring
       ✗ hpa / pdb
       ✗ nodeSelector / tolerations / affinity
       ✗ ingress

📋 下一步：

  ${YELLOW}【开发】${NC}把业务文件复制到业务仓库：
    cp -r $OUT_DIR/{Dockerfile,Jenkinsfile,deploy} <业务仓库>/
    cd <业务仓库>
    git add . && git commit -m "feat: 部署配置" && git push

  ${YELLOW}【运维】${NC}review prod 配置并 push：
    cd $PROJECT_ROOT
    vi $PROD_FILE
    git add baselines/prod-values/$PROJECT/$SERVICE/
    git commit -m "ops: $SERVICE prod 配置"
    git push origin main

  ${YELLOW}【运维】${NC}首次部署：
    Jenkins → $SERVICE-test → ACTION=deploy

🧹 清理临时目录:
    rm -rf $OUT_DIR

${CYAN}═══════════════════════════════════════════════════════════${NC}
${CYAN}🛡 安全保证: 开发碰不到 prod 配置（无运维仓库 push 权限）${NC}
${CYAN}🔧 启用字段: enabled: false → true；空 {} / [] 直接填值${NC}
${CYAN}⚠️ git push 冲突解决:${NC}
    git pull --rebase origin main
    # 看到 CONFLICT 编辑文件保留你想要的部分
    git add <冲突文件> && git rebase --continue
    git push origin main
  搞不定:
    git rebase --abort && git pull origin main
    git add <冲突文件> && git commit -m "merge" && git push
${CYAN}═══════════════════════════════════════════════════════════${NC}
EOF
