#!/bin/bash
# ============================================================
# values-validate.sh
# ============================================================
# 校验业务 values 文件是否含有【CI 注入字段】或【运维全局基线字段】
#
# 用法：
#   ./values-validate.sh <values-file>
#
# 退出码：
#   0  通过
#   1  发现越界字段
#   2  文件不存在或参数错误
#
# 设计（v2 - 2026-05）：
#   - 业务 values（deploy/values-test.yaml）= 开发管，test 环境用
#   - 开发可以自由写：service.replicas/port、resources、java、env、probes、
#                     monitoring、hpa、pdb、ingress、nodeSelector、tolerations、
#                     affinity 等所有 K8s 调度/资源字段
#   - 真正不能写的只有两类：
#     1. CI 注入字段（pipeline 自动算）
#     2. 全局基线字段（baselines/_global.yaml 管，所有服务共享）
# ============================================================

set -uo pipefail

if [ -t 1 ]; then
    RED='\033[0;31m'; YELLOW='\033[1;33m'; GREEN='\033[0;32m'; CYAN='\033[0;36m'; NC='\033[0m'
else
    RED='' YELLOW='' GREEN='' CYAN='' NC=''
fi

VALUES_FILE="${1:-}"
if [ -z "$VALUES_FILE" ]; then
    echo "用法: $0 <values-file>"
    exit 2
fi
if [ ! -f "$VALUES_FILE" ]; then
    echo -e "${RED}❌ 文件不存在: $VALUES_FILE${NC}"
    exit 2
fi

# ── 禁止字段类别 1：CI 注入字段（pipeline 自动算，不要写）──
# 顶级字段
FORBIDDEN_TOP_LEVEL=(
    "project"               # CI 自动注入（来自 Jenkinsfile projectName）
    "environment"           # CI 自动注入（来自 DEPLOY_ENV）
    "workloadType"          # 全局基线管
    "extraLabels"           # 一般运维加（如 cost-center 标签）
    "volumeClaimTemplates"  # StatefulSet 专用，运维管
)

# 嵌套字段
FORBIDDEN_NESTED=(
    "service.namespace"           # CI 注入: {project}-{env}
    "service.name"                # CI 注入: serviceName
    "image.name"                  # CI 注入: {project-prefix}/{service}
    "image.tag"                   # CI 注入: R{commit-sha}
    "image.registry"              # 全局基线: SWR registry
    "image.pullSecret"            # 全局基线: regcred
    "image.pullPolicy"            # 全局基线: IfNotPresent
    "image.createPullSecret"      # CI 注入: 触发自动创建 secret
    "image.pullSecretData"        # CI 注入: dockerconfigjson base64
)

violations=()

# 检测顶级字段
check_top_level() {
    local field=$1
    if grep -E "^${field}[[:space:]]*:" "$VALUES_FILE" | grep -v "^[[:space:]]*#" >/dev/null 2>&1; then
        violations+=("$field")
    fi
}

# 检测嵌套字段（如 service.namespace）
check_nested() {
    local path=$1
    local parent=${path%%.*}
    local child=${path#*.}

    awk -v parent="$parent" -v child="$child" '
        $0 ~ "^"parent":[[:space:]]*$" { in_block = 1; next }
        in_block && /^[a-zA-Z_]/ { in_block = 0 }
        in_block && $0 ~ "^[[:space:]]+"child"[[:space:]]*:" {
            if ($0 !~ /^[[:space:]]*#/) {
                print "FOUND"
                exit
            }
        }
    ' "$VALUES_FILE" | grep -q "FOUND" && violations+=("$path")
}

for f in "${FORBIDDEN_TOP_LEVEL[@]}"; do
    check_top_level "$f"
done

for p in "${FORBIDDEN_NESTED[@]}"; do
    check_nested "$p"
done

if [ ${#violations[@]} -eq 0 ]; then
    echo -e "${GREEN}✅ values 校验通过: $VALUES_FILE${NC}"
    exit 0
fi

echo ""
echo -e "${YELLOW}┌─────────────────────────────────────────────────────────────┐${NC}"
echo -e "${YELLOW}│${NC}  ${RED}⚠️  values 包含 CI/全局基线字段（建议删除）${NC}              ${YELLOW}│${NC}"
echo -e "${YELLOW}├─────────────────────────────────────────────────────────────┤${NC}"
echo -e "${YELLOW}│${NC}  File: ${CYAN}$VALUES_FILE${NC}"
echo -e "${YELLOW}│${NC}"
echo -e "${YELLOW}│${NC}  下列字段 ${RED}由 CI / 全局基线自动注入${NC}，业务 values 写了不会生效："
for v in "${violations[@]}"; do
    echo -e "${YELLOW}│${NC}    • ${RED}$v${NC}"
done
echo -e "${YELLOW}│${NC}"
echo -e "${YELLOW}│${NC}  ${GREEN}修复方法：${NC}"
echo -e "${YELLOW}│${NC}    从 $VALUES_FILE 中删除上述字段"
echo -e "${YELLOW}│${NC}"
echo -e "${YELLOW}│${NC}  说明：本检查仅警告，不阻塞部署。"
echo -e "${YELLOW}│${NC}        业务 values 可以自由调 nodeSelector / tolerations /"
echo -e "${YELLOW}│${NC}        affinity / pdb / resources.limits / hpa 等 K8s 字段。"
echo -e "${YELLOW}└─────────────────────────────────────────────────────────────┘${NC}"
echo ""

exit 1
