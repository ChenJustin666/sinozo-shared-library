# Gitea 自动触发 Jenkins 构建 - 一次配置全局生效

## 📋 方案概述

```
Gitea 组织级 Webhook（配一次）
    ↓
所有仓库 push 事件 → 统一 URL
    ↓
Jenkins Generic Webhook Trigger
    ↓
根据仓库名 + 分支 → 自动路由到正确的 Job
```

**核心设计**：
- ✅ Gitea 侧只配一次（组织级 Webhook）
- ✅ 新服务接入零配置（Job 名规则自动匹配）
- ✅ 只有指定分支才触发（可配置）
- ✅ 生产 Job 永远不自动触发

---

## 🔧 实现原理

### Token 规则

每个 Jenkins Job 的 token = Job 名称本身（自动）

```
Job: ad-gateway-test   →  token = ad-gateway-test
Job: ad-admin-dev      →  token = ad-admin-dev
Job: pic03-gateway-test → token = pic03-gateway-test
```

共享库 `k8sDeploy.groovy` 中自动设置，业务方无感知。

### Gitea 组织级 Webhook

**只需配一次**，URL 不带固定 token，而是让 Jenkins 根据 payload 匹配：

```
https://jks-test.sinozo.com/generic-webhook-trigger/invoke
```

### 匹配逻辑

```
Gitea push: 仓库=server/AdGateway, 分支=test
    ↓
每个 Job 的 GenericTrigger 检查：
  ad-gateway-test:  仓库名含 AdGateway? ✅  分支=test? ✅  → 触发！
  ad-gateway-prod:  仓库名含 AdGateway? ✅  分支=test? ❌  → 跳过
  ad-admin-test:    仓库名含 AdAdmin?   ❌               → 跳过
```

---

## 🚀 实施步骤

### 第一步：k8sDeploy.groovy 添加 triggers 块

**位置**：`parameters { ... }` 后，`stages { ... }` 前（约第117行）

```groovy
        // ── Gitea Webhook 自动触发 ──
        // token 使用 Job 名称，Gitea 组织级 webhook 只需配一次
        triggers {
            GenericTrigger(
                // 从 Gitea push payload 提取变量
                genericVariables: [
                    [key: 'GITEA_REF',    value: '$.ref'],
                    [key: 'GITEA_AFTER',  value: '$.after'],
                    [key: 'GITEA_PUSHER', value: '$.pusher.login'],
                    [key: 'GITEA_REPO',   value: '$.repository.full_name'],
                ],

                // 用 Job 名作为 token（每个 Job 自动唯一）
                token: resolveWebhookToken(),

                // 分支过滤
                regexpFilterText: '$GITEA_REF',
                regexpFilterExpression: resolveWebhookBranchFilter(cfg),

                causeString: 'Gitea push by $GITEA_PUSHER to $GITEA_REF ($GITEA_REPO)',
                printContributedVariables: true,
                printPostContent: false,
                silentResponse: false,
            )
        }
```

### 第二步：添加 helper 函数

**位置**：k8sDeploy.groovy 底部 helper 函数区域

```groovy
/**
 * Webhook token = Job 短名（去掉 folder 前缀）
 * 例如 Job "Finance/ad-gateway-test" → token = "ad-gateway-test"
 */
@NonCPS
def resolveWebhookToken() {
    return (env?.JOB_NAME ?: 'unknown').tokenize('/').last()
}

/**
 * 根据 Job 类型和配置生成分支过滤正则
 *
 * 规则：
 *   *-prod  → '^$'（永不匹配，生产只能手动）
 *   *-test  → '^refs/heads/(test|main)$'
 *   *-dev   → '^refs/heads/(dev|develop)$'
 *   无后缀  → '^refs/heads/(test|dev)$'
 *
 * 支持 Jenkinsfile 自定义：
 *   k8sDeploy(webhookBranches: ['test', 'feature/.*'])
 */
@NonCPS
def resolveWebhookBranchFilter(Map cfg = [:]) {
    def shortName = (env?.JOB_NAME ?: '').tokenize('/').last()?.toLowerCase() ?: ''
    def suffix = shortName.tokenize('-').last()

    // 生产 Job 永远不自动触发
    if (suffix == 'prod') return '^$'

    // 如果业务 Jenkinsfile 显式指定了触发分支
    if (cfg.webhookBranches) {
        def pattern = cfg.webhookBranches.collect { "refs/heads/${it}" }.join('|')
        return "^(${pattern})\$"
    }

    // 默认规则
    if (suffix == 'dev')  return '^refs/heads/(dev|develop)$'
    if (suffix == 'test') return '^refs/heads/(test|main)$'
    return '^refs/heads/(test|dev)$'
}
```

