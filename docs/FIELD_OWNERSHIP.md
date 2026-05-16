# 字段所有权契约（Field Ownership Contract）

> v1.0 · 平台组 owner · 季度 review

## 一、唯一规则

**每个 Helm values 字段有且只有一个 owner。开发的 values 写了运维字段，CI 直接拒绝。**

## 二、字段归属

### 🟢 开发字段（业务仓库 `deploy/values*.yaml`）

```yaml
# 业务行为
env:                          # 环境变量
config:                       # 业务配置
java.opts:                    # JVM 参数
java.enabled:                 # 是否注入 JAVA_OPTS

# 业务规模
service.replicas:             # 副本数（基础值）
service.port:                 # 容器端口
hpa.enabled:                  # 是否启用 HPA
hpa.minReplicas:              # HPA 最小副本
hpa.maxReplicas:              # HPA 最大副本（不能超 baseline 上限）
hpa.cpuTarget:                # CPU 目标值
resources.requests:           # 资源请求量

# 业务镜像
image.name:                   # 镜像名（不带 registry）

# 业务可观测
probes:                       # 健康探针
monitoring:                   # Prometheus 注解

# 业务网关
ingress:                      # Ingress 规则

# 业务配置文件
configmap:                    # ConfigMap 数据
```

### 🔴 运维字段（k8s-deploy `baselines/*.yaml`）

业务仓库写了这些字段 → CI 拒绝部署。

```yaml
# 环境路由
project:                      # 项目名
environment:                  # 环境名
service.namespace:            # K8s 命名空间
service.type:                 # ClusterIP/LoadBalancer
service.annotations:          # Service 注解（SLB 等）

# 镜像基础设施
image.registry:               # 镜像仓库地址
image.pullSecret:             # 镜像凭据
image.pullPolicy:             # 拉取策略

# 资源上限（防止吃光集群）
resources.limits:             # CPU / 内存上限

# 集群安全
securityContext:              # 容器/Pod 安全上下文
serviceAccount:               # ServiceAccount

# 集群调度
nodeSelector:                 # 节点选择
tolerations:                  # 污点容忍
affinity:                     # 亲和性

# 集群稳定性
pdb:                          # Pod 中断预算
strategy:                     # Deployment 策略
updateStrategy:               # StatefulSet 策略

# 持久化存储
pvc:                          # PVC
volumeClaimTemplates:         # StatefulSet 卷模板

# 工作负载类型
workloadType:                 # deployment/statefulset

# 合规与审计
extraLabels:                  # 合规标签
```

### ⚙️ CI 自动注入（不写在任何 values）

```yaml
image.tag:                    # R<commit_short>，每次部署
image.pullSecretData:         # docker registry secret，从 Jenkins Credential
nacos.username/password:      # Nacos 凭据，从 Jenkins Credential
```

## 三、合并优先级

```
① charts/generic-service/values.yaml          (Chart 默认)
       ↓ 被覆盖
② baselines/_global.yaml                       (全公司基线 / 必加)
       ↓ 被覆盖
③ baselines/{project}/{svc}/baseline-{env}.yaml (服务级基线 / 可选)
       ↓ 被覆盖
④ {repo}/deploy/values.yaml                    (业务通用)
       ↓ 被覆盖
⑤ {repo}/deploy/values-{env}.yaml              (业务环境)
       ↓ 被覆盖
⑥ helm --set image.tag=...                     (CI 注入)
```

注：运维 baseline 在开发 values **之前**加载，看似开发能覆盖。但部署前**校验脚本拒绝**业务 values 中含运维字段，从源头阻断。

## 四、违反契约的处理

```
❌ 部署被拒绝
   File: deploy/values-prod.yaml
   Forbidden fields detected:
     - resources.limits.cpu     (managed by Ops)
     - nodeSelector             (managed by Ops)
   Action:
     1. Remove these fields from deploy/values-prod.yaml, OR
     2. Contact Ops to update baseline at:
        k8s-deploy/baselines/adv/ad-gateway/baseline-prod.yaml
```

## 五、契约演进

| 变更 | 流程 |
|------|------|
| 新增运维字段 | 运维直接加 baseline，本文档同步 |
| 新增开发字段 | 本文档 + `values-validate.sh` 白名单同步更新 |
| 字段所有权变更 | 公告 1 周 → 灰度迁移 → 文档更新 |

## 六、FAQ

**Q：开发想看 baseline？**  
A：k8s-deploy 仓库给开发只读权限。`git clone --depth 1` 或 web UI 直接看。

**Q：开发想本地预览最终配置？**  
A：`helm template ad-gateway charts/generic-service -f baselines/_global.yaml -f deploy/values-prod.yaml`

**Q：开发改了字段不生效？**  
A：99% 是该字段属于运维。本地跑 `helm template` 看最终值。

**Q：90% 服务应该不需要服务级 baseline 吧？**  
A：对。`_global.yaml` 兜底就够。只有真正特殊的服务（防关联 EIP、StatefulSet 等）才创建 `baselines/{project}/{svc}/baseline-{env}.yaml`。
