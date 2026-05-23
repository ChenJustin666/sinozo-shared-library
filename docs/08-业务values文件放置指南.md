# 业务 values 文件放置指南

业务方在自己的代码仓库里放 `Dockerfile` / `Jenkinsfile` / `deploy/values*.yaml`。
本文档说明 **2 种仓库结构** 各自怎么放。

---

## 总原则

```
业务仓库根目录或子模块根目录下，建 deploy/ 目录：
├── deploy/
│   ├── values.yaml          # 业务通用配置（所有环境共享）
│   ├── values-test.yaml     # test 环境差异
│   └── values-prod.yaml     # prod 环境差异
```

**自动检测规则**（k8sDeploy 内部）：
- 业务仓库有 `deploy/values-{env}.yaml` → 用新模式 ⭐ 推荐
- 没有 → fallback 旧模式（运维仓库 `projects/` 路径）

---

## 类型 A：单服务仓库（一个 git repo 一个服务）

### 目录结构

```
ad-gateway/                              ← git repo: ad-gateway.git
├── src/main/java/...
├── pom.xml
├── Dockerfile                           ← 业务管
├── Jenkinsfile                          ← 业务管
└── deploy/                              ← ⭐ values 放这里
    ├── values.yaml                      ← 业务通用
    ├── values-test.yaml                 ← test 差异
    └── values-prod.yaml                 ← prod 差异
```

### Jenkinsfile 写法

```groovy
@Library('k8s-deploy-lib@main') _

k8sDeploy(
    projectName:  'adv',
    serviceName:  'ad-gateway',
    serviceType:  'java',
    dockerImage:  'ad-gateway',
    dockerCredId: 'docker-swr-cred',

    jdkTool:    'jdk 1.8',
    mavenTool:  'Maven 3.8.8',

    kubeconfigCredId: [
        test: 'test-k8s-aliyun-am',
        prod: 'prod-k8s-aliyun-am',
    ],
    // 不写 subdirectory → 默认 WORKSPACE 根目录找 deploy/
)
```

### Jenkins Job 配置

```
Pipeline definition: Pipeline script from SCM
SCM:                Git
Repository URL:     http://gitea/server/ad-gateway.git
Credentials:        git-server-cred
Branch Specifier:   */master  或 */main
Script Path:        Jenkinsfile          ← 默认根目录
```

---

## 类型 B：Monorepo（一个 git repo 多个服务）

### 目录结构

```
agent-monorepo/                          ← git repo: agent-monorepo.git
├── pom.xml                              ← 父 pom（如果是 maven 多模块）
├── pod-agent-api/                       ← 子模块 1
│   ├── src/main/java/...
│   ├── pom.xml
│   ├── Dockerfile                       ← ⭐ 每个子模块独立
│   ├── Jenkinsfile                      ← ⭐ 每个子模块独立
│   └── deploy/                          ← ⭐ 每个子模块独立
│       ├── values.yaml
│       ├── values-test.yaml
│       └── values-prod.yaml
└── pod-agent-manager/                   ← 子模块 2
    ├── src/...
    ├── pom.xml
    ├── Dockerfile
    ├── Jenkinsfile
    └── deploy/
        ├── values.yaml
        ├── values-test.yaml
        └── values-prod.yaml
```

### Jenkinsfile 写法（每个子模块一份）

#### `pod-agent-api/Jenkinsfile`

```groovy
@Library('k8s-deploy-lib@main') _

k8sDeploy(
    projectName:  'agent',
    serviceName:  'pod-agent-api',
    serviceType:  'java',
    dockerImage:  'pod-agent-api',
    dockerCredId: 'docker-swr-cred',

    subdirectory: 'pod-agent-api',       // ⭐ Monorepo 关键参数

    jdkTool:    'jdk 1.8',
    mavenTool:  'Maven 3.8.8',

    kubeconfigCredId: [
        test: 'test-k8s-aliyun-am',
        prod: 'prod-k8s-aliyun-am',
    ],
)
```

#### `pod-agent-manager/Jenkinsfile`