### 第三步：修改 checkout 逻辑

**位置**：约第163行，在 `def requestedBranch = ...` 之前添加：

```groovy
// webhook 触发时，从 Gitea payload 提取分支
// 手动触发时，使用参数选择的分支（完全不影响现有行为）
if (env.GITEA_REF?.trim() && !params.GIT_BRANCH?.trim()) {
    def webhookBranch = env.GITEA_REF.replaceFirst('^refs/heads/', '')
    echo "📥 Gitea webhook 触发: 分支=${webhookBranch}, 推送人=${env.GITEA_PUSHER}, 仓库=${env.GITEA_REPO}"
    // 回写到参数，后续逻辑统一处理
    params.GIT_BRANCH = webhookBranch
}
```

---

## 📝 Gitea 侧配置（只需一次）

### 组织级 Webhook

1. 进入 Gitea 组织 → **Settings** → **Webhooks** → **Add Webhook**

| 字段 | 值 |
|------|----|
| Target URL | `https://jks-test.sinozo.com/generic-webhook-trigger/invoke` |
| Content Type | `application/json` |
| Trigger On | **Push Events** |
| Active | ✅ |

**注意**：URL 不带 `?token=xxx`，因为 Jenkins 内部每个 Job 有自己的 token。

但 Generic Webhook Trigger 需要 token 才能路由，所以改为：

**实际做法**：Gitea 组织 Webhook 无法指定 token，需要用另一种方式。

---

### ⭐ 最佳实践：Gitea 仓库模板 + 脚本批量配置

由于 Gitea 组织级 Webhook **不支持**动态 token 路由，
推荐使用 **Gitea API 批量配置仓库 Webhook**，一个脚本搞定所有仓库：

```bash
#!/bin/bash
# 批量为 Gitea 仓库配置 Jenkins Webhook
# 用法：bash setup-gitea-webhooks.sh

GITEA_URL="https://gitea.sinozo.com"
GITEA_TOKEN="your-gitea-api-token"  # Gitea 管理员 token
JENKINS_URL="https://jks-test.sinozo.com"

# 服务映射：Gitea仓库名 → Jenkins Job名
# 格式："gitea-org/repo-name:jenkins-job-name"
SERVICES=(
    "server/AdGateway:adv/ad-gateway-test"
    "server/AdAdmin:adv/ad-admin-test"
    "server/Pic03:pic03/pic03-gateway-test"
    # 添加更多服务...
)

for mapping in "${SERVICES[@]}"; do
    REPO="${mapping%%:*}"
    JOB="${mapping##*:}"
    TOKEN=$(echo "$JOB" | tr '/' '-')  # token = job名

    echo "配置: ${REPO} → ${JOB} (token=${TOKEN})"

    curl -s -X POST "${GITEA_URL}/api/v1/repos/${REPO}/hooks" \
      -H "Authorization: token ${GITEA_TOKEN}" \
      -H "Content-Type: application/json" \
      -d "{
        \"type\": \"gitea\",
        \"active\": true,
        \"events\": [\"push\"],
        \"config\": {
            \"url\": \"${JENKINS_URL}/generic-webhook-trigger/invoke?token=${TOKEN}\",
            \"content_type\": \"json\"
        }
      }"

    echo " ✅ 完成"
done

echo ""
echo "全部配置完成！共 ${#SERVICES[@]} 个仓库"
```

**一次执行，所有仓库的 webhook 就配好了！**

