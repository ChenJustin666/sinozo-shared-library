# 容器化与 CI/CD 实战指南

> **当前标准（2026-08）**：新服务使用 [Kustomize 部署方案](./Kustomize.md)；Helm 只供未迁移旧服务使用，同一服务不能同时由两个引擎管理。

> 本文档面向所有开发运维人员，从零讲清楚**为什么我们要做这件事**，以及**整套流程如何跑通**。

---


| 标准 | 当前实现 | 状态 |
| --- | --- | --- |
| Build Once, Deploy Many | 完整 Git SHA 镜像只构建一次，prod 复用同一 tag | 已实现 |
| 配置分层 | Kustomize base + test/prod overlay | 已实现 |
| 权限隔离 | 业务仓库唯一 Jenkinsfile；prod 分支/overlay、Job、Shared Library 和 kubeconfig 受保护 | 已实现 |
| 密钥管理 | 默认 SealedSecret；已有中心密钥时 ExternalSecret | 已实现 |
| 部署前预览 | 渲染和密钥校验后执行 `kubectl diff` | 已实现 |
| 生产审批 | 白名单、固定分支、远端 commit 校验、人工审批 | 已实现 |
| 健康检查和回滚 | `kubectl rollout status/undo/restart` | 已实现 |

建议后续增加镜像 CVE 扫描、SBOM/签名验证、部署通知和 Kubernetes 准入策略。仓库与发布门禁见 [Git 与发布安全规范](./07-Git与发布安全规范.md)。


## 一、我们目前是怎么部署的

### 1.1 传统部署方式（当前现状）

```
开发写完代码 → 打 jar 包 → 传给运维 → SSH 登录服务器 → 上传 jar → 手动启动
java -Xms512m -Xmx1024m -jar app.jar &
```

每个服务上线都需要运维：
1. **申请服务器**（买 ECS、装系统、配网络）
2. **装环境**（JDK、Maven、Nginx、Node.js）
3. **写启动脚本**（nohup 后台跑，或者 systemd 服务）
4. **配置 Nacos / 数据库连接**（写死在 application.yml 里）
5. **加监控**（手动装 Prometheus agent / 日志采集 agent）
6. **配置开机自启**（写 systemd service 文件）
7. **配置故障重启**（自己写 bash 脚本检测进程是否存活）
8. **扩容**（买新机器、重新部署、配置负载均衡）

**一个服务上线，运维要花半天到一天。**

---

## 二、传统部署的痛点（为什么要折腾）

### 2.1 开发体验差

| 痛点 | 具体表现 |
|------|---------|
| 部署慢 | 每次发版：打 jar → 传服务器 → 重启，10 分钟起步 |
| 回滚难 | 上一个版本的 jar 被覆盖了，要重新找历史包 |
| 环境不一致 | "我本地跑得好好的" → test 和 prod 配置不同导致问题 |
| 配置散落 | Nacos 地址写死在 yml 里，每个环境单独改一份 |
| 分支混乱 | 开发直接 push 到 main，test 上跑的代码和 prod 不同 |

### 2.2 运维负担重

| 痛点 | 具体表现 |
|------|---------|
| 手动操作多 | 每台服务器都要 SSH 登录、上传、重启 |
| 监控靠人肉 | 服务挂了自己都不知道，要等用户反馈 |
| 扩容难 | 要加机器：买 ECS → 装环境 → 部署 → 配 LB，至少半天 |
| 故障自愈无 | 进程挂了只能手动拉起，凌晨 3 点也要爬起来 |
| 成本浪费 | 一台 4C8G 的 ECS 只跑一个服务，CPU 利用率不到 10% |
| 日志分散 | 每台机器看日志，排查问题要 SSH 多台机器 |

### 2.3 安全隐患

| 痛点 | 具体表现 |
|------|---------|
| 密码明文 | 数据库密码、AK/SK 写在 application.yml 里，进 Git |
| 权限混乱 | 开发能 SSH 到生产服务器 |
| 无审计 | 谁改了什么配置，什么时候改的，查不到 |

---

## 三、容器化后解决了什么

### 3.1 传统 VM vs K8s Pod 对比

| 对比项 | 传统 VM 部署 | K8s Pod 部署 |
|--------|------------|-------------|
| **部署速度** | 10-30 分钟（手动） | 1-2 分钟（自动） |
| **回滚** | 找历史 jar，手动替换 | `kubectl rollout undo` 或回退 Git 后重新部署 |
| **扩容** | 买 ECS → 装环境 → 部署（半天） | 改 replicas: 3，自动扩容（30秒） |
| **故障自愈** | 自己写脚本监控进程 | K8s 自动重启挂掉的 Pod（liveness probe） |
| **资源利用** | 一台 ECS 跑一个服务（浪费） | 一台 ECS 跑多个 Pod（利用率 60-80%） |
| **环境一致性** | "我本地能跑" → 上线就挂 | Docker 镜像 = 环境，到处一样 |
| **日志监控** | SSH 每台机器看日志 | 集中日志（Loki/EFK）+ Prometheus 自动采集 |
| **配置管理** | 写死在 yml，每个环境改一份 | Nacos 统一管理，环境变量注入 |
| **权限控制** | 开发能 SSH 到生产 | 开发只能触发 CI，运维管 prod 配置 |
| **成本** | 5 台 4C8G ECS（月 ~5000） | 5 台 8C16G ECS 跑 20 个服务（月 ~8000） |

