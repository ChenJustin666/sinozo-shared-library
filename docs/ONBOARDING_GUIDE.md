# 新服务接入完整指南（从 0 到 1）

> 适用：新服务从无到有，跑通 dev → test → prod 三环境  
> 预计耗时：30 分钟（首次接入）/ 10 分钟（已有平台经验）  
> 角色标注：📦 = 运维操作，💻 = 开发操作，🤖 = 自动执行

---

## 关键概念先看清楚

```
┌──────────────────────────────────────────────────────────────┐
│  "一个镜像跑三环境" + "三个 Job"  不矛盾！                     │
│                                                              │
│  镜像（产物）：1 个 commit → 构建 1 次 → 跑遍 dev/test/prod    │
│  Job（入口）：3 个（dev/test/prod），但只有 dev Job 会构建      │
│                                                              │
│  类比：1 辆车开去 3 个地方，需要 3 个目的地按钮                 │
└──────────────────────────────────────────────────────────────┘
```

| 入口 | 监听分支 | 是否构建镜像 | 触发方式 |
|------|---------|-----------|---------|
| `{svc}-dev` | `main` | ✅ 是（每次新 commit）| Webhook 自动 |
| `{svc}-test` | `test` | ❌ 否（复用 dev 镜像）| Webhook 自动 |
| `{svc}-prod` | `prod`/`tag` | ❌ 否（复用同 commit 镜像）| 手动 + 审批 |

---

## 整体流程图

```
╔════════════════════════════════════════════════════════════╗
║  Step 0：环境准备（仅首次，一次性）  📦                      ║
║    Jenkins 插件 / Shared Library / 凭据 / k8s-deploy 仓库   ║
╚════════════════════════════════════════════════════════════╝
                            ▼
╔════════════════════════════════════════════════════════════╗
║  Step 1：运维初始化新服务（5 分钟）  📦                      ║
║    跑 init-service.sh → 生成模板文件                         ║
╚════════════════════════════════════════════════════════════╝
                            ▼
╔════════════════════════════════════════════════════════════╗
║  Step 2：开发接入业务仓库（10 分钟）  💻                     ║
║    复制文件 → 创建 main/test/prod 三分支 → push              ║
╚════════════════════════════════════════════════════════════╝
                            ▼
╔════════════════════════════════════════════════════════════╗
║  Step 3：运维创建 3 个 Jenkins Job（5 分钟）  📦             ║
║    {svc}-dev / {svc}-test / {svc}-prod                      ║
╚════════════════════════════════════════════════════════════╝
                            ▼
╔════════════════════════════════════════════════════════════╗
║  Step 4：首次部署（10 分钟）                                 ║
║    main → dev (auto)  ⇒  合并 test → test (auto)            ║
║    ⇒  合并 prod + 打 tag → prod (manual + approve)           ║
╚════════════════════════════════════════════════════════════╝
```

---

## Step 0：环境准备（仅首次）📦

> 跳过条件：Jenkins / k8s-deploy 仓库 / Shared Library 已配置过 → 直接 Step 1

### 0.1 Jenkins 插件

`Manage Jenkins → Manage Plugins → Available`

```
✅ Pipeline
✅ Git Parameter
✅ Generic Webhook Trigger（或 Gitea Plugin）
✅ Kubernetes CLI Plugin
✅ Credentials Binding
✅ Role-based Authorization Strategy（推荐）
```

安装后重启。

### 0.2 全局工具

`Manage Jenkins → Global Tool Configuration`

```
JDK:
  Name: jdk 1.8     JAVA_HOME: /usr/lib/jvm/java-8-openjdk-amd64
  Name: jdk 17      JAVA_HOME: /usr/lib/jvm/java-17-openjdk-amd64

Maven:
  Name: maven 3.8   MAVEN_HOME: /usr/local/maven
```

### 0.3 全局环境变量（权限白名单）

`Manage Jenkins → Configure System → Global properties → Environment variables`

```
DEVOPS_USERS           = admin,ops-zhang,ops-li      # 谁能触发 prod 部署
PROD_APPROVERS         = admin,ops-zhang             # 谁能在审批阶段点确认
PROD_APPROVAL_TIMEOUT  = 60                          # 审批等待分钟数
```

### 0.4 准备 k8s-deploy 仓库（在 Jenkins 节点）

```bash
sudo su - jenkins
mkdir -p /var/lib/jenkins/workspace/deploy
cd /var/lib/jenkins/workspace/deploy
git clone http://gitea.example.com/ops/k8s-deploy.git
ls k8s-deploy/   # 应该看到：baselines/ charts/ shared-library/ automation/ docs/
```

