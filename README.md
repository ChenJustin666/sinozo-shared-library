# k8s-deploy

K8s 容器化部署平台 — Jenkins + Helm 自动化部署

## 快速开始

详见 [docs/00-完整上线指南.md](docs/00-完整上线指南.md)

## 核心理念

- **首次部署最简化**：只生成 deployment + service，能跑起来就行
- **逐步开启功能**：probes → monitoring → hpa → ingress，每次开一个验证一个
- **测试通过后上生产**：复制测试配置，改参数即可
- **全自动初始化**：project.yaml / kubeconfig / namespace / secret 首次构建自动生成

## 仓库结构

```
k8s-deploy/
├── helm_deploy.py              # 部署脚本入口
├── helm_deploy/                # 部署脚本模块
├── charts/generic-service/     # Helm Chart 模板（通用）
├── shared-library/             # Jenkins 共享库
│   ├── vars/                   # Pipeline 脚本
│   └── templates/              # Dockerfile 模板
├── projects/                   # 项目配置（自动生成）
└── docs/                       # 文档
```

## 可选资源开关

| 开关 | 默认 | 说明 |
|------|------|------|
| `probes` | 关 | 健康探针（liveness/readiness/startup） |
| `monitoring` | 关 | Prometheus 监控 annotations |
| `hpa` | 关 | 自动伸缩 |
| `ingress` | 关 | 域名访问 |
| `pvc` | 关 | 持久化存储 |
| `configmap` | 关 | 配置文件挂载 |

## 使用方式

Jenkins Pipeline script 里调用：

```groovy
@Library('k8s-deploy-lib@main') _

k8sDeploy(
    projectName:    'adv',
    serviceName:    'ad-gateway',
    serviceType:    'java',
    gitUrl:         'http://git.example.com/server/AdGateway.git',
    gitCredId:      'git-adv-cred',
    dockerImage:    'sinozo-test/ad-gateway',
    dockerCredId:   'docker-swr-cred',
    kubeconfigCredId: 'k8s-adv-test',
    port:           8080,
    javaOpts:       '-Dspring.profiles.active=test',
)
```
