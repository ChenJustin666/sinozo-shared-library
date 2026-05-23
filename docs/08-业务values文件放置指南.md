# 业务 values 放置指南（含模板和职责清单）

业务方在自己的代码仓库根目录建 `deploy/`，放 3 份 values 文件。
本文档给出**直接可用的模板** + **谁改什么**职责清单。

---

## 📌 谁改什么 - 一张表说清楚

| 字段类别 | Owner | 写在哪个文件 | 仓库 |
|---------|------|------------|------|
| 镜像基础设施（registry、organization、pullSecret） | **运维** | `baselines/_global.yaml` | sinozo-shared-library |
| 容器安全（securityContext） | **运维** | `baselines/_global.yaml` | sinozo-shared-library |
| 部署策略（strategy 滚动更新） | **运维** | `baselines/_global.yaml` | sinozo-shared-library |
| 资源 limits 兜底 | **运维** | `charts/generic-service/values.yaml` | sinozo-shared-library |
| 节点调度（nodeSelector、tolerations、affinity） | **运维** | 按需，向运维提需求 | sinozo-shared-library |
| `image.name` / `image.tag` / `namespace` | **CI 自动注入** | 不要写在任何 yaml 里 | - |
| 服务端口、Java JVM、健康探针、Prometheus 监控 | **开发** | `deploy/values.yaml` | 业务仓库 |
| 测试副本数 / 资源 requests / HPA / JVM / env / Ingress | **开发** | `deploy/values-test.yaml` | 业务仓库 |
| 生产副本数 / 资源 requests / HPA / JVM / env / Ingress | **开发** | `deploy/values-prod.yaml` | 业务仓库 |

**规则**：
- 测试环境**宽松** - 开发自由调
- 生产环境**严格控制** - 通过 4 道防线保证安全：
  1. `automation/values-validate.sh` - 业务 yaml 写运维字段（如 `securityContext`、`resources.limits`、`nodeSelector`）→ CI 直接 fail
  2. **GitHub PR review** - 必须 1 名 reviewer 批准才能合 main
  3. **Jenkins prod 审批** - 触发部署需要审批人点确认
  4. **Helm template 预览** - prod 部署前显示渲染配置，审批人扫一眼
- 业务方对 prod 只允许提 PR，**直接 push main 被 GitHub 阻止**


---

## 📂 业务仓库目录结构

### 类型 A：单服务仓库（一个 git repo 一个服务）

```
ad-gateway/                       ← git repo
├── src/...
├── pom.xml
├── Dockerfile                    ← 业务管
├── Jenkinsfile                   ← 业务管
└── deploy/                       ← 业务管，3 份 values
    ├── values.yaml
    ├── values-test.yaml
    └── values-prod.yaml
```

### 类型 B：Monorepo（一个 git repo 多个服务）

```
agent-monorepo/                   ← git repo
├── pom.xml                       ← 父 pom
├── pod-agent-api/                ← 子模块 1
│   ├── src/...
│   ├── Dockerfile
│   ├── Jenkinsfile               ← Jenkinsfile 加 subdirectory: 'pod-agent-api'
│   └── deploy/
│       ├── values.yaml
│       ├── values-test.yaml
│       └── values-prod.yaml
└── pod-agent-manager/            ← 子模块 2
    ├── src/...
    ├── Dockerfile
    ├── Jenkinsfile               ← subdirectory: 'pod-agent-manager'
    └── deploy/
        ├── values.yaml
        ├── values-test.yaml
        └── values-prod.yaml
```

每个子模块**自己一份 Jenkinsfile**，加 `subdirectory: '<子模块名>'`。
Jenkins 每个子模块**一个 Job**，Script Path 指向 `<子模块>/Jenkinsfile`。

---

## ☕ Java 服务模板

模板文件在仓库 `charts/generic-service/examples/business/java/`，直接复制即可。

### `deploy/values.yaml` - 业务通用

