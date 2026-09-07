# Kustomize 部署方案（当前标准）

本文档是本仓库当前的部署标准。共享库 `k8sDeploy` 使用 `kubectl kustomize` 生成清单，再用 `kubectl diff/apply` 部署。仓库仍保留 `charts/generic-service`、`helm_deploy` 和旧 `values*.yaml` 给未迁移服务，但它们不经由当前 `k8sDeploy` 入口；因此是“迁移期并存支持”，不是同一个 release 同时由 Helm 和 Kustomize 管理。

## 1. 适用范围与现状

当前支持两类运行时：

| 类型 | 构建 | 容器运行方式 | 默认端口 |
| --- | --- | --- | --- |
| `java` | Jenkins Maven 构建 `target/app.jar` | `java -jar` | 8080 |
| `nodejs` | Docker 内 `npm ci`，执行可选的 `npm run build` | `pm2-runtime npm -- start` | 8080 |

Node.js 服务不是默认 Nginx 静态站点。需要纯静态部署的项目仍可自带 Dockerfile 使用 Nginx，但必须自行把容器端口、探针和 Service 改为 80；平台自动模板针对现有的 Node.js + PM2/SSR/API 服务。

## 2. 权限边界

| 配置 | Owner | 仓库路径 |
| --- | --- | --- |
| 工作负载公共模板 | 平台 | `kustomize/examples/` |
| base、test/prod overlay、Dockerfile、唯一 Jenkinsfile | 开发/运维共同 review | 业务仓库 `deploy/kustomize/` |
| 生产分支保护、CODEOWNERS、生产凭据和 Job 权限 | 运维/平台 | Git/Jenkins/Kubernetes 平台配置 |
| 镜像、namespace、`regcred` | CI/运维 | Jenkins 参数、项目名和 `_overrides.yaml` |

生产 Pipeline 读取业务仓库的 prod overlay。生产分支和 `deploy/kustomize/overlays/prod/` 必须使用 CODEOWNERS、保护分支和 PR 审批，禁止直接 push。部署时所有替换都在 Jenkins 工作区的 `.kustomize-render/` 临时目录完成，不会改写业务清单；旧服务仍可从运维仓库 overlay 迁移，代码保留兼容回退。

## 3. 目录约定

### `common/` 的用途

`kustomize/examples/common/` 是平台提供的公共示例目录，不会被每个服务自动合并。这里放可按需复制和改名的公共资源：`external-secret.example.yaml`（从密钥中心同步）、`sealed-registry-secret.example.yaml`（用 SealedSecret 管理 `regcred`）和 `deployer-rbac.example.yaml`（部署账号的 Role/RoleBinding 示例）。实际接入时只把需要的资源复制到业务或运维仓库，替换真实 namespace、SecretStore、Secret 名称并加入对应 overlay 的 `resources`；不要直接把示例文件当作生产配置。

业务仓库：

```text
.
├── Dockerfile
├── Jenkinsfile
└── deploy/
    └── kustomize/
        ├── base/
        │   ├── deployment.yaml
        │   ├── service.yaml
        │   └── kustomization.yaml
        └── overlays/
            ├── test/
            │   ├── deployment-patch.yaml
            │   └── kustomization.yaml
            ├── prod/
            │   └── kustomization.yaml
            └── kustomization.yaml
```

同一业务仓库只保留一个 `Jenkinsfile`，test/prod Job 都调用受保护的 Shared Library；生产 Job 固定使用 `PROD_BRANCH`、`-prod` Job 名、审批和受保护凭据。开发不能修改生产 Job 权限、凭据或 Shared Library，因此单一 Jenkinsfile 不等于生产权限开放。旧服务的运维仓库 prod overlay 仍可作为迁移期回退。

每个 overlay 都引用 `../../base`。base 中的 `SERVICE_NAME` 是初始化模板占位符；初始化脚本会将它替换为真实服务名。`APP_IMAGE` 由 Pipeline 仅在临时副本中替换，业务和运维文件不应填写真实镜像 tag。

## 4. 新服务初始化

在本仓库执行：