### 3.2 具体场景举例

**场景：凌晨 2 点，某服务 OOM 挂了**

| | 传统部署 | K8s 部署 |
|--|---------|---------|
| 发现 | 第二天用户投诉 | liveness probe 30 秒内检测到 |
| 恢复 | 运维被叫起来手动重启 | K8s 自动重启 Pod |
| 定位 | SSH 机器看日志 | `kubectl logs` + HeapDump 自动保存 |

**场景：大促，需要临时扩容**

| | 传统部署 | K8s 部署 |
|--|---------|---------|
| 操作 | 买 3 台 ECS → 装环境 → 部署 → 配 LB | HPA 自动扩到 10 个副本 |
| 时间 | 半天 | 30 秒 |
| 缩容 | 手动删 ECS（可能忘了缩） | HPA 自动缩回 3 个副本 |

### 3.3 成本对比

假设当前有 10 个服务：

| | 传统 VM 部署 | K8s Pod 部署 |
|--|------------|-------------|
| 服务器数量 | 10 台 4C8G ECS | 3 台 8C16G ECS |
| 月成本（估算） | ~10000 元 | ~4800 元 |
| CPU 利用率 | 10-15% | 60-80% |
| 运维人力 | 1 人全职 | 0.3 人（自动化） |

> 容器化的核心收益不只是省钱，更是**把运维从重复劳动中解放出来**。

---

## 四、为什么需要配置中心（Nacos）

### 4.1 没有配置中心时

```yaml
# application.yml（写死在代码里）
spring:
  datasource:
    url: jdbc:mysql://192.168.1.100:3306/mydb
    password: prod_password_123    # ❌ 密码明文进 Git

nacos:
  server-addr: 192.168.1.50:8848   # ❌ 硬编码地址
```

**问题**：
- test/prod 使用同一镜像；test 分支构建并验证，合并到 `PROD_BRANCH` 后生产只复用对应 commit 镜像，不重新打包
- 密码明文提交到 Git，任何能 clone 仓库的人都能看到
- 想改配置？重新打 jar → 重新部署 → 服务重启

### 4.2 有配置中心后

```yaml
# application.yml（只写 Nacos 地址，其余从 Nacos 拉）
spring:
  cloud:
    nacos:
      config:
        server-addr: ${NACOS_SERVERS:localhost:8848}   # 环境变量注入
        namespace: ${NACOS_NAMESPACE:}
```

**好处**：
- 所有业务配置（DB 地址、Redis、第三方 Key）都在 Nacos 管理
- 改配置不用重启服务（Nacos 支持热更新）
- test 和 prod 用不同的 Nacos 命名空间，互不影响
- 密码不在 Git 里，Nacos 有自己的权限控制

---

## 五、为什么密码不能明文

### 5.1 Git 历史是永久的

```bash
git log -p application.yml
# 即使你后来删掉了密码，Git 历史里永远保留着那一次 commit
# 任何能 clone 仓库的人都能看到历史密码
```

### 5.2 正确做法

| 配置类型 | 存储位置 | 说明 |
|---------|---------|------|
| 非敏感（API 地址、开关） | Kustomize ConfigMap/patch 或 Nacos | 可以进 Git |
| 敏感（数据库密码、AK/SK） | SealedSecret 密文或 ExternalSecret 引用 | Git 不存明文/普通 Secret |
| Nacos 密码 | Secret → env 注入 | Secret 由 Sealed Secrets/External Secrets Controller 生成 |

---

## 六、分支管理与 Git 工作流

### 6.1 推荐分支策略

```
业务仓库分支结构：
├── main          # 生产分支，保护分支，只能通过 MR/PR 合并
├── test          # 测试集成分支，保护分支
├── feature/xxx   # 功能分支，先合并到 test
└── hotfix/xxx    # 紧急修复，从 main 拉出，修完合并回 main
```

### 6.2 日常开发流程

```bash
# 1. 从 test 拉功能分支
git checkout test
git pull origin test
git checkout -b feature/new-payment

# 2. 开发 + 提交
git add .
git commit -m "feat: 新增支付模块"
git push origin feature/new-payment

# 3. 提 MR/PR 合并到 test（代码 review）
# → GitLab/GitHub 界面操作

# 4. test 验证后提 PR 到 main；main 的精确 commit 还需由 test Job 验证一次
# → 生产只复用这个完整 commit SHA 对应的镜像
```

### 6.3 部署触发规则