```yaml
# ============================================================
# Java 业务通用配置 (deploy/values.yaml)
# Owner: 开发
# 范围:  所有环境共享（test 和 prod 都会读这份）
# ============================================================
#
# 🔵 CI 自动注入字段（不要在任何 yaml 里写）：
#    image.name / image.tag / image.registry / image.pullSecret / namespace
#
# 🔴 运维管字段（写了 CI 拒绝，请去运维仓库 baselines/ 让运维改）：
#    resources.limits / securityContext / strategy / nodeSelector / pdb
#
# 🟢 开发可改字段：
#    service.port / service.replicas
#    java.enabled / java.opts
#    env / config / probes / monitoring
#    hpa.minReplicas / hpa.maxReplicas / hpa.cpuTarget
#    resources.requests / ingress / configmap
# ============================================================

# ── 服务端口 ──
service:
  port: 8080

# ── Java 默认 JVM（环境特化在 values-{env}.yaml 里覆写）──
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

# ── Prometheus 监控 ──
monitoring:
  enabled: true
  path: /actuator/prometheus
  port: 8080
```

### `deploy/values-test.yaml` - 测试环境（宽松）

```yaml
# ============================================================
# Java 测试环境配置 (deploy/values-test.yaml)
# Owner: 开发
#
# 测试环境宽松，开发可自由调：副本数 / 资源 / HPA / JVM / env / ingress
# 仅写与 values.yaml 不同的字段
# ============================================================

# ── 副本数 ──
service:
  replicas: 1

# ── 资源 requests（test 节省）──
resources:
  enabled: true
  requests:
    cpu: 100m
    memory: 256Mi

# ── Java JVM 测试环境覆写（连测试 Nacos）──
java:
  opts: >-
    -Dspring.application.name=ad-gateway
    -Dspring.profiles.active=test
    -Dspring.cloud.nacos.config.server-addr=nacos-test.internal:8848
    -Dspring.cloud.nacos.config.namespace=ad-gateway-test
    -Dspring.cloud.nacos.discovery.server-addr=nacos-test.internal:8848
    -Dspring.cloud.nacos.discovery.namespace=ad-gateway-test
    -Xms256m -Xmx512m

# ── 业务环境变量（test 开 debug）──
env:
  - name: LOG_LEVEL
    value: debug
  - name: SPRING_PROFILES_ACTIVE
    value: test

# ── HPA（test 默认不开，按需打开）──
# hpa:
#   enabled: true
#   minReplicas: 1
#   maxReplicas: 3
#   cpuTarget: 70
```

### `deploy/values-prod.yaml` - 生产环境（开发写，PR 必须 review）

```yaml
# ============================================================
# Java 生产环境配置 (deploy/values-prod.yaml)
# Owner: 开发，PR 必须 1 名 reviewer 批准才能合 main
#
# ⚠️ 生产部署多重保险：
#    1. values-validate.sh 拦截运维字段（限定开发只能写以下字段）
#    2. GitHub PR review 必填（直接 push main 被 branch protection 拒绝）
#    3. Jenkins prod 部署需要审批
#    4. 部署前 helm template 显示渲染配置供 review
#
# ✅ 开发可写：
#    service.replicas / service.port
#    java.opts / env / probes / monitoring / config
#    hpa.* / resources.requests
#    ingress.host / ingress.path / ingress.tls
#
# ❌ 写了 CI 直接 fail：
#    resources.limits / securityContext / strategy
#    nodeSelector / tolerations / affinity / pdb
# ============================================================

# ── 副本数（按服务规模定，建议 ≥ 2）──
service:
  replicas: 3

# ── 资源 requests（基于实际压测数据填写）──
resources:
  enabled: true
  requests:
    cpu: 500m
    memory: 1Gi

# ── Java JVM 生产环境覆写（连生产 Nacos）──
java:
  opts: >-
    -Dspring.application.name=ad-gateway
    -Dspring.profiles.active=prod
    -Dspring.cloud.nacos.config.server-addr=nacos-prod.internal:8848
    -Dspring.cloud.nacos.config.namespace=ad-gateway-prod
    -Dspring.cloud.nacos.discovery.server-addr=nacos-prod.internal:8848
    -Dspring.cloud.nacos.discovery.namespace=ad-gateway-prod
    -Xms2g -Xmx4g
    -XX:+UseG1GC

# ── 业务环境变量（prod info 日志，避免日志风暴）──
env:
  - name: LOG_LEVEL
    value: info
  - name: SPRING_PROFILES_ACTIVE
    value: prod

# ── HPA（按需打开，建议运行稳定 1-2 周后再开）──
# hpa:
#   enabled: true
#   minReplicas: 3
#   maxReplicas: 10
#   cpuTarget: 70
```