```bash
./automation/init-service.sh <project> <service> java [port]
./automation/init-service.sh <project> <service> nodejs [port]
```

例如：

```bash
./automation/init-service.sh adv ad-gateway java
./automation/init-service.sh adv ad-admin nodejs
```

脚本会生成一个完整业务目录：

```text
/tmp/k8s-init/adv-ad-gateway/business/
├── Dockerfile
├── Jenkinsfile
└── deploy/kustomize/{base,overlays/test,overlays/prod}/
```

开发将 `business/` 内容复制到业务仓库并提交；生产 overlay 通过受保护分支和 CODEOWNERS 由运维 review。脚本不会覆盖已有目录。

修改初始化模板或脚本后执行 `bash automation/test-init-service.sh`，它会在临时目录生成 Java/Node 服务、渲染 test/prod overlay，并验证部署账号 RBAC 示例。

## 5. Jenkinsfile

共享库入口保持不变，最小配置如下：

```groovy
@Library('k8s-deploy-lib@main') _

k8sDeploy(
    projectName:  'adv',
    serviceName:  'ad-gateway',
    serviceType:  'java',       // java | nodejs
    dockerImage:  'ad-gateway',
    dockerCredId: 'docker-swr-cred',
    jdkTool:      'jdk 17',     // java 才需要
)
```

Node.js 服务去掉 `jdkTool`，并确保 `package.json` 有生产 `start` script：

```json
{
  "scripts": {
    "build": "next build",
    "start": "next start -p ${PORT:-8080}"
  }
}
```

Pipeline 的环境参数为 `DEPLOY_ENV=test|prod`，环境与 Job 后缀绑定：`*-test`、`*-prod` 只能部署对应环境，无后缀兼容 Job 只允许 test。`ACTION` 支持 `deploy|rollback|restart`。test 构建镜像，prod 复用 test 已验证的同一 tag。默认两个环境共用 SWR organization `sinozo`，项目确需镜像仓库隔离时才在 `_overrides.yaml` 覆盖并触发 promotion。

## 6. Java 服务约定

平台模板 [Dockerfile.java17](../shared-library/templates/Dockerfile.java17) 从 `target/app.jar` 复制运行包，业务 `pom.xml` 必须使用：

```xml
<build>
  <finalName>app</finalName>
</build>
```

Deployment 通过 `JAVA_OPTS` 注入 JVM 参数，推荐使用容器感知参数（`MaxRAMPercentage`），不要让 `-Xmx` 超过 memory limit。Nacos 用户名和密码默认由 SealedSecret/ExternalSecret 生成 `<service>-runtime` Secret，应用通过 `NACOS_USERNAME`、`NACOS_PASSWORD` 读取；地址、namespace 等非敏感配置放在 ConfigMap 或 overlay patch 中。

Java base 已包含：

- `RollingUpdate`、`maxUnavailable: 0` 和 45 秒优雅终止；
- startup/readiness/liveness TCP 探针，确认 HTTP 健康接口后再改成 `httpGet`；
- requests/limits、非 root 安全上下文和 `regcred`。

## 7. Node.js + PM2 约定

平台模板 [Dockerfile.nodejs-pm2](../shared-library/templates/Dockerfile.nodejs-pm2) 使用两阶段构建：

1. `node:20-alpine` 中执行 `npm ci`、`npm run build --if-present` 和 `npm prune --omit=dev`；
2. 运行镜像安装固定版本 `pm2`，以非 root 用户执行 `pm2-runtime npm -- start`。

业务必须提交 `package-lock.json`（或兼容 npm ci 的 lockfile），并提供不会后台 daemonize 的 `start` script。PM2 在容器中只负责进程信号转发和异常退出，副本数、扩缩容由 Kubernetes Deployment 管理，不要在容器内用 `pm2 start -i max` 再做一层集群化。

Java 与 Node.js overlay 统一默认端口 8080，并配置 TCP 探针。若存量项目实际端口不同，用初始化脚本传入端口或同时修改 Deployment 的 `containerPort`、Service `port/targetPort`、`PORT` 和探针。

## 8. Ingress 与配置注入

Ingress 属于环境 overlay，不放在 base。示例：

