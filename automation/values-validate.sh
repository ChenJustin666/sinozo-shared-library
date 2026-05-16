#!/bin/bash
# ============================================================
# values-validate.sh
# ============================================================
# 校验业务 values 文件是否含有运维管控字段
#
# 用法：
#   ./values-validate.sh <values-file>
#   ./values-validate.sh deploy/values-prod.yaml
#
# 退出码：
#   0  通过（无越界字段）
#   1  发现越界字段（warning 模式仍返回 1，由 caller 决定阻塞与否）
#   2  文件不存在或参数错误
#
# 设计：
#   - 用 grep 做轻量校验（不依赖 yq/python）
#   - Forbidden 列表与 docs/FIELD_OWNERSHIP.md 严格对应
#   - 输出对开发友好（明确指出违反字段、修复方法）
# ============================================================

set -uo pipefail

# ── 颜色 ────────────────────────────────────────────────────
if [ -t 1 ]; then
    RED='\033[0;31m'
    YELLOW='\033[1;33m'
    GREEN='\033[0;32m'
    CYAN='\033[0;36m'
    NC='\033[0m'
else
    RED='' YELLOW='' GREEN='' CYAN='' NC=''
fi

# ── 参数 ────────────────────────────────────────────────────
VALUES_FILE="${1:-}"
if [ -z "$VALUES_FILE" ]; then
    echo "用法: $0 <values-file>"
    exit 2
fi
if [ ! -f "$VALUES_FILE" ]; then
    echo -e "${RED}❌ 文件不存在: $VALUES_FILE${NC}"
    exit 2
fi

# ── 禁止字段（运维管，开发 values 不允许写）─────────────────
# 格式：YAML 顶级字段路径（不含缩进），用正则匹配文件起始行
# 注意：必须用顶级字段（行首），避免误伤注释里出现的同名字符串
FORBIDDEN_TOP_LEVEL=(
    "project"
    "environment"
    "workloadType"
    "securityContext"
    "serviceAccount"
    "nodeSelector"
    "tolerations"
    "affinity"
    "pdb"
    "pvc"
    "volumeClaimTemplates"
    "strategy"
    "updateStrategy"
    "extraLabels"
)

# 嵌套字段（路径形式，如 service.namespace、image.registry）
# 检测方法：先找到顶级 key，再在其缩进块里找子 key
FORBIDDEN_NESTED=(
    "service.namespace"
    "service.type"
    "service.annotations"
    "image.registry"
    "image.pullSecret"
    "image.pullPolicy"
    "image.createPullSecret"
    "image.pullSecretData"
    "resources.limits"
)

# ── 检测函数 ────────────────────────────────────────────────
violations=()

# 检测顶级字段
check_top_level() {
    local field=$1
    # 匹配行首（无缩进）的 field: 模式，跳过注释行
    if grep -E "^${field}[[:space:]]*:" "$VALUES_FILE" | grep -v "^[[:space:]]*#" >/dev/null 2>&1; then
        violations+=("$field")
    fi
}

# 检测嵌套字段（如 service.namespace）
check_nested() {
    local path=$1
    local parent=${path%%.*}      # service
    local child=${path#*.}        # namespace

    # 用 awk 找 parent 块里的 child
    awk -v parent="$parent" -v child="$child" '
        # 进入 parent: 块
        $0 ~ "^"parent":[[:space:]]*$" { in_block = 1; next }
        # 离开 parent 块（遇到下一个顶级 key）
        in_block && /^[a-zA-Z_]/ { in_block = 0 }
        # 在 parent 块内匹配 child
        in_block && $0 ~ "^[[:space:]]+"child"[[:space:]]*:" {
            # 跳过注释
            if ($0 !~ /^[[:space:]]*#/) {
                print "FOUND"
                exit
            }
        }
    ' "$VALUES_FILE" | grep -q "FOUND" && violations+=("$path")
}

# ── 执行检测 ────────────────────────────────────────────────
for f in "${FORBIDDEN_TOP_LEVEL[@]}"; do
    check_top_level "$f"
done

for p in "${FORBIDDEN_NESTED[@]}"; do
    check_nested "$p"
done

# ── 输出结果 ────────────────────────────────────────────────
if [ ${#violations[@]} -eq 0 ]; then
    echo -e "${GREEN}✅ values 校验通过: $VALUES_FILE${NC}"
    exit 0
fi

echo ""
echo -e "${YELLOW}┌─────────────────────────────────────────────────────────────┐${NC}"
echo -e "${YELLOW}│${NC}  ${RED}❌ 字段所有权校验失败${NC}                                    ${YELLOW}│${NC}"
echo -e "${YELLOW}├─────────────────────────────────────────────────────────────┤${NC}"
echo -e "${YELLOW}│${NC}  File: ${CYAN}$VALUES_FILE${NC}"
echo -e "${YELLOW}│${NC}"
echo -e "${YELLOW}│${NC}  以下字段属于 ${RED}运维${NC}，不允许在业务 values 中修改："
for v in "${violations[@]}"; do
    echo -e "${YELLOW}│${NC}    • ${RED}$v${NC}"
done
echo -e "${YELLOW}│${NC}"
echo -e "${YELLOW}│${NC}  ${GREEN}修复方法（二选一）：${NC}"
echo -e "${YELLOW}│${NC}    1. 从 $VALUES_FILE 中删除上述字段"
echo -e "${YELLOW}│${NC}    2. 联系运维在 baselines/<project>/<svc>/baseline-<env>.yaml 修改"
echo -e "${YELLOW}│${NC}"
echo -e "${YELLOW}│${NC}  字段所有权契约: ${CYAN}docs/FIELD_OWNERSHIP.md${NC}"
echo -e "${YELLOW}└─────────────────────────────────────────────────────────────┘${NC}"
echo ""

exit 1
