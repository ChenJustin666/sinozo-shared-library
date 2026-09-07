# Jenkins Shared Library

共享库负责镜像构建、Kustomize 渲染、`kubectl diff/apply`、rollout 健康检查、回滚和重启；项目配置不同镜像 organization 时才执行 promotion。

```text
shared-library/
├── vars/
│   ├── k8sDeploy.groovy        # Declarative Pipeline 入口
│   ├── buildJava.groovy        # Maven 构建
│   ├── buildNodejs.groovy      # Node.js 项目预检查
│   ├── pushImage.groovy        # Docker 构建和推送
│   ├── promoteImage.groovy     # 跨 organization 时提升镜像（默认不使用）
│   ├── withDockerRegistry.groovy # 临时 Docker 登录和凭据清理
│   └── deployToK8s.groovy      # Kustomize 部署、回滚、重启
└── templates/
    ├── Dockerfile.java8
    ├── Dockerfile.java17
    └── Dockerfile.nodejs-pm2
```

业务仓库必须提供 `deploy/kustomize/base`、test/prod overlay 和唯一 `Jenkinsfile`。Pipeline 把 base 与目标 overlay 复制到 `.kustomize-render/`，再注入 namespace 和镜像，不修改 Git 源文件。生产 Job 仍使用同一个 Jenkinsfile，但只能由共享库白名单、`-prod` Job、管理员指定 `PROD_BRANCH`、生产 kubeconfig 和人工审批控制。

```groovy
@Library('k8s-deploy-lib@main') _
k8sDeploy(
    projectName:  'adv',
    serviceName:  'ad-admin',
    serviceType:  'nodejs',
    dockerImage:  'ad-admin',
    dockerCredId: 'docker-swr-cred',
)
```

该片段是业务仓库唯一入口，test/prod Job 均可调用；prod Job 的权限、凭据和分支限制由 Jenkins 管理员及共享库控制，不能由 Jenkinsfile 参数覆盖。

Jenkins 凭据：

| ID | 类型 | 用途 |
| --- | --- | --- |
| 管理员自定义 | Secret file | kubeconfig，由 `K8S_CRED_TEST/K8S_CRED_PROD` 引用 |
| `docker-swr-cred` | Username/Password | test 构建、验证镜像；目标 namespace 没有 `regcred` 时幂等创建 |
| `nacos-cred` | Username/Password | 仅旧项目 `nacosSecretMode: 'jenkins'` 使用 |

运行节点要求 Docker、Git、Maven/JDK（Java）和支持内置 Kustomize 的 `kubectl >= 1.21`，不再要求 Helm 或 helm-diff 插件。Docker 登录使用 Jenkins 临时目录中的 `DOCKER_CONFIG`，任务结束后清理，不把 registry 凭据写入 agent 用户目录。