```groovy
@Library('k8s-deploy-lib@main') _

k8sDeploy(
    projectName:  'agent',
    serviceName:  'pod-agent-manager',
    serviceType:  'java',
    dockerImage:  'pod-agent-manager',
    dockerCredId: 'docker-swr-cred',

    subdirectory: 'pod-agent-manager',   // ⭐ 指向自己的子目录

    jdkTool:    'jdk 1.8',
    mavenTool:  'Maven 3.8.8',

    kubeconfigCredId: [
        test: 'test-k8s-aliyun-am',
        prod: 'prod-k8s-aliyun-am',
    ],
)
```

`subdirectory` 起的作用：
- maven 构建：自动 `cd pod-agent-api && mvn clean package`
- values 查找：`${WORKSPACE}/pod-agent-api/deploy/values-{env}.yaml`
- Dockerfile：自动用 `pod-agent-api/Dockerfile`

### Jenkins Job 配置（每个子模块一个 Job）

#### Job: `pod-agent-api-test`

```
Pipeline definition: Pipeline script from SCM
SCM:                Git
Repository URL:     http://gitea/server/agent-monorepo.git    ← 同一个仓库
Credentials:        git-server-cred
Branch Specifier:   */master
Script Path:        pod-agent-api/Jenkinsfile                  ← ⭐ 子目录路径
```

#### Job: `pod-agent-manager-test`

```
Repository URL:     http://gitea/server/agent-monorepo.git    ← 同一个仓库
Script Path:        pod-agent-manager/Jenkinsfile              ← ⭐ 不同子目录
```

---

## values 文件内容怎么写？

### `deploy/values.yaml`（业务通用）

只写所有环境共享的字段。

```yaml
# ── 服务基本信息 ──
service:
  port: 8080

# ── 镜像 ──（image.tag 由 CI 自动注入，不要写）
image:
  name: sinozo-test/ad-gateway        # 注意 tag 别在这写

# ── Java 配置 ──
java:
  enabled: true
  opts: >-
    -Dspring.application.name=ad-gateway
    -Xms512m -Xmx1024m

# ── 健康探针 ──
probes:
  enabled: true
  type: http
  path: /actuator/health
  port: 8080

# ── 监控 ──
monitoring:
  enabled: true
  path: /actuator/prometheus
  port: 8080
```

### `deploy/values-test.yaml`（test 环境差异）

只写跟 `values.yaml` 不同的字段。

```yaml
# ── test 副本数（最小化）──
service:
  replicas: 1

# ── test 资源（节省）──
resources:
  enabled: true
  requests:
    cpu: 100m
    memory: 256Mi

# ── test 环境的 JVM 参数（连测试 Nacos）──
java:
  opts: >-
    -Dspring.application.name=ad-gateway
    -Dspring.profiles.active=test
    -Dspring.cloud.nacos.config.server-addr=nacos-test.internal:8848
    -Xms256m -Xmx512m

# ── test 环境变量 ──
env:
  - name: LOG_LEVEL
    value: debug
```

### `deploy/values-prod.yaml`（prod 环境差异）

```yaml
# ── prod 副本数（高可用）──
service:
  replicas: 3

# ── prod 资源 ──
resources:
  enabled: true
  requests:
    cpu: 500m
    memory: 1Gi

# ── prod HPA（强烈建议开启）──
hpa:
  enabled: true
  minReplicas: 3
  maxReplicas: 10
  cpuTarget: 70

# ── prod JVM ──
java:
  opts: >-
    -Dspring.application.name=ad-gateway
    -Dspring.profiles.active=prod
    -Dspring.cloud.nacos.config.server-addr=nacos-prod.internal:8848
    -Xms2g -Xmx4g
    -XX:+UseG1GC

# ── prod 环境变量 ──
env:
  - name: LOG_LEVEL
    value: info
```

---

## 字段所有权（重要）

业务 values 文件 **只能写业务字段**，运维字段会被 CI 拒绝。

### ✅ 业务可写字段