### 0.5 配置 Shared Library

`Manage Jenkins → Configure System → Global Pipeline Libraries → Add`

```
Name:           k8s-deploy-lib
Default version: main
Load implicitly: ❌

Retrieval method: Modern SCM
  SCM:                Git
  Project Repository: http://gitea.example.com/ops/k8s-deploy.git
  Credentials:        (Git 凭据)

Library Path:   shared-library     ← 关键！库在子目录
```

### 0.6 全局凭据

`Manage Jenkins → Manage Credentials → (global) → Add Credentials`

| 凭据 ID | 类型 | 用途 |
|---------|------|------|
| `git-{project}-cred` | Username/Password | Git 拉代码 |
| `docker-swr-cred` | Username/Password | 镜像仓库（全公司一份）|
| `k8s-{project}-dev` | Secret file (kubeconfig) | dev 集群 |
| `k8s-{project}-test` | Secret file (kubeconfig) | test 集群 |
| `k8s-{project}-prod` | Secret file (kubeconfig) | prod 集群 |

### 0.7 验证

```groovy
// 创建测试 Pipeline，跑一次
@Library('k8s-deploy-lib@main') _
pipeline {
    agent any
    stages { stage('Test') { steps { echo "✅ Library OK" } } }
}
```

---

## Step 1：运维初始化新服务（5 分钟）📦

```bash
cd /var/lib/jenkins/workspace/deploy/k8s-deploy

./automation/init-service.sh adv ad-gateway java
#                              │   │           │
#                              │   │           └─ 类型：java / nodejs
#                              │   └────────── 服务名
#                              └────────────── 项目名
```

脚本输出：

```
🚀 初始化服务: adv/ad-gateway (java)

📦 生成业务仓库文件 → /tmp/ad-gateway-init-XXXXX/
  ✓ Dockerfile
  ✓ Jenkinsfile
  ✓ deploy/values.yaml
  ✓ deploy/values-test.yaml
  ✓ deploy/values-prod.yaml

🔍 校验生成的业务 values 是否符合契约...
  ✓ values.yaml
  ✓ values-test.yaml
  ✓ values-prod.yaml
  所有业务 values 符合字段所有权契约

📋 服务级 baseline:
  默认不创建（90% 服务用 _global.yaml 就够）

✅ 初始化完成！
```

**运维做的事**：
1. 运行脚本
2. 把 `/tmp/ad-gateway-init-XXXXX/` 路径告诉开发
3. **不需要在 k8s-deploy 仓库 push 任何文件**（绝大多数服务用 `_global.yaml` 兜底就够）

---

## Step 2：开发接入业务仓库（10 分钟）💻

### 2.1 复制文件到业务仓库

```bash
cd ~/projects/ad-gateway              # 业务代码仓库

cp -r /tmp/ad-gateway-init-XXXXX/{Dockerfile,Jenkinsfile,deploy} .

ls
# Dockerfile  Jenkinsfile  deploy/  pom.xml  src/  ...

cat Jenkinsfile
# @Library('k8s-deploy-lib@main') _
# k8sDeploy(
#     projectName:  'adv',
#     serviceName:  'ad-gateway',
#     ...
# )

ls deploy/
# values.yaml  values-test.yaml  values-prod.yaml
```

### 2.2 微调 deploy/ 配置

```bash
vim deploy/values-test.yaml      # 调整 test 环境的 java.opts、env 等
vim deploy/values-prod.yaml      # 调整 prod 副本数、HPA 等
```

⚠️ **不能写**：`namespace` / `securityContext` / `resources.limits` / `nodeSelector` 等运维字段（CI 会拒绝），详见 `docs/FIELD_OWNERSHIP.md`。

### 2.3 推送 main 分支

```bash
git add Dockerfile Jenkinsfile deploy/
git commit -m "feat: 容器化部署配置"
git push origin main
```

### 2.4 创建 test / prod 分支

```bash
git checkout -b test && git push origin test
git checkout -b prod && git push origin prod
git checkout main
```

### 2.5 在 Gitea 设置分支保护

```
仓库 Settings → Branches:

main:  ✅ 必须 MR  ✅ 至少 1 reviewer  ✅ CI 通过
test:  ✅ 只能 fast-forward 合并  ✅ 来源分支限定 main
prod:  ✅ 只能 fast-forward 合并  ✅ 来源分支限定 test  ✅ 必须有 git tag（可选）
```

---

