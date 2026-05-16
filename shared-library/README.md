# Jenkins Shared Library - K8s 部署

## 架构概览

```
shared-library/
├── vars/                          # Pipeline 步骤
│   ├── k8sDeploy.groovy          # 入口：定义 pipeline + 参数
│   ├── initDeploy.groovy         # 初始化：检查/自动生成 values 文件
│   ├── buildJava.groovy          # Java 构建（Jenkins 全局 JDK 工具）
│   ├── buildNodejs.groovy        # 前端预检查（Docker 多阶段构建）
│   ├── pushImage.groovy          # Docker 构建 + 推送
│   ├── deployToK8s.groovy        # Helm 部署/回滚/重启
│   └── checkPermission.groovy    # 权限检查（prod 需审批）
└── templates/                     # 模板文件
    ├── Dockerfile.java8          # Java 8 Dockerfile
    ├── Dockerfile.java17         # Java 17 Dockerfile
    ├── Dockerfile.nginx          # 前端多阶段构建 Dockerfile
    ├── Jenkinsfile.java          # Java 服务 Jenkinsfile 模板
    └── Jenkinsfile.nodejs        # 前端服务 Jenkinsfile 模板
```

## 核心设计

### 1. kubeconfigCredId 自动推断

```
约定：k8s-{projectName}-{env}
例如：k8s-adv-test, k8s-adv-prod, k8s-podManager-prod
```

- 默认不需要在 Jenkinsfile 中指定
- 特殊集群可手动覆盖：`kubeconfigCredId: 'k8s-us-west-prod'`

### 2. 环境由 Job 参数控制

- `DEPLOY_ENV` 参数在 Jenkins Job 中设定默认值
- Jenkinsfile 不包含任何环境判断逻辑
- 同一份 Jenkinsfile 适用于所有环境

### 3. initDeploy 自动初始化

首次部署时，如果 values 文件不存在：
1. 自动从模板生成初始 values-{env}.yaml
2. 自动提交到 Git
3. 中断部署，提示运维检查配置
4. 运维修改后重新触发即可

### 4. JDK 使用 Jenkins 全局工具

```groovy
jdkTool: 'jdk 1.8'    // 对应 Jenkins → Global Tool Configuration 中的名称
```

## 快速接入

### Java 服务

```groovy
@Library('k8s-deploy-lib@main') _
k8sDeploy(
    projectName:  'adv',
    serviceName:  'ad-gateway',
    serviceType:  'java',
    gitUrl:       'http://git.example.com/server/AdGateway.git',
    gitCredId:    'git-adv-cred',
    dockerImage:  'sinozo/ad-gateway',
    dockerCredId: 'docker-swr-cred',
    jdkTool:      'jdk 1.8',
)
```

### 前端服务

```groovy
@Library('k8s-deploy-lib@main') _
k8sDeploy(
    projectName:  'adv',
    serviceName:  'ad-admin-fe',
    serviceType:  'nodejs',
    gitUrl:       'http://git.example.com/frontend/AdAdmin.git',
    gitCredId:    'git-adv-cred',
    dockerImage:  'sinozo/ad-admin-fe',
    dockerCredId: 'docker-swr-cred',
)
```

## Jenkins 凭据清单

| 凭据 ID | 类型 | 说明 |
|---------|------|------|
| `k8s-{project}-{env}` | Secret file | kubeconfig 文件 |
| `git-{project}-cred` | Username/Password | Git 仓库凭据 |
| `docker-swr-cred` | Username/Password | Docker Registry 凭据 |
| `nacos-cred` | Username/Password | Nacos 凭据（可选） |