```yaml
apiVersion: networking.k8s.io/v1
kind: Ingress
metadata:
  name: ad-gateway
  annotations:
    alb.ingress.kubernetes.io/ssl-redirect: "true"
spec:
  ingressClassName: alb
  rules:
    - host: ad-gateway-test.example.com
      http:
        paths:
          - path: /
            pathType: Prefix
            backend:
              service:
                name: ad-gateway-svc
                port:
                  number: 8080
```

非敏感值使用 ConfigMap 或 `patches` 中的 `env`；敏感值使用 SealedSecret 或 ExternalSecret，密文资源可以提交 Git，明文 Secret 和 `secretGenerator` literal 不可以。共享库默认会幂等处理 namespace 和 `regcred`：已存在则复用，不存在时创建；创建 `regcred` 只在 Jenkins 中临时读取 Docker 凭据。若平台已用 ACK、SealedSecret 或 ExternalSecret 管理 `regcred`，可显式关闭 `manageRegistrySecret`。Java 默认读取 overlay 中的 SealedSecret；老项目设置 `nacosSecretMode: 'jenkins'` 后才由 Jenkins 凭据临时生成。

## 9. 部署、预览与回滚

本地只做渲染检查：

```bash
kubectl kustomize deploy/kustomize/overlays/test > /tmp/ad-gateway-test.yaml
kubectl apply --dry-run=client -f /tmp/ad-gateway-test.yaml
```

Jenkins 部署顺序：

1. 解析镜像 project 和 commit tag；
2. 将 base 与目标 overlay 复制到 `.kustomize-render/`；
3. 注入服务名、namespace、完整镜像地址；
4. 写入镜像、业务配置和运维配置的 Git SHA 注解；
5. `kubectl kustomize` 生成 manifest，对源文件和渲染结果各做一次密钥校验；
6. prod 先执行只读 `kubectl diff`，审批人查看差异后批准；
7. 审批后重新校验并执行 `kubectl diff/apply`（差异返回码 1 不视为失败）；
8. `kubectl rollout status deployment/<service>` 健康检查。

### 变量替换与发布时序

`SERVICE_NAME` 只是一处模板占位符。执行 `automation/init-service.sh <project> <service> <type>` 时，脚本会把模板中的占位符写成实际服务名；`APP_IMAGE` 则由 Pipeline 在临时目录 `.kustomize-render/<service>-<env>/` 中替换为完整镜像地址，Git 中的 base/overlay 不会被改写。namespace 来自 `_overrides.yaml`，未配置时默认为 `<project>-<env>`。

测试 Job 从业务仓库动态选择并 checkout 任意分支，计算 `R<40位完整Git SHA>`，镜像不存在才构建并推送，然后检查/创建 namespace 和 `regcred`，渲染、校验、diff、apply 并等待 rollout。生产 Job 使用同一个业务仓库 `Jenkinsfile`，只 checkout 管理员指定 `PROD_BRANCH` 的业务 commit，复用同一个 `R<40位SHA>` 镜像，先渲染和 `kubectl diff`，人工审批后再 apply。默认 organization 统一为 `sinozo`，因此不需要 promotion 或重新构建；只有 `_overrides.yaml` 明确配置不同 organization 时才执行 pull/tag/push，tag 仍保持不变。

例如 test 分支构建并验证 commit `28sf72ks9`（这里只展示短 SHA）后，以 fast-forward 方式合并到 `master`，生产 Job 检查 master 当前 HEAD 的完整 SHA，并将 prod 清单中的 `APP_IMAGE` 渲染为：

```text
swr.ap-southeast-3.myhuaweicloud.com/sinozo/<service>:R<master完整40位SHA>
```

生产不会重新执行 Maven/npm/Docker build；如果 master HEAD 不是已验证 commit，或者镜像 tag 与 master HEAD 不一致，共享库会拒绝发布。