## Step 3：运维创建 3 个 Jenkins Job（5 分钟）📦

> 共用同一个 Jenkinsfile（业务仓库根目录），但每个 Job 监听不同分支、默认 DEPLOY_ENV 不同。

### 3.1 Job 1：`ad-gateway-dev`

```
Jenkins → New Item → Pipeline
Item name: ad-gateway-dev

【Pipeline 配置】
Definition:    Pipeline script from SCM
SCM:           Git
Repository:    http://gitea.example.com/adv/ad-gateway.git
Credentials:   git-adv-cred
Branches to build:
  Branch Specifier: */main          ← 只监听 main
Script Path:   Jenkinsfile

【Build Triggers】
✅ Generic Webhook Trigger
   Token:                ad-gateway-dev-token-<random-string>
   Filter expression:    ^refs/heads/main$
   Filter text:          $ref

【保存后第一次构建会自动注册参数，之后默认】
DEPLOY_ENV: dev
```

### 3.2 Job 2：`ad-gateway-test`

```
配置同上，差异：
  Branch Specifier:    */test
  Filter expression:   ^refs/heads/test$
  Token:               ad-gateway-test-token-<random>
  默认 DEPLOY_ENV:    test
```

### 3.3 Job 3：`ad-gateway-prod`

```
配置同上，差异：
  Branch Specifier:    */prod    或    refs/tags/v*
  ❌ 不配 Webhook（手动触发）
  默认 DEPLOY_ENV:    prod

【权限（推荐）】
Authorization Strategy: Project-based Matrix
  devops 组: Build, Cancel, Configure, Read
  其他用户:  仅 Read
```

### 3.4 Gitea Webhook 配置

业务仓库 Settings → Webhooks → Add Webhook → Gitea

```
Webhook 1（监听 main）：
  Target URL: http://jenkins.example.com/generic-webhook-trigger/invoke?token=ad-gateway-dev-token-<random>
  HTTP Method: POST
  Content Type: application/json
  Events: Push events
  Branch filter: main

Webhook 2（监听 test）：
  Target URL: http://jenkins.example.com/generic-webhook-trigger/invoke?token=ad-gateway-test-token-<random>
  Events: Push events
  Branch filter: test
```

prod 不配 Webhook（手动触发）。

测试 Webhook：在 Gitea Webhook 页面点 "Test Delivery"，应该 200 OK 且 Jenkins 自动触发一次。

---

## Step 4：首次部署演练（10 分钟）

### 4.1 触发 dev 部署（自动）🤖

开发刚才已经 push 了 main。Webhook 应该已经触发 `ad-gateway-dev` Job：

```
Stage 1: 权限检查              ✅ dev 环境，无需特殊权限
Stage 2: Checkout 业务代码     ✅ commit=R<hash>, tag=R<hash>
Stage 3: 初始化                ✅ values 文件检查
Stage 4: 构建镜像（仅 dev）    🔨 Maven build → Docker build → push registry
Stage 5: 部署到 K8s           ✅ helm upgrade adv-dev
✅ deploy 成功: ad-gateway (dev) tag=R<hash>
```

验证：
```bash
kubectl get pods -n adv-dev
kubectl logs -f deployment/ad-gateway -n adv-dev
```

### 4.2 触发 test 部署（合并）

```bash
# 把 main 合并到 test（fast-forward）
git checkout test
git merge --ff-only main
git push origin test
```

观察 `ad-gateway-test` Job：

```
Stage 1: 权限检查                ✅
Stage 2: Checkout 业务代码       ✅ tag=R<同一个 hash>
Stage 3: 初始化                  ✅
Stage 4: 构建镜像                ⏭ SKIP（不是 dev 环境）
Stage 5: 验证镜像（test/prod）   🔍 docker manifest inspect
                                 ✅ 镜像已存在，可复用
Stage 6: 部署到 K8s             ✅ helm upgrade adv-test
✅ deploy 成功: ad-gateway (test) tag=R<同一个 hash>
```

⭐ 注意：和 dev 完全一样的 tag，**没有重新构建**。

### 4.3 触发 prod 部署（手动 + 审批）📦

```bash
# 测试通过后
git checkout prod
git merge --ff-only test
git push origin prod

# 打 tag（推荐）
git tag v2026.05.16-1 prod
git push origin v2026.05.16-1
```

```
Jenkins → ad-gateway-prod → Build with Parameters
  GIT_BRANCH:  v2026.05.16-1（或直接 prod）
  DEPLOY_ENV:  prod
  ACTION:     deploy
  IMAGE_TAG:  (留空)
点击 Build
```

