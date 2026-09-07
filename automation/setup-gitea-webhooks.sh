#!/bin/bash
# ============================================================
# 批量为 Gitea 仓库配置 Jenkins Webhook
# 用法：
#   1. 编辑下面的 SERVICES 数组，添加仓库和Job的映射
#   2. 运行：bash automation/setup-gitea-webhooks.sh
#
# 新服务接入时，只需添加一行映射，重新运行即可
# ============================================================

set -euo pipefail

# ── 配置区域（根据实际情况修改）──
GITEA_URL="https://gitea.sinozo.com"
GITEA_TOKEN="${GITEA_TOKEN:-}"           # 优先从环境变量读取
JENKINS_URL="https://jks-test.sinozo.com"

# 服务映射："Gitea组织/仓库名:Jenkins-Job路径"
# Job路径中的 / 会被替换为 - 作为 token
# 示例：
#   server/AdGateway → Jenkins Job adv/ad-gateway-test → token=adv-ad-gateway-test
SERVICES=(
    # "gitea-org/repo:jenkins-folder/job-name"
    # TODO: 添加你们的服务映射
    # "server/AdGateway:adv/ad-gateway-test"
    # "server/AdAdmin:adv/ad-admin-test"
    # "server/Pic03Gateway:pic03/pic03-gateway-test"
)

# ── 检查参数 ──
if [[ -z "$GITEA_TOKEN" ]]; then
    echo "❌ 请设置 GITEA_TOKEN 环境变量（Gitea 管理员 API Token）"
    echo "   export GITEA_TOKEN=your-token"
    echo "   bash $0"
    exit 1
fi

if [[ ${#SERVICES[@]} -eq 0 ]]; then
    echo "❌ SERVICES 数组为空，请先编辑此脚本添加服务映射"
    exit 1
fi

echo "╔═══════════════════════════════════════════════════════╗"
echo "║  批量配置 Gitea → Jenkins Webhook                    ║"
echo "╠═══════════════════════════════════════════════════════╣"
echo "║  Gitea:   ${GITEA_URL}"
echo "║  Jenkins: ${JENKINS_URL}"
echo "║  服务数:  ${#SERVICES[@]}"
echo "╚═══════════════════════════════════════════════════════╝"
echo ""

SUCCESS=0
FAILED=0

for mapping in "${SERVICES[@]}"; do
    REPO="${mapping%%:*}"
    JOB="${mapping##*:}"
    TOKEN=$(echo "$JOB" | tr '/' '-')
    WEBHOOK_URL="${JENKINS_URL}/generic-webhook-trigger/invoke?token=${TOKEN}"

    echo -n "  📌 ${REPO} → ${JOB} (token=${TOKEN}) ... "

    # 先检查是否已存在相同 webhook（避免重复创建）
    EXISTING=$(curl -s "${GITEA_URL}/api/v1/repos/${REPO}/hooks" \
        -H "Authorization: token ${GITEA_TOKEN}" \
        | grep -c "${TOKEN}" 2>/dev/null || echo "0")

    if [[ "$EXISTING" -gt 0 ]]; then
        echo "⏭️  已存在，跳过"
        SUCCESS=$((SUCCESS + 1))
        continue
    fi

    # 创建 webhook
    RESPONSE=$(curl -s -w "\n%{http_code}" -X POST \
        "${GITEA_URL}/api/v1/repos/${REPO}/hooks" \
        -H "Authorization: token ${GITEA_TOKEN}" \
        -H "Content-Type: application/json" \
        -d "{
            \"type\": \"gitea\",
            \"active\": true,
            \"events\": [\"push\"],
            \"config\": {
                \"url\": \"${WEBHOOK_URL}\",
                \"content_type\": \"json\"
            }
        }")

    HTTP_CODE=$(echo "$RESPONSE" | tail -1)
    if [[ "$HTTP_CODE" == "201" ]]; then
        echo "✅ 创建成功"
        SUCCESS=$((SUCCESS + 1))
    else
        echo "❌ 失败 (HTTP ${HTTP_CODE})"
        FAILED=$((FAILED + 1))
    fi
done

echo ""
echo "╔═══════════════════════════════════════════════════════╗"
echo "║  配置完成: 成功=${SUCCESS}, 失败=${FAILED}              ║"
echo "╚═══════════════════════════════════════════════════════╝"

if [[ $FAILED -gt 0 ]]; then
    echo "⚠️  有 ${FAILED} 个仓库配置失败，请检查仓库名和权限"
    exit 1
fi
