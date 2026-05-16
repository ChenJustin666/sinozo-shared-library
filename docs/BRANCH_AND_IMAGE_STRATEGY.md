# 分支策略与镜像复用方案

> v1.0 · 平台组 owner  
> 适用：所有接入 k8s-deploy-lib 的服务

---

## 一、核心原则

### 原则 1：一个镜像多环境复用

```
┌───────────────────────────────────────────────────────────────┐
│  同一个 commit → 同一个镜像 → 跑遍 dev/test/prod                │
└───────────────────────────────────────────────────────────────┘
```

**不允许**为不同环境构建不同镜像。原因：
- 测试通过的镜像和生产部署的镜像必须是同一个
- 否则"测试通过"没有意义
- 这是 [12-Factor App](https://12factor.net/build-release-run) 的核心原则

### 原则 2：镜像内不含环境差异

```
✅ 允许：镜像里有 application.yml（默认配置）
❌ 禁止：镜像里有 application-test.yml + application-prod.yml
```

环境差异通过以下机制注入：
- Nacos 配置中心（业务配置）
- K8s ConfigMap（业务配置）
- K8s 环境变量（基础设施信息）
- Helm values 的 `java.opts` / `env`（运行时参数）

### 原则 3：分支即环境

```
main  → dev  环境（push 自动部署）
test  → test 环境（合并自动部署）
prod  → prod 环境（手动触发 + 审批）
```

代码**只能从前往后流动**：`feature → main → test → prod`，禁止反向 cherry-pick。

---

## 二、分支模型（GitLab Flow 简化版）

### 2.1 分支结构

```
                    git tag (v1.2.3) ─┐
                                       │
   feature/xxx ──→ main ──→ test ──→ prod
        ▲           │         │        │
        │           ▼         ▼        ▼
       MR        dev 部署   test 部署  prod 部署
                  自动      自动      手动+审批
```

### 2.2 分支用途

| 分支 | 谁创建 | 谁合并 | 部署到 | 部署方式 |
|------|-------|-------|-------|---------|
| `feature/*` | 开发 | - | 不部署 | 仅 CI 校验（编译/单测） |
| `main` | - | 开发提 MR | dev 集群 | push 即部署 |
| `test` | 开发或 QA | QA 验收 | test 集群 | 合并即部署 |
| `prod` | 运维 | 运维 | prod 集群 | 手动 + 审批 |
| `hotfix/*` | 运维 | 运维 | prod | 紧急 hotfix 流程 |

### 2.3 分支保护规则（强烈建议在 Gitea/GitLab 配置）

| 分支 | 保护 |
|------|------|
| `main` | 必须 MR + 至少 1 reviewer + CI 通过 |
| `test` | 只能从 main fast-forward 合并 |
| `prod` | 只能从 test fast-forward 合并 + 必须有 git tag |

**为什么强制 fast-forward？** 防止开发绕过 dev/test 直接合并代码到 prod。

---

## 三、典型场景流程

### 场景 1：开发新功能

```
开发者:
  git checkout main && git pull
  git checkout -b feature/login-bug
  ... 写代码 ...
  git push origin feature/login-bug
  
Gitea:
  开发创建 MR → main
  CI 自动跑：编译 + 单测 + 代码扫描（不构建镜像）
  
Reviewer:
  review 通过 → 合并到 main
  
自动:
  push main → 触发 ad-gateway-dev Job
  dev 环境构建镜像 image:R<commit> 并推送 registry
  helm upgrade dev → 部署完成
```

### 场景 2：测试环境验证

```
开发或 QA:
  在 Gitea 上把 main 合并到 test
  （main HEAD 必须等于 test HEAD 之后的某个 commit）
  
自动:
  push test → 触发 ad-gateway-test Job
  Pipeline 检测：DEPLOY_ENV=test，跳过构建
  Pipeline 验证：image:R<commit> 在 registry 中存在 ✓
  helm upgrade test → 部署完成（用同一个镜像）
  
QA:
  在 test 环境验收
  通过 → 进入 prod 流程
  不通过 → 回到开发，开发修完走 main → test 流程
```

### 场景 3：生产部署

```
运维:
  确认 test 验收通过
  在 Gitea 上：
    1. 把 test 合并到 prod
    2. 给 prod HEAD 打 git tag（如 v2026.05.16-1）
  
触发部署:
  Jenkins → ad-gateway-prod → Build with Parameters
    GIT_BRANCH: v2026.05.16-1（或 prod）
    DEPLOY_ENV: prod
    ACTION: deploy
  
Pipeline 流程:
  1. checkPermission：仅 DEVOPS_USERS 白名单能触发 ✓
  2. checkout prod 分支
  3. 验证 image:R<commit> 在 registry 中存在 ✓
  4. ⏸ 人工审批（input）—— 等运维点 "确认部署"
  5. helm upgrade prod
  6. 健康检查
```

### 场景 4：生产紧急回滚

```
运维:
  Jenkins → ad-gateway-prod → Build with Parameters
    DEPLOY_ENV: prod
    ACTION: rollback
    ROLLBACK_REVISION: 0   （0=上一个版本）
  
Pipeline:
  helm rollback ad-gateway 0 -n adv-prod
  → 30 秒内回滚到上一个版本
```

### 场景 5：紧急 hotfix（不走 main）

```
运维:
  git checkout prod && git pull
  git checkout -b hotfix/oom-fix
  ... 改代码 ...
  git push origin hotfix/oom-fix
  
临时构建镜像（dev Job 借道）:
  Jenkins → ad-gateway-dev → Build with Parameters
    GIT_BRANCH: hotfix/oom-fix
    DEPLOY_ENV: dev      （注意：构建到 dev 集群验证）
  
验证后，合并回 prod + main:
  Gitea：hotfix/oom-fix → prod（先发布止血）
  Gitea：hotfix/oom-fix → main（保持代码同步）
  
打 tag + 部署 prod:
  在 prod HEAD 打 tag v2026.05.16-hotfix
  Jenkins → ad-gateway-prod → Build with IMAGE_TAG=R<commit-of-hotfix>
```

---

## 四、镜像 tag 规范

| 分支 / Tag | 镜像 tag | 谁构建 | 何时构建 |
|------------|---------|-------|---------|
| `feature/*` | 不构建 | - | - |
| `main` HEAD | `R<commit-short>` | dev Job | push main |
| `test` 合并后 | **复用** | 不构建 | 部署时验证存在 |
| `prod` 合并后 | **复用** | 不构建 | 部署时验证存在 |
| `v*.*.*` tag | **复用** | 不构建 | 部署时验证存在 |

**镜像生命周期**：
- `R<commit>` 永久保留至少 30 天（防止 prod 想回滚但镜像被 GC）
- `v*.*.*` tag 永久保留（合规审计）

---

## 五、Jenkins Job 配置标准

每个服务**3 个 Job**（DEPLOY_ENV 默认值不同）：

### Job 1：`{service}-dev`

```
Pipeline script from SCM
  Repository: <业务仓库>
  Branch:     */main
  Script Path: Jenkinsfile

Default Parameters:
  DEPLOY_ENV: dev
  
Triggers:
  Webhook（main 分支 push 触发）
```

### Job 2：`{service}-test`

```
Pipeline script from SCM
  Repository: <业务仓库>
  Branch:     */test
  Script Path: Jenkinsfile

Default Parameters:
  DEPLOY_ENV: test
  
Triggers:
  Webhook（test 分支 push 触发）
```

### Job 3：`{service}-prod`

```
Pipeline script from SCM
  Repository: <业务仓库>
  Branch:     */prod   或   refs/tags/v*
  Script Path: Jenkinsfile

Default Parameters:
  DEPLOY_ENV: prod
  
Triggers:
  ❌ 不配 webhook（手动触发）
  
权限:
  Authorization Strategy: Project-based Matrix
    devops 组: Build, Configure, Read
    其他: 仅 Read
```

---

## 六、新接入服务的标准流程

```
1. 运维：./automation/init-service.sh <project> <service> [type]
   → 生成 Dockerfile / Jenkinsfile / deploy/values{,*-test,*-prod}.yaml

2. 开发：把生成的文件放到业务仓库根目录，push 到 main

3. 开发：在 Gitea 创建 test 分支（基于 main）
        在 Gitea 创建 prod 分支（基于 main）

4. 运维：在 Jenkins 创建 3 个 Job
        分别对应 dev / test / prod

5. 测试触发：
   推 main → dev 自动部署 → 验证通过
   合并 main 到 test → test 自动部署 → 验证通过
   合并 test 到 prod + 打 tag → 手动触发 prod 部署 → 审批通过
```

---

## 七、常见问题（FAQ）

**Q1：为什么 test 不构建镜像，要求 dev 先构建？**  
A：保证测试环境跑的镜像和后续生产环境跑的是同一个。否则 test 测的镜像 ≠ prod 的镜像，"测试通过"失去意义。

**Q2：如果 dev 构建失败怎么办？**  
A：开发修复后再 push main，dev 重新构建。修好之前 test/prod 用旧镜像继续工作。

**Q3：能不能跳过 test 直接部署 prod？**  
A：技术上可以（手动指定 IMAGE_TAG），但**强烈不推荐**。建议先在 test 验证。

**Q4：手动指定 IMAGE_TAG 在什么场景用？**  
A：紧急回滚到 N-2 或更老版本时（helm rollback 只能回上一个，更老的要手动指定 image tag 重新部署）。

**Q5：feature 分支为什么不构建镜像？**  
A：节省 CI 资源。feature 阶段只跑单测足够，等合并到 main 再构建。如果开发要在 feature 分支测部署，可以临时手动触发 dev Job 指定 GIT_BRANCH=feature/xxx。

**Q6：prod 必须打 git tag 吗？**  
A：强烈推荐但不强制。tag 的作用是审计和回滚定位。也可以接受"prod 分支 HEAD"作为版本标识。

**Q7：dev 环境部署失败要不要阻塞测试？**  
A：不阻塞。dev 失败不影响 test 用上一个成功的镜像。但要看监控告警。

**Q8：如果业务代码没变，只想改 values 重新部署 prod？**  
A：直接在 Jenkins 触发 ad-gateway-prod Job，ACTION=deploy。Pipeline 检测代码无变化，复用现有镜像，只 helm upgrade 应用新 values。

---

## 八、对老服务的兼容性

老服务（用 `projects/{project}/{env}/{svc}/values-{env}.yaml`）继续工作不受影响：
- Pipeline 检测到业务仓库无 `deploy/` 目录时自动回退老路径
- 镜像复用机制对新老服务一视同仁
- 建议按月度迁移老服务到新模式（开发改一次 Jenkinsfile + 加 deploy/ 目录即可）

迁移命令参考：
```bash
# 把 k8s-deploy 仓库里的 values 移动到业务仓库
cd k8s-deploy
git mv projects/adv/test/ad-gateway/values-test.yaml /tmp/migrate/
# 在业务仓库
mkdir -p deploy
mv /tmp/migrate/values-test.yaml deploy/
# 清掉运维字段（参考 docs/FIELD_OWNERSHIP.md）
vim deploy/values-test.yaml
# 校验
/path/to/k8s-deploy/automation/values-validate.sh deploy/values-test.yaml
```