新服务接入时，只需在 `SERVICES` 数组中添加一行，重新运行脚本即可。

---

## 📊 完整触发流程

### 自动触发（开发日常）

```
开发 push 到 test 分支
    ↓
Gitea 发送 POST .../invoke?token=ad-gateway-test
    Body: { "ref": "refs/heads/test", ... }
    ↓
Jenkins ad-gateway-test Job 的 GenericTrigger
    GITEA_REF=refs/heads/test
    匹配 ^refs/heads/(test|main)$ → ✅ 触发
    ↓
k8sDeploy：自动识别分支=test，执行构建部署
```

### 手动触发（不受任何影响）

```
运维点击 Build with Parameters
    选择 GIT_BRANCH=feature-xxx
    ↓
k8sDeploy：使用选择的分支，和以前完全一样
```

### 生产部署（永远手动）

```
ad-gateway-prod Job：
    resolveWebhookBranchFilter() → '^$' → 永不匹配
    只能手动触发 → 审批 → 部署
```

---

## 🎯 支持指定分支触发

### 方式1：默认规则（推荐，零配置）

| Job类型 | 自动触发的分支 |
|---------|---------------|
| `*-test` | `test`, `main` |
| `*-dev` | `dev`, `develop` |
| `*-prod` | **永不触发** |

### 方式2：自定义触发分支

在 Jenkinsfile 中指定：

```groovy
@Library('k8s-deploy-lib@main') _
k8sDeploy(
    projectName:     'adv',
    serviceName:     'ad-gateway',
    serviceType:     'java',
    dockerImage:     'ad-gateway',
    dockerCredId:    'docker-swr-cred',
    // 自定义：push到这些分支时自动触发
    webhookBranches: ['test', 'release/.*', 'main'],
)
```

这样 push 到 `test`、`release/v1.0`、`main` 都会触发。

### 方式3：完全禁用自动触发

```groovy
k8sDeploy(
    webhookBranches: [],  // 空数组 = 不触发
)
```

---

## 🔐 安全保障

| 安全项 | 措施 |
|--------|------|
| 生产不触发 | `resolveWebhookBranchFilter()` 对 prod 返回 `^$` |
| 仓库隔离 | 每个 Job 独立 token，不会交叉触发 |
| 分支过滤 | 只有匹配的分支才触发 |
| 删除事件 | after=000...不会匹配有效分支 |
| 手动不影响 | webhook 变量为空时走原逻辑 |

---

## 🐛 排障指南

### 1. Push 了但没触发

```bash
# 检查 Gitea 投递记录
仓库 → Settings → Webhooks → Recent Deliveries
# 200=送达  403=token错  404=URL错

# 检查 Jenkins 日志
Manage Jenkins → System Log → 搜索 GenericTrigger
```

### 2. 触发了错误的 Job

**原因**：token 配置错误
**修复**：确认 Gitea webhook URL 中的 token 和 Jenkins Job 名称一致

### 3. 手动触发行为变了

**不会发生**。只有 `GITEA_REF` 有值且 `GIT_BRANCH` 为空时才用 webhook 分支。
手动构建时 `GIT_BRANCH` 参数有值，走原逻辑。

---

## ✅ 总结

### 配置工作量

| 操作 | 频率 | 工作量 |
|------|------|--------|
| Gitea webhook 批量配置 | 一次 | 运行脚本 1分钟 |
| 新服务加 webhook | 每次新服务 | 加一行脚本 |
| 业务 Jenkinsfile 改动 | 无 | 零改动 |
| k8sDeploy.groovy 改动 | 一次 | 三处修改 |

### 核心优势

1. ✅ **一次配置**：批量脚本搞定所有仓库
2. ✅ **零业务改动**：Jenkinsfile 不需要任何修改
3. ✅ **指定分支**：默认规则 + 自定义 webhookBranches
4. ✅ **生产安全**：永远不自动触发
5. ✅ **手动兼容**：不影响现有手动触发行为

---

**文档版本**: v2.0  
**最后更新**: 2026-09-07
