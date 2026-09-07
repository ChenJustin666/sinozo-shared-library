# k8s-deploy

Jenkins Shared Library + Kustomize 的 Kubernetes 部署方案，支持 Java 服务和 Node.js + PM2 前端/SSR 服务。

## 快速开始

```bash
./automation/init-service.sh <project> <service> java [port]
./automation/init-service.sh <project> <service> nodejs [port]
```

从平台初始化开始请阅读 [从零接入完整指南](docs/00-从零接入完整指南.md)，目录与迁移细节见 [Kustomize 部署方案](docs/Kustomize.md)，仓库权限和密钥门禁见 [Git 与发布安全规范](docs/07-Git与发布安全规范.md)。

## 核心流程

- 非生产环境构建一次镜像，生产环境在同一 `sinozo` organization 复用该 tag，不重复构建。
- 业务仓库维护 `deploy/kustomize/base`、test/prod overlay 和唯一 `Jenkinsfile`。
- 生产分支和 prod overlay 由 Git 保护规则、CODEOWNERS 和 Jenkins 权限控制。
- CI 在临时目录注入 namespace 和镜像，执行 `kubectl diff/apply`。
- 回滚使用 `kubectl rollout undo`，重启使用 `kubectl rollout restart`。

## 仓库结构

```text
├── automation/init-service.sh       # 新服务初始化
├── baselines/                       # 运维全局配置和 prod overlay
├── kustomize/examples/              # Java、Node.js + PM2 清单模板
├── shared-library/                  # Jenkins Shared Library
├── docs/                            # 接入与运维文档
├── charts/                          # 旧 Helm 资产（迁移期保留）
└── helm_deploy/                     # 旧 Helm CLI（迁移期保留）
```

## Jenkinsfile

下面是业务仓库唯一入口；prod Job 仍使用此文件，但由共享库和 Jenkins 权限控制生产能力。

```groovy
@Library('k8s-deploy-lib@main') _
k8sDeploy(
    projectName:  'adv',
    serviceName:  'ad-gateway',
    serviceType:  'java',
    dockerImage:  'ad-gateway',
    dockerCredId: 'docker-swr-cred',
    jdkTool:      'jdk 17',
)
```

旧 `values*.yaml` 和 Helm Chart 不再用于新服务。同一服务不能同时由 Helm 与 Kustomize 管理。
