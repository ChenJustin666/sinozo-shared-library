# 镜像 Promotion 方案（多 SWR Project 隔离）

> v1.0 · 平台组 owner  
> 适用：所有接入 k8s-deploy-lib 的服务

---

## 一、为什么要 Promotion？

### 现状

我们的镜像仓库（华为云 SWR）按 organization（即 SWR 的 "project"）隔离：
- `sinozo-test` — 测试环境凭据可访问
- `sinozo-prod` — 仅生产凭据可访问

### 问题

如果直接"在 prod 阶段重新 build 一次镜像"会有两个致命问题：
1. **测试白做**：test 测的镜像 ≠ prod 部署的镜像，违反 12-Factor App 原则
2. **构建不可重现**：编译时的依赖、时间戳、layer 顺序都可能不同

### 解决方案

**Image Promotion**：不重新 build，而是用 `docker pull + tag + push` 把镜像从 test project "提升"到 prod project。

```
sinozo-test/ad-gateway:Rxxx       (test 部署用这个)
        │
        │  docker pull
        ▼
   [Jenkins 节点本地]              ← layer 完全复用
        │
        │  docker tag → sinozo-prod/ad-gateway:Rxxx
        │  docker push
        ▼
sinozo-prod/ad-gateway:Rxxx       (prod 部署用这个，layer hash 完全一致)
```

**关键**：promotion 是 **layer 复制**，不是重新 build。  
两个仓库里的镜像 layer hash 完全相同，**就是同一个镜像**。

---

## 二、行业实践（"Build Once, Deploy Many"）

| 公司/工具 | 实现方式 |
|-----------|---------|
| **Google** Container Registry | `gcrane copy src dst` |
| **Red Hat** OpenShift | `skopeo copy docker://src docker://dst` |
| **Netflix** Spinnaker | Pipeline 内置 promotion stage |
| **阿里云** 云效/AppStack | 内置镜像同步功能 |
| **GitLab** Auto DevOps | `promote_to_xxx` job |
| **本方案** | `docker pull + tag + push`（最普及，无额外依赖） |