---

## 🌐 Node.js / 前端服务模板

模板文件在仓库 `charts/generic-service/examples/business/nodejs/`。

### `deploy/values.yaml` - 业务通用

```yaml
# ============================================================
# Node.js / 前端业务通用配置 (deploy/values.yaml)
# Owner: 开发
# 范围:  所有环境共享
# ============================================================
#
# 🔵 CI 自动注入字段（不要在任何 yaml 里写）：
#    image.name / image.tag / image.registry / image.pullSecret / namespace
#
# 🔴 运维管字段（写了 CI 拒绝）：
#    resources.limits / securityContext / strategy / nodeSelector / pdb
#
# 🟢 开发可改字段：
#    service.port / service.replicas
#    env / config / probes / monitoring
#    hpa / resources.requests / ingress / configmap
# ============================================================

# ── 服务端口（前端 Nginx 一般 80，后端 Node API 一般 3000）──
service:
  port: 80

# ── 健康探针（前端 Nginx 可不开，后端 API 建议开）──
# probes:
#   enabled: true
#   type: http
#   path: /health
#   port: 3000
```

### `deploy/values-test.yaml` - 测试环境

```yaml
# ============================================================
# Node.js / 前端测试环境配置 (deploy/values-test.yaml)
# Owner: 开发
#
# 测试环境宽松，开发可自由调：副本数 / 资源 / env / ingress
# 仅写与 values.yaml 不同的字段
# ============================================================

# ── 副本数 ──
service:
  replicas: 1

# ── 资源 requests（前端 Nginx 很省）──
resources:
  enabled: true
  requests:
    cpu: 50m
    memory: 64Mi

# ── 业务环境变量（test 连测试 API）──
env:
  - name: NODE_ENV
    value: test
  - name: API_BASE_URL
    value: https://api-test.internal    # ⚠️ 改成你的测试 API 地址
  - name: LOG_LEVEL
    value: debug

# ── Ingress（前端通常需要，按需打开）──
# ingress:
#   enabled: true
#   host: my-app-test.example.com
#   path: /
#   tls: false
```

### `deploy/values-prod.yaml` - 生产环境（开发写，PR 必须 review）

```yaml
# ============================================================
# Node.js / 前端生产环境配置 (deploy/values-prod.yaml)
# Owner: 开发，PR 必须 1 名 reviewer 批准才能合 main
#
# ⚠️ 生产部署多重保险（同 Java 服务）
#
# ✅ 开发可写：
#    service.replicas / service.port
#    env / probes / monitoring / config
#    hpa.* / resources.requests
#    ingress.host / ingress.path / ingress.tls
#
# ❌ 写了 CI 直接 fail：
#    resources.limits / securityContext / strategy
#    nodeSelector / tolerations / affinity / pdb
# ============================================================

# ── 副本数（按服务规模定，建议 ≥ 2）──
service:
  replicas: 2

# ── 资源 requests（基于实际压测填写）──
resources:
  enabled: true
  requests:
    cpu: 100m
    memory: 128Mi

# ── 业务环境变量（prod 连生产 API）──
env:
  - name: NODE_ENV
    value: production
  - name: API_BASE_URL
    value: https://api.example.com      # ⚠️ 改成你的生产 API 地址
  - name: LOG_LEVEL
    value: info

# ── Ingress（前端必开）──
# ingress:
#   enabled: true
#   host: my-app.example.com
#   path: /
#   tls: true

# ── HPA（按需，建议运行稳定 1-2 周后再开）──
# hpa:
#   enabled: true
#   minReplicas: 2
#   maxReplicas: 6
#   cpuTarget: 70
```

