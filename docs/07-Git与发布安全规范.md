# Git 与发布安全规范

测试和生产配置都应进入 Git，以便审阅、追溯和回退；但两者不应由同一权限边界管理，任何敏感值都不能以明文、Base64 或普通 Kubernetes `Secret` 的形式提交。

## 1. 仓库与所有权

| 内容 | 建议仓库 | Owner | 生产是否读取 |
| --- | --- | --- | --- |
| 业务代码、Dockerfile、Kustomize base | 业务仓库 | 开发团队 | 是，固定到业务 commit |
| test overlay、测试 SealedSecret | 业务仓库 | 开发团队 | 否 |
| prod overlay、生产 SealedSecret/ExternalSecret | 业务仓库受保护的 `PROD_BRANCH` | 运维/安全团队 | 是，固定到业务 commit |
| 唯一 Jenkinsfile | 业务仓库 | 开发团队/运维 Owner | 生产分支需审批 |
| 唯一业务 `Jenkinsfile`、Shared Library | 业务仓库保护规则、平台仓库 | 平台/运维团队 | 是 |
| kubeconfig、SWR/Jenkins 凭据、Sealed Secrets 私钥 | Jenkins/密钥系统 | 平台管理员 | 不进入 Git |

生产 Job 与测试 Job 使用同一个业务仓库 `Jenkinsfile`。生产安全不依赖脚本中的 kubeconfig 参数，而由 `-prod` Job 权限、共享库固定版本、管理员指定 `PROD_BRANCH`、生产 kubeconfig、白名单和人工审批共同控制。业务仓库 prod overlay 必须由 CODEOWNERS 和保护分支约束。

## 2. Git 中允许保存什么

可以提交：

- ConfigMap、Ingress、资源限制、副本数、开关和非敏感地址；
- `SealedSecret.spec.encryptedData` 密文；
- `ExternalSecret` 的 remote key/property 引用；
- Sealed Secrets Controller 公钥。

禁止提交：

- 普通 `Secret`，包括 `data` 中看似不可读的 Base64；
- `stringData`、Kustomize `secretGenerator` literal/env 文件；
- 密码、AK/SK、token、私钥、证书私钥、`.dockerconfigjson`；
- kubeconfig、Jenkins credential 导出、Sealed Secrets Controller 私钥；
- 包含真实敏感值的 `.env`、Nacos/Spring 配置或构建日志。

默认选型：已有阿里云 Secrets Manager/KMS 或 Vault 时使用 External Secrets；没有中心密钥系统时使用 Sealed Secrets。SOPS 只有在 CI/CD 或 GitOps Controller 已建立受控解密链路后再采用，当前 Jenkins 流程不默认解密 SOPS。

SealedSecret 密文不等于没有权限风险。能在目标 namespace 创建 SealedSecret、创建 Pod 并挂载生成 Secret 的主体，仍可能取得明文。因此生产 SealedSecret 只放业务仓库受保护的 `PROD_BRANCH`，使用 strict scope，并限制生产 namespace 的工作负载和 Secret 相关权限。

## 3. 分支保护和 CODEOWNERS

业务仓库至少保护 `test` 和 `PROD_BRANCH`，Shared Library 至少保护默认分支：

- 禁止直接 push、force push 和删除受保护分支；
- 所有变更通过 PR/MR，要求最新基线、状态检查通过后才能合并；
- prod overlay、业务 Jenkinsfile、Shared Library 和安全校验脚本必须由运维 Owner 审批；
- 生产配置建议至少两人审阅，触发人和批准人尽量分离；
- 管理员变更、保护规则例外和紧急发布必须保留审计记录。

业务仓库 CODEOWNERS 示例，团队名按实际代码平台修改：

```text
/deploy/kustomize/overlays/prod/ @sinozo/platform-ops
/shared-library/ @sinozo/platform-ops
/automation/kustomize-validate.sh @sinozo/platform-ops @sinozo/security
```

CODEOWNERS 文件本身也必须在保护范围内。仅增加 CODEOWNERS 文件而没有启用“必须由 Code Owner 审批”不构成门禁。

## 4. CI 合并门禁

业务仓库和 Shared Library 的 PR 至少执行：

```bash
bash automation/kustomize-validate.sh <base-or-overlay-path>
kubectl kustomize <overlay> > rendered.yaml
bash automation/kustomize-validate.sh rendered.yaml
kubectl apply --dry-run=client -f rendered.yaml
```