| 字段 | 说明 |
|------|------|
| `service.replicas` | 副本数 |
| `service.port` | 服务端口 |
| `image.name` | 镜像名（不含 tag） |
| `java.enabled` `java.opts` | Java 配置 |
| `env` | 环境变量列表 |
| `config` | 业务配置（渲染为 ConfigMap） |
| `probes` | 健康探针 |
| `monitoring` | Prometheus 配置 |
| `hpa.minReplicas` `hpa.maxReplicas` `hpa.cpuTarget` | HPA |
| `resources.requests` | 资源请求量 |
| `ingress` | Ingress 配置 |
| `configmap` | ConfigMap 内容 |

### ❌ 业务禁写字段（运维管）

| 字段 | 谁管 |
|------|------|
| `namespace` `service.namespace` | 运维（按 `<project>-<env>` 自动决定） |
| `image.registry` `image.pullSecret` | 运维（在 baselines/_global.yaml 配） |
| `resources.limits` | 运维（防止业务申请过量） |
| `securityContext` | 运维（安全合规） |
| `nodeSelector` `tolerations` `affinity` | 运维（节点调度） |
| `pdb` | 运维（高可用策略） |
| `strategy` | 运维（滚动更新策略） |

写了运维字段 CI 会失败，去 `docs/07-字段所有权标准.md` 看完整契约。

---

## 现有服务迁移步骤（旧模式 → 新模式）

如果你的服务当前用旧模式（values 在 `sinozo-shared-library/projects/`），按这 4 步迁移：

### Step 1：在业务仓库建 deploy/

```bash
cd <业务仓库>
mkdir -p deploy
```

### Step 2：拆分旧 values

旧的 `projects/<proj>/<env>/<svc>/values-<env>.yaml` 把业务 + 运维字段混在一起。
拆成 2 份：
- 业务字段 → `deploy/values.yaml` + `deploy/values-{env}.yaml`
- 运维字段 → 删除（已在 `baselines/_global.yaml` 或运维 baseline 里配）

### Step 3：提交业务仓库

```bash
git add deploy/
git commit -m "feat: values 迁移到业务仓库 deploy/"
git push
```

### Step 4：清理运维仓库旧 values

```bash
cd /tmp/sinozo-shared-library
rm projects/<proj>/<env>/<svc>/values-<env>.yaml
git commit -am "chore: 移除 <svc> 旧 values（已迁到业务仓库）"
git push
```

---

## 常见问题

### Q1：能用 init-service.sh 一键生成 deploy/ 吗？

可以。脚本已经会生成 `Dockerfile + Jenkinsfile + deploy/values*.yaml`：

```bash
cd /tmp/sinozo-shared-library
./automation/init-service.sh adv ad-gateway java
# 输出在 /tmp/ad-gateway-init-XXXXX/

# 复制到业务仓库
cp -r /tmp/ad-gateway-init-XXXXX/{Dockerfile,Jenkinsfile,deploy} <业务仓库>/
```

### Q2：Monorepo 子模块共享 values 怎么办？

不要共享。每个子模块自己一份 deploy/，Helm release 是按 service 维度的。
共享配置可以提取到 `baselines/_global.yaml`（运维管）。

### Q3：如何调试 values 渲染结果？

```bash
# 在 k8s-node 上手动跑一次
helm template my-release \
    /var/lib/jenkins/.../charts/generic-service \
    -f baselines/_global.yaml \
    -f <业务仓库>/deploy/values.yaml \
    -f <业务仓库>/deploy/values-test.yaml \
    --set image.tag=R12345
```

### Q4：业务 values 改了不重新构建直接部署？

可以。Jenkins Job 触发 deploy 时勾上 "跳过构建" 或不传 IMAGE_TAG，
代码会检测到当前 commit 已构建过镜像，跳过构建直接部署。

### Q5：紧急回滚怎么办？

```
Jenkins → <service>-<env> Job → Build with Parameters
  ACTION: rollback
  ROLLBACK_REVISION: 0   ← 0=上一个版本，或填具体 revision 号
```