---

## 🛠 运维管什么（运维仓库）

### `baselines/_global.yaml` - 全局基线

所有服务必加载，运维季度 review。负责：
- 镜像 registry / pullSecret / project 映射（dev/test/prod）
- 容器安全（runAsUser / fsGroup）
- 部署策略（滚动更新、零停机）

### 服务级特殊配置（极少用）

仅当某服务有非常特殊的需求（StatefulSet、节点亲和、需要 PDB）时，可由运维在 `baselines/<project>/<service>/baseline-<env>.yaml` 单独写。**90% 的服务用不到**，开发不要碰这块。

---

## 🚀 业务方接入步骤


```bash
# 1. 在业务仓库根目录建 deploy/
cd <业务仓库>
mkdir -p deploy

# 2. 从 sinozo-shared-library 复制对应类型的模板
# Java:
cp /path/to/sinozo-shared-library/charts/generic-service/examples/business/java/values*.yaml deploy/
# Node.js:
cp /path/to/sinozo-shared-library/charts/generic-service/examples/business/nodejs/values*.yaml deploy/

# 3. 改 values.yaml 里的 application.name 为你的服务名
sed -i 's/ad-gateway/<your-service>/g' deploy/values*.yaml

# 4. 提交
git add deploy/
git commit -m "feat: 添加 K8s 部署 values"
git push

# 5. Jenkins 触发部署
#    Job: <service>-test → Build with Parameters → ACTION=deploy
```

---

## 📝 加载顺序（理解优先级）

部署时 Helm 按顺序加载，**后面的覆盖前面的**：

```
1. baselines/_global.yaml                                  ← 运维全局基线
2. baselines/<project>/<service>/baseline-<env>.yaml       ← 运维服务级基线（按需）
3. <业务仓库>/deploy/values.yaml                            ← 业务通用
4. <业务仓库>/deploy/values-<env>.yaml                      ← 业务环境差异
5. helm --set image.tag=R<commit> --set image.name=...     ← CI 注入（最高优先级）
```

例：业务 `values-prod.yaml` 写了 `service.replicas: 5` 想覆盖运维的 3 副本，**会被允许**（顺序 4 > 顺序 2）。但 CI 校验会**拒绝**这种写法（生产副本数属于运维字段）。

---

## ❓ 常见问题

### Q1：业务 yaml 里写 `image: name: xxx` 行不行？

**不行**。CI 在 `deployToK8s.groovy` 用 `--set image.name=...` 强制注入。即使你写了，也会被覆盖。删掉，避免歧义。

### Q2：生产想加副本数，能自己改 `values-prod.yaml` 吗？

**不能**（CI 会拒绝）。找运维改 `baselines/<svc>/baseline-prod.yaml`。这是字段所有权契约的核心。

### Q3：开发自己改测试环境的 HPA 用不用找运维？

**不用**。测试环境业务 `values-test.yaml` 写 `hpa: enabled: true ...` 就行，CI 通过。

### Q4：业务通用 `values.yaml` 里写了 Java JVM，环境特化的 `values-test.yaml` 也写 JVM，最终用哪个？

**用 values-test.yaml 的**（顺序 4 覆盖顺序 3）。习惯用法：通用文件写默认，环境文件写特化覆盖。

### Q5：紧急回滚怎么办？

```
Jenkins → <service>-prod → Build with Parameters
  ACTION: rollback
  ROLLBACK_REVISION: 0    # 0=上一个版本
```

### Q6：debug 渲染结果怎么看？

```bash
# 在 k8s-node 上手跑
helm template my-release \
    /var/lib/jenkins/.../charts/generic-service \
    -f baselines/_global.yaml \
    -f baselines/adv/ad-gateway/baseline-prod.yaml \
    -f <业务仓库>/deploy/values.yaml \
    -f <业务仓库>/deploy/values-prod.yaml \
    --set image.tag=R12345 \
    --set image.name=sinozo-prod/ad-gateway
```