修改校验规则后还需执行 `bash automation/test-kustomize-validate.sh`，确认正反例行为没有回归。

还应在代码平台启用 Gitleaks、TruffleHog 或同类 secret scanning，并扫描 PR diff 与 Git 历史。`kustomize-validate.sh` 只负责 Kubernetes 结构和常见敏感键，不能识别藏在任意配置文件、JSON 字符串或未知字段中的所有秘密。

推荐增加以下检查：

- YAML/Kustomize schema 校验和策略检查；
- 镜像漏洞扫描、SBOM 和镜像签名验证；
- 禁止 privileged、hostPath、hostNetwork、危险 capability 和集群级资源；
- Ingress host 冲突、资源 requests/limits、探针和不可变 selector 检查。

关键限制最好再由 Kyverno、Gatekeeper 或 ACK 策略治理在准入层强制执行，避免其他客户端绕过 Jenkins 校验。

## 5. Jenkins 边界

- test/prod 即使连接同一 ACK 集群，也使用不同 ServiceAccount、不同 kubeconfig 和不同 Jenkins Credential；
- `K8S_CRED_PROD` 仅保存生产 Credential ID，Credential 本体只在生产 Folder/Job 可见；
- 开发不能修改生产 Job、运行生产 agent 上的任意 Pipeline 或读取生产 Folder Credentials；
- `*-test`、`*-prod` Job 与环境一一绑定，生产只能由名称以 `-prod` 结尾的 Job 执行；
- `PROD_BRANCH` 由 Jenkins 管理员定义，业务参数不能覆盖；
- Shared Library 使用受保护分支或发布 tag，生产 Job 不允许选择任意 Library 版本；
- prod deploy/rollback/restart 均要求运维白名单和人工审批。
- Docker registry 登录使用 Jenkins 临时目录中的 `DOCKER_CONFIG` 并在操作后删除，不在共享 agent 用户目录残留认证。

部署时记录完整镜像 commit、业务 commit、运维 commit 和 Jenkins build number。生产 deploy 在人工审批前完成渲染、密钥校验和 `kubectl diff`，审批后使用同一 workspace 再次校验并 apply。

## 6. Kubernetes 最小权限

Pipeline 会幂等检查 namespace：存在则复用，不存在才创建。test/prod 仍分别使用 namespaced `Role` 和 `RoleBinding`，不要把测试账号绑定到可访问生产的 `ClusterRoleBinding`。若安全基线禁止部署账号创建 namespace，可预建后将 `manageNamespace` 设为 `false`。

新服务的部署账号默认不需要普通 Secret 的写权限：

- Sealed Secrets 模式只授予 `bitnami.com/sealedsecrets` 所需权限，由 Controller 创建 Secret；
- External Secrets 模式只授予 `external-secrets.io/externalsecrets` 所需权限，由 Operator 创建 Secret；
- 默认 `manageRegistrySecret: true` 时，需要目标 namespace 内 `regcred` 的 get/create/update/patch 权限；如果由 SealedSecret/ExternalSecret/平台预置，则设置为 `false` 并移除普通 Secret 写权限。`nacosSecretMode: 'jenkins'` 仅为旧项目兼容模式，才额外评估运行时 Secret 权限。

部署账号还应只管理目标 namespace 内明确需要的 Deployment、Service、ConfigMap、Ingress、HPA、PDB 和（默认模式下）`regcred`。除自动初始化所需的 `namespaces get/create` 外，不要授予 Node、ClusterRole、ClusterRoleBinding、CRD 等其他集群级写权限；若 namespace 由平台预建，则连 namespace 创建权限也移除。

## 7. 变更和泄漏处理

生产 PR 需要记录业务 commit/镜像 tag、变更内容、验证结果、负责人、变更窗口和回退方式。测试通过的 commit 合并到管理员指定 `PROD_BRANCH` 后，生产只发布该分支当前 HEAD 对应的同一镜像 tag，不重新构建。Kustomize 的 `rollout undo` 只回滚 Deployment PodTemplate；Service、Ingress、ConfigMap、Secret 引用等变更应 revert 业务 Git commit 后重新部署。

一旦秘密误入 Git：先撤销或轮换秘密，再清理 Git 历史和缓存，最后审计使用记录。仅删除当前分支文件或把提交 revert 掉，不能让已经泄漏的秘密恢复安全。