执行流程：

```
Stage 1: 权限检查                🔒 触发者: ops-zhang ✅（在 DEVOPS_USERS 白名单）
Stage 2: Checkout 业务代码       ✅
Stage 3: 初始化                  ✅
Stage 4: 验证镜像（test/prod）   ✅ 镜像已存在
Stage 5: 生产部署审批            ⏸️ 等待 PROD_APPROVERS 在 Jenkins UI 点 "确认部署"
                                 (60 分钟超时)

[运维在 Jenkins UI 点击 "✅ 确认部署"]

Stage 6: 部署到 K8s             ✅ helm upgrade adv-prod
✅ deploy 成功: ad-gateway (prod) tag=R<同一个 hash>
```

---

## 五、操作手卡（贴在工位）

### 日常发布

```
开发推 main           ─→ dev 自动部署
合并 main → test     ─→ test 自动部署
合并 test → prod     ─→ Jenkins 触发 prod Job ─→ 审批 ─→ 部署
```

### 紧急回滚

```
Jenkins → ad-gateway-prod → Build with Parameters
  ACTION: rollback
  ROLLBACK_REVISION: 0     # 0=上一个版本
点击 Build → 30 秒回滚完成
```

### 重启服务（不变镜像）

```
Jenkins → ad-gateway-test → Build with Parameters
  ACTION: restart
点击 Build
```

### 紧急 hotfix（绕过 main）

```
git checkout prod && git checkout -b hotfix/oom-fix
... 改代码 ...
git push origin hotfix/oom-fix

# 借用 dev Job 临时构建一个镜像
Jenkins → ad-gateway-dev → Build with Parameters
  GIT_BRANCH: hotfix/oom-fix
  DEPLOY_ENV: dev          # 临时部署到 dev 验证

# 验证 OK 后合并回 prod 和 main
git checkout prod && git merge --no-ff hotfix/oom-fix && git push
git checkout main && git merge --no-ff hotfix/oom-fix && git push

# 在 prod HEAD 打 tag
git tag v2026.05.16-hotfix prod && git push origin v2026.05.16-hotfix

# 触发 prod 部署
Jenkins → ad-gateway-prod → Build with Parameters
  GIT_BRANCH: v2026.05.16-hotfix
  IMAGE_TAG:  R<hotfix-commit-hash>   # 因为 hotfix 镜像在 dev 构建时用的是 hotfix commit
```

### 查最终配置（debug "为什么不生效"）

```bash
helm template ad-gateway \
  /var/lib/jenkins/workspace/deploy/k8s-deploy/charts/generic-service \
  -f /var/lib/jenkins/workspace/deploy/k8s-deploy/baselines/_global.yaml \
  -f deploy/values-prod.yaml \
  | less
```

---

## 六、容易踩的坑

| 坑 | 现象 | 解决 |
|----|-----|------|
| test 部署失败：镜像不存在 | "镜像不存在: image:Rxxx" | 先去 `{svc}-dev` Job 部署一次（构建镜像）再合并到 test |
| prod 部署被卡住 | 一直在 "生产部署审批" | 让审批人去 Jenkins UI 点 "✅ 确认部署" 按钮 |
| values 校验失败 | "字段所有权校验失败" | 看报错列出的字段，从 `deploy/values-*.yaml` 删掉这些字段 |
| Webhook 没触发 | push 后 Job 没自动跑 | 检查 Token / URL / 防火墙；Gitea → Webhooks → Recent Deliveries |
| 开发改了 prod values 没生效 | helm upgrade 跑了但配置没变 | 99% 是被 `_global.yaml` 覆盖了，跑 `helm template` 看最终值 |
| 第一次 push main 没构建 | dev Job 没启动 | 检查 Webhook Token 和 Filter expression `^refs/heads/main$` |
| prod 触发显示无权限 | "用户 xxx 无权触发生产部署" | 让管理员把用户加到 Jenkins 全局环境变量 DEVOPS_USERS |

---

## 七、引用文档

| 文档 | 用途 |
|------|------|
| `docs/FIELD_OWNERSHIP.md` | 字段所有权契约（哪些开发能改，哪些不能） |
| `docs/BRANCH_AND_IMAGE_STRATEGY.md` | 分支与镜像策略（设计原理） |
| `docs/04-完整部署实战指南.md` | Jenkins / Shared Library 配置细节 |
| `docs/05-进阶配置-Webhook与Secret管理.md` | Webhook 与 Secret 进阶 |