> 引用：[12-Factor App #5: Build, release, run](https://12factor.net/build-release-run)

---

## 三、本方案的字段定义

### `baselines/_global.yaml`

```yaml
image:
  registry: swr.ap-southeast-3.myhuaweicloud.com

  # 多 SWR project 映射
  # key   = DEPLOY_ENV
  # value = SWR organization 名
  projects:
    dev:  sinozo-test    # dev 推到 test project（共享避免重复构建）
    test: sinozo-test    # test 部署也用同一个
    prod: sinozo-prod    # prod 用独立 project，由 Pipeline 自动 promotion 进来

  pullSecret: regcred
  pullPolicy: IfNotPresent
```

**关键约定**：
- `dev` 和 `test` **必须用同一个 project**，否则需要在 dev → test 之间也加一次 promotion（多余）
- `prod` 用独立 project，CI 自动从 dev/test project 把镜像 promote 过来

---

## 四、流水线工作机制

### 4.1 镜像构建条件（自动判断）

```
当 DEPLOY_ENV != 'prod'
   且 IMAGE_TAG 没有手动指定
   且 镜像在 IMAGE_PROJECT_BUILD（即 sinozo-test）中不存在
→ 构建并推送
```

通过 `docker manifest inspect` 检查镜像是否已存在，已存在直接跳过——**保证一个 commit 只构建 1 次**。

### 4.2 三种环境组合的实际执行

#### 场景 A：只有 test + prod（多数现有服务）

```
  推 test 分支 (Webhook 触发 ad-gateway-test Job)
    ├ 计算 tag = R<commit>
    ├ 解析 IMAGE_PROJECT = sinozo-test (test→sinozo-test)
    ├ 检查 sinozo-test/ad-gateway:R<commit> 是否存在
    │   不存在 → docker build → push sinozo-test/ad-gateway:R<commit>
    ├ 验证镜像存在 ✓
    └ helm upgrade test (拉 sinozo-test/ad-gateway:R<commit>)

  合并 test → prod, 触发 ad-gateway-prod Job
    ├ 计算 tag = R<commit>（同一个 commit）
    ├ 解析 IMAGE_PROJECT = sinozo-prod (prod→sinozo-prod)
    ├ 解析 IMAGE_PROJECT_BUILD = sinozo-test
    ├ 跳过构建（DEPLOY_ENV == prod）
    ├ ✨ Image Promotion:
    │     docker pull  sinozo-test/ad-gateway:R<commit>
    │     docker tag   ... sinozo-prod/ad-gateway:R<commit>
    │     docker push  sinozo-prod/ad-gateway:R<commit>
    ├ 验证 sinozo-prod/ad-gateway:R<commit> 存在 ✓
    ├ 人工审批 ⏸
    └ helm upgrade prod (拉 sinozo-prod/ad-gateway:R<commit>)

✅ 镜像只构建 1 次（在 test Job 中）
✅ prod 用的镜像和 test 完全一样（layer 复用）
```

#### 场景 B：完整 dev + test + prod

```
  推 main 分支 (触发 ad-gateway-dev Job)
    ├ 检查镜像不存在 → 构建 → push sinozo-test
    └ helm upgrade dev (拉 sinozo-test)

  合并 main → test (触发 ad-gateway-test Job)
    ├ 检查镜像 sinozo-test/...:R<commit> 已存在 → 跳过构建
    └ helm upgrade test (拉同一个镜像)

  合并 test → prod (手动触发 ad-gateway-prod Job)
    ├ 跳过构建（prod）
    ├ Image Promotion sinozo-test → sinozo-prod
    ├ 审批
    └ helm upgrade prod

✅ 镜像还是只构建 1 次（在 dev Job 中）
```

#### 场景 C：只有 prod（极少数）

```
  触发 ad-gateway-prod Job
    ├ DEPLOY_ENV == prod → 跳过构建（when 卡住）
    ├ 验证镜像存在 ❌
    └ 报错："镜像不存在，需先去非 prod 环境构建"

⚠️  方案不允许直接 prod build。
建议至少加一个 test 环境，或者 prod 部署前手动跑一次 dev/test Job。
```

### 4.3 Pipeline Stage 列表

```
Stage 1: 权限检查
Stage 2: Checkout 业务代码 + 解析镜像目标 (IMAGE_PROJECT, IMAGE_PROJECT_BUILD)
Stage 3: 初始化
Stage 4: 构建镜像（按需）       ← when: DEPLOY_ENV != prod && 镜像不存在
Stage 5: Image Promotion       ← when: DEPLOY_ENV == prod && project 不同
Stage 6: 验证镜像（部署前最后一道关）
Stage 7: 生产部署审批           ← when: DEPLOY_ENV == prod
Stage 8: 部署到 K8s
```

---

## 五、配置变更指引

### 5.1 当前默认（华为云 SWR，test/prod 分 project）

```yaml
# baselines/_global.yaml
image:
  registry: swr.ap-southeast-3.myhuaweicloud.com
  projects:
    dev:  sinozo-test
    test: sinozo-test
    prod: sinozo-prod
```

直接用即可。

### 5.2 切换成单 project（如果未来合并）

```yaml
image:
  projects:
    dev:  sinozo
    test: sinozo
    prod: sinozo
```

Pipeline 会自动检测到 `IMAGE_PROJECT == IMAGE_PROJECT_BUILD`，**跳过 promotion 阶段**——零代码改动。

### 5.3 加新环境（如 staging）

```yaml
image:
  projects:
    dev:     sinozo-test
    test:    sinozo-test
    staging: sinozo-staging   # 独立 project
    prod:    sinozo-prod
```

然后在 `k8sDeploy.groovy` 的 `DEPLOY_ENV` choice 加上 `staging`。

---

## 六、Jenkins 凭据要求

`docker-swr-cred` 这一个凭据需要能访问**所有** SWR project（test/prod 等）。

如果不能（例如安全要求 test 凭据完全无法访问 prod），有 3 种方案：

### 方案 1：用单一 robot 账号，赋予两个 project 的写权限（推荐）

最简单。SWR 支持给一个账号同时授权多个 organization。

### 方案 2：用两个凭据，promotion 时分别登录

需要改 `promoteImage.groovy`，用两个不同的凭据：

```groovy
withCredentials([usernamePassword(credentialsId: 'docker-swr-test-cred', ...)]) {
    sh "docker pull ..."
}
withCredentials([usernamePassword(credentialsId: 'docker-swr-prod-cred', ...)]) {
    sh "docker push ..."
}
```

### 方案 3：用 SWR 自带的 cross-region replication

华为云 SWR 支持仓库间自动同步，不需要 Jenkins 介入 promotion，但要付费且配置较复杂。

**当前实现使用方案 1**。如需切换到方案 2，告知运维改 `promoteImage.groovy`。

---

## 七、镜像生命周期建议

| 镜像位置 | 保留策略 | 原因 |
|----------|---------|------|
| `sinozo-test/*` | 30 天 | 防止 prod 想回滚但 promotion 找不到源镜像 |
| `sinozo-prod/*` | 永久（或 90 天 + 关键版本永久）| 合规 / 审计 |
| `*/v*.*.*` (release tag) | 永久 | 合规 |

具体在 SWR 控制台 → 仓库 → 触发器/生命周期 配置。

---

## 八、常见问题

**Q1：promotion 失败怎么办？**  
A：通常是凭据问题。Pipeline 会输出具体错误，看 Jenkins Console。重新触发即可（promoteImage 是幂等的）。

**Q2：promotion 慢吗？**  
A：很快。layer 复用，通常 5-30 秒（取决于网络），不消耗 CPU 编译。

**Q3：dev 和 test 必须同 project 吗？**  
A：技术上可以分。但分了之后 dev → test 也要 promotion，多此一举。**强烈建议合并**。

**Q4：手动指定 IMAGE_TAG 时 promotion 还跑吗？**  
A：跑。只要 `DEPLOY_ENV == prod` 且 `IMAGE_PROJECT != IMAGE_PROJECT_BUILD` 就会执行 promotion——目标 tag 在 source project 找得到就成功，找不到就报错。

**Q5：能跳过 test 直接 prod 部署吗？**  
A：可以。但前提是该 commit 在 test project 已经有镜像（之前 dev/test 部署过）。否则 promotion 会失败。

**Q6：镜像 layer 复制会被 SWR 计费吗？**  
A：华为云 SWR 不同 organization 间 push 会计入流量。但因为是 layer 复用，量很小。月度部署几百次，流量成本通常 < 10 元。

---

## 九、相关代码文件

| 文件 | 作用 |
|------|------|
| `baselines/_global.yaml` | `image.projects` 映射定义 |
| `shared-library/vars/k8sDeploy.groovy` | Pipeline 入口，解析 IMAGE_PROJECT_BUILD/IMAGE_PROJECT |
| `shared-library/vars/promoteImage.groovy` | Promotion 实现（pull + tag + push）|
| `shared-library/vars/pushImage.groovy` | 镜像构建推送（按 IMAGE_PROJECT 拼接镜像名）|
| `shared-library/vars/deployToK8s.groovy` | helm upgrade 时注入 image.name |

---

## 十、引用

- [12-Factor App](https://12factor.net/build-release-run)
- [Google Cloud Build: gcrane copy](https://github.com/google/go-containerregistry/blob/main/cmd/gcrane/README.md)
- [Red Hat skopeo](https://github.com/containers/skopeo)
- [Netflix Spinnaker Image Bake & Promote](https://spinnaker.io/docs/concepts/pipelines/)