| 动作 | 触发方式 | 部署到 |
|------|---------|--------|
| push/合并到测试分支 | Git Webhook 或手动触发 `*-test` Job | test 环境 |
| 手动 Build | `*-test` Job | 测试环境 |
| prod 部署 | 运维触发受保护的 `*-prod` Job | prod 环境 |

### 6.4 为什么不能直接 push 到 main？

- 代码 review：MR/PR 要求至少 1 人 approve
- 质量把控：CI 跑单元测试、代码扫描通过才能合并
- 可追溯：每次合并都有记录，出问题能找到是谁改的

---

## 七、镜像策略：为什么只构建一次

### 7.1 Build Once, Deploy Many

```
commit abc1234
    │
    ▼ test 部署时
构建镜像：sinozo/ad-gateway:R<40位Git SHA>
    │
    ├── 部署到 test（kubectl apply）
    │
    ▼ prod 部署时（不重新构建）
同一 sinozo organization 直接复用；仅跨 organization 时执行 Promotion
    │
    ▼
镜像：sinozo/ad-gateway:R<40位Git SHA>（同一不可变 tag）
    │
    ├── 部署到 prod（kubectl apply）
```

### 7.2 为什么不每个环境都构建一次？

| 问题 | 后果 |
|------|------|
| 两次构建产物不同 | "test 跑得好好的，上 prod 就挂了" |
| 编译环境差异 | test 用的 JDK 8.0.200，prod 用的 8.0.300 |
| 依赖版本漂移 | test 构建时依赖 A v1.0，prod 构建时变成 v1.1 |
| 浪费构建时间 | prod 部署多等 5 分钟构建 |

**不可变完整 SHA tag 保证：prod 跑的代码和 test 验证过的一模一样。**

---

## 八、完整部署流程（从代码到上线）

```
开发者提交代码
    │
    ▼
Git push → Webhook → Jenkins 触发
    │
    ▼
Jenkins Pipeline 执行：
├── 1. 校验 Job 环境、用户权限和生产分支
├── 2. 固定业务仓库 commit（prod 使用 PROD_BRANCH）
├── 3. 计算镜像 tag：R<40位Git SHA>
├── 4. 非生产按需构建；生产只复用已验证镜像
├── 5. 合并 Kustomize base 与目标 overlay
├── 6. 注入镜像、namespace 和追溯注解
├── 7. 对源文件和渲染 manifest 执行密钥校验
├── 8. prod 先 diff，再由审批人确认
├── 9. kubectl apply + rollout status
└── 10. 部署成功
    │
    ▼
K8s 自动管理：
├── 滚动更新（零停机）
├── 健康检查（自动重启挂掉的 Pod）
├── HPA 自动扩缩容
└── 日志监控自动采集
```

### 8.1 Kustomize 配置来源

```
① 业务仓库 deploy/kustomize/base                         （公共工作负载）
② 业务仓库 deploy/kustomize/overlays/test            （测试）
③ 业务仓库 deploy/kustomize/overlays/prod（生产，受保护分支）
④ CI 临时注入镜像、namespace 和 Git 追溯注解
```

test/prod 都读取业务 overlay；prod 只读取管理员指定的受保护 `PROD_BRANCH`，两者复用同一业务 base。

### 8.2 字段所有权（谁能改什么）

| 字段类别 | Owner | 位置 | 开发能改？ |
|---------|-------|------|----------|
| 镜像 tag / 镜像名 / namespace | CI 自动注入 | 不需要写 | ❌ |
| registry / pullSecret / 全局策略 | 运维 | `baselines/_global.yaml` | ❌ |
| test 配置（副本数、资源、JVM、探针） | 开发 | `deploy/kustomize/overlays/test` | ✅ |
| prod 配置 | 运维 review | `deploy/kustomize/overlays/prod` 的受保护 `PROD_BRANCH` | ❌ 无直接 push 权限 |

---

## 九、提效总结

| 指标 | 传统部署 | 容器化 + CI/CD | 提升 |
|------|---------|---------------|------|
| 单次部署时间 | 10-30 分钟 | 1-2 分钟 | **10x** |
| 回滚时间 | 10 分钟（找 jar + 重启） | 约 30 秒（rollout undo） | **20x** |
| 扩容时间 | 半天（买机器 + 部署） | 30 秒（改 replicas） | **100x** |
| 故障恢复时间 | 人工介入（分钟级） | 自动重启（秒级） | **60x** |
| 运维人力（10 个服务） | 1 人全职 | 0.3 人 | **3x** |
| 服务器成本（10 个服务） | ~10000 元/月 | ~4800 元/月 | **节省 50%** |

---

## 十、后续文档导航

| 角色 | 文档 |
|------|------|
| 开发人员 | [开发接入指南](./02-开发接入指南.md) |
| 运维人员 | [运维操作指南](./06-运维操作指南.md) |
| 所有人 | [变量外置规范](./05-变量外置规范.md) |
