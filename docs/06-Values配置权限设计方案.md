# Values 配置权限设计方案

> **背景**：20+ 开发、1000+ Pod 规模下，"开发自由度"和"运维管控"如何平衡？  
> **核心问题**：Values 配置归谁管？运维全包效率低，开发全管不安全。  
> **解决方案**：分层 Values + 字段权限矩阵 + Schema 校验

---

## 一、现状诊断

### 1.1 当前架构

```
k8s-deploy/                                     # 运维仓库
└── projects/adv/test/ad-gateway/values-test.yaml   # 单一文件，所有配置混在一起
```

单个 values 文件里同时包含：
- 业务相关：`java.opts`、`env`、`replicas`
- 基线敏感：`resources.limits`、`namespace`、`image.registry`、`hpa.maxReplicas`
- 安全相关：`securityContext`、`nodeSelector`、`tolerations`

### 1.2 关键问题

#### 问题 1：权限控制只在"触发部署"层面，不在"配置内容"层面

代码证据（`shared-library/vars/checkPermission.groovy`）：

```groovy
def call(String deployEnv) {
    // 只检查"谁能点 Build 按钮"
    if (!devopsUsers.contains(currentUser)) {
        error("❌ 用户 '${currentUser}' 无权操作生产环境")
    }
}
```

但是，**values 文件被改了什么、由谁改的，Pipeline 完全感知不到**。  
即开发可以提 MR 改 prod values，把 `replicas: 100` 偷偷加进去，
只要 MR 被合并，运维点了 Build，就会按这个配置部署。

#### 问题 2：所有人改的是同一个文件，没有字段边界

```yaml
# values-test.yaml（开发想加一个环境变量）
env:
  - name: LOG_LEVEL
    value: debug
# 但同时这个文件里还有：
resources:
  limits:
    cpu: "1"          # 开发可能误改成 8
    memory: 2Gi       # 开发可能误改成 16Gi
```

#### 问题 3：运维想统一调整所有服务的某个参数，要改 N 个文件

例如：所有服务统一要加 `securityContext.runAsNonRoot: true`，
现在要改 50 个 values 文件。

---

## 二、五种方案对比

### 方案 A：现状 - 运维全包

```
所有 values 在 k8s-deploy 仓库 → 开发提 MR → 运维 review/merge → 部署
```

| 维度 | 评价 |
|------|------|
| 开发自由度 | ❌ 低，改个环境变量也要走 MR |
| 运维管控 | ✅ 强 |
| 实施成本 | ✅ 0（现状） |
| 一致性 | ⚠️ 中等，依赖 review |
| 适用规模 | <10 服务 |

### 方案 B：开发全管 values

```
values 放在业务仓库 → 开发自己改 → Jenkins 部署
```

| 维度 | 评价 |
|------|------|
| 开发自由度 | ✅ 高 |
| 运维管控 | ❌ 弱，无法统一规范 |
| 实施成本 | ✅ 低 |
| 一致性 | ❌ 差，每个服务配置风格不同 |
| 适用规模 | <5 服务，初创团队 |

### 方案 C：单 values 多环境 + envsubst

```yaml
# values.yaml（一份，靠环境变量替换）
service:
  namespace: ${PROJECT}-${ENV}
  replicas: ${REPLICAS:-1}
```

| 维度 | 评价 |
|------|------|
| 开发自由度 | ⚠️ 中 |
| 运维管控 | ⚠️ 中 |
| 实施成本 | ⚠️ 中（要改 Pipeline） |
| 可读性 | ❌ 差，模板字符串混乱 |
| Helm 兼容性 | ❌ 不推荐，破坏 Helm values 语义 |

### 方案 D：分层 values（推荐 ⭐）

```
charts/generic-service/values.yaml              # Chart 默认值
       ↓
projects/{project}/{env}/{svc}/values-{env}.yaml   # 开发可改：业务层
       ↓
baselines/{project}/{env}-baseline.yaml         # 运维基线：强制覆盖
```

| 维度 | 评价 |
|------|------|
| 开发自由度 | ✅ 高（业务字段开发可改） |
| 运维管控 | ✅ 强（基线强制覆盖） |
| 实施成本 | ⚠️ 中（改 Pipeline + 拆配置） |
| 一致性 | ✅ 强 |
| 适用规模 | **20+ 服务推荐** |

### 方案 E：Schema 校验 + 字段白名单（高级）

在方案 D 的基础上，加 JSON Schema：
- `charts/generic-service/values.schema.json`：定义字段类型、范围、必填
- Pipeline 在部署前用 `helm lint --strict` 校验
- 开发的 values 如果包含禁止字段（如 `nodeSelector`），直接报错

| 维度 | 评价 |
|------|------|
| 安全性 | ✅✅ 极强 |
| 实施成本 | ❌ 高 |
| 适用规模 | 50+ 服务、合规要求高 |

---

## 三、推荐方案：方案 D（分层 Values）

### 3.1 仓库结构

```
k8s-deploy/
├── charts/
│   └── generic-service/
│       ├── values.yaml                # 第 1 层：Chart 默认值
│       └── values.schema.json         # （可选）字段校验
│
├── baselines/                         # 运维管控区
│   ├── _common.yaml                   # 全局基线（所有服务都加）
│   ├── adv/
│   │   ├── test-baseline.yaml         # adv-test 环境基线
│   │   └── prod-baseline.yaml         # adv-prod 环境基线（最严格）
│   └── fcm/
│       ├── test-baseline.yaml
│       └── prod-baseline.yaml
│
└── projects/                          # 开发协作区（提 MR）
    └── adv/
        ├── test/ad-gateway/values-test.yaml    # 开发可改
        └── prod/ad-gateway/values-prod.yaml    # prod 也可提 MR，但基线兜底
```

### 3.2 三层覆盖顺序（Helm 的 `-f` 后置优先）

```bash
helm upgrade --install ad-gateway ./charts/generic-service \
  -f baselines/_common.yaml \                              # 1. 全局基线
  -f projects/adv/prod/ad-gateway/values-prod.yaml \       # 2. 开发业务配置
  -f baselines/adv/prod-baseline.yaml \                    # 3. 环境基线（最后=最高优先级）
  -n adv-prod
```

**关键点**：环境基线放在**最后**，会覆盖开发 values 中的同名字段。  
开发即使在 values-prod.yaml 里写 `resources.limits.cpu: 8`，也会被基线的 `resources.limits.cpu: 2` 覆盖。