生产和测试共用业务仓库的唯一 `Jenkinsfile`。安全边界由受保护的 `PROD_BRANCH`、生产 Job 权限、生产 kubeconfig、Shared Library 固定版本和人工审批提供，而不是依赖第二个 Jenkinsfile。10 位 SHA 可作为显示短标识，实际镜像 tag 使用 40 位完整 SHA，避免前缀碰撞并保证审计、回滚和“一次构建，多次部署”的唯一性。

回滚使用 Kubernetes 原生 revision：

```bash
kubectl rollout history deployment/<service> -n <project>-prod
kubectl rollout undo deployment/<service> -n <project>-prod --to-revision=<revision>
```

`ACTION=restart` 只执行 `kubectl rollout restart`。prod 的 deploy、rollback 和 restart 都需触发者白名单与人工审批。Kustomize 没有 Helm release history，因此不要再使用 `helm rollback` 或 `helm status` 判断新部署状态。

## 10. Helm 迁移清单

| 旧项 | 新项 |
| --- | --- |
| `deploy/values.yaml`、`values-test.yaml` | `deploy/kustomize/base` + `overlays/test` |
| `baselines/.../values-prod.yaml` | `baselines/.../kustomize/prod` |
| `helm upgrade --install` | `kubectl kustomize` + `kubectl apply` |
| `helm --set image.tag` | CI 在临时渲染目录替换 `APP_IMAGE` |
| `helm rollback` | `kubectl rollout undo` |
| Helm adopt 元数据 | Kustomize 不接管旧资源；迁移前确认资源 selector/name 相同，必要时先备份并由运维执行一次 `kubectl apply` |

旧服务可以继续由旧 Helm Pipeline 维护，但同一服务不要同时由 Helm 和 Kustomize 管理。迁移时先在 test namespace 用 `kubectl kustomize` 预览并确认 Deployment/Service selector 不变，再切 prod overlay。

## 11. 单服务与多服务仓库

单服务仓库：

```text
ad-gateway/
├── src/
├── Dockerfile
├── Jenkinsfile
└── deploy/kustomize/{base,overlays/test}
```

多服务仓库建议每个服务拥有独立构建上下文和 Jenkinsfile，避免一个服务修改凭据或 Dockerfile 影响另一个服务：

```text
order-backend/
├── services/
│   ├── user-service/{src,Dockerfile,Jenkinsfile,deploy/kustomize/{base,overlays/test}}
│   ├── order-service/{src,Dockerfile,Jenkinsfile,deploy/kustomize/{base,overlays/test}}
│   └── payment-service/{src,Dockerfile,Jenkinsfile,deploy/kustomize/{base,overlays/test}}
├── pom.xml
└── Jenkinsfile                     # 可选：仅做父项目校验
```

服务 Jenkinsfile 填写 `subdirectory: 'services/user-service'`。每个服务建立独立 `-test` 和 `-prod` Job，但两个 Job 的 SCM/Script Path 都指向业务仓库中的同一个 Jenkinsfile；prod Job 的权限、生产 kubeconfig 和分支由平台管理员控制。

基础模板也可集中在平台仓库的 `kustomize/examples/`，业务服务复制后独立演进；不要让多个服务直接引用另一个业务仓库的相对路径。

## 12. 故障排查

| 现象 | 检查 |
| --- | --- |
| `找不到 Kustomize base/overlay` | 目录名是否为 `deploy/kustomize/...`，prod 是否已提交到管理员指定的 `PROD_BRANCH` |
| `ImagePullBackOff` | `regcred`、SWR project/tag、Jenkins Docker 凭据和 namespace |
| PM2 容器启动即退出 | `package.json` 的 `start` 是否前台运行，端口是否和 `PORT`/Service 一致 |
| 探针失败 | 先临时改 TCP，确认监听地址为 `0.0.0.0`，再启用 HTTP path |
| Java OOM | 查看 `kubectl logs --previous`，降低 `MaxRAMPercentage` 或增加 memory limit |
| rollout 卡住 | `kubectl describe pod`、`kubectl get events`，确认镜像、Secret、资源配额和探针 |

更多变量外置和日常巡检要求见 [变量外置规范](./05-变量外置规范.md) 与 [运维操作指南](./06-运维操作指南.md)；两份文档中的 Kustomize 命令优先于旧 Helm 示例。
