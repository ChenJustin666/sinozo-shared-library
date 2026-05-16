# Jenkins 配置指南（最终版）

> 仓库：`git@github.com:ChenJustin666/sinozo-shared-library.git`  
> 适用：把本仓库作为 Jenkins Shared Library 接入

---

## 一、当前仓库结构（保持不动）

```
sinozo-shared-library/                ← 仓库根
  ├── shared-library/                 ← Jenkins Shared Library（用 Library Path 指定）
  │     ├── vars/
  │     │     ├── k8sDeploy.groovy
  │     │     ├── deployToK8s.groovy
  │     │     ├── promoteImage.groovy
  │     │     └── ...
  │     └── templates/
  │           ├── Dockerfile.java8
  │           ├── Dockerfile.java17
  │           └── Dockerfile.nginx
  │
  ├── baselines/                      ← 运维基线（运行时 clone 到 Jenkins 节点）
  │     └── _global.yaml
  ├── charts/                         ← Helm Chart（运行时使用）
  │     └── generic-service/
  ├── automation/                     ← 脚本（运行时使用）
  │     ├── init-service.sh
  │     └── values-validate.sh
  └── docs/
```

**重要**：本仓库被 Jenkins **加载两次**：
1. 作为 **Shared Library**（自动）— Jenkins 用 `shared-library/vars/`
2. 作为 **运行时资产仓库**（手动 clone）— Pipeline 在执行时读 `baselines/` `charts/` `automation/`

---

## 二、Jenkins 一次性配置（仅运维做一次）

### 2.1 Jenkins 节点上 clone 本仓库（运行时资产）

```bash
ssh jenkins-node
sudo su - jenkins
mkdir -p /var/lib/jenkins/workspace/deploy
cd /var/lib/jenkins/workspace/deploy

# clone 时改名为 k8s-deploy（匹配代码里的 DEPLOY_BASE_DIR）
git clone git@github.com:ChenJustin666/sinozo-shared-library.git k8s-deploy

# 验证
ls k8s-deploy/
# 应该看到：baselines/ charts/ automation/ shared-library/ ...
```

> **后续维护**：本仓库有更新时，需要在 Jenkins 节点 `cd k8s-deploy && git pull` 同步运行时资产。
> 可以加一个 cron job 自动 pull：
> ```bash
> echo "*/5 * * * * cd /var/lib/jenkins/workspace/deploy/k8s-deploy && git pull --quiet" | crontab -u jenkins -
> ```

### 2.2 配置 Shared Library

```
Manage Jenkins → System → Global Pipeline Libraries → 新增

Name:               k8s-deploy-lib
Default version:    main
Load implicitly:    ❌ 不勾
Allow default version override: ✅ 勾

Retrieval method:   Modern SCM
  Source Code Management: Git
  项目仓库:  git@github.com:ChenJustin666/sinozo-shared-library.git
  凭据:     <选 Git 凭据>
  行为:     发现分支
  Library Path (optional):  shared-library      ⭐⭐⭐ 关键！

保存
```

### 2.3 全局环境变量（权限白名单）

```
Manage Jenkins → System → Global properties → Environment variables

DEVOPS_USERS           = admin,ops-zhang,ops-li     # 谁能触发 prod 部署
PROD_APPROVERS         = admin,ops-zhang            # 谁能在审批阶段点确认
PROD_APPROVAL_TIMEOUT  = 60                         # 审批等待分钟
```

### 2.4 全局凭据

| 凭据 ID | 类型 | 用途 |
|---------|------|------|
| `docker-swr-cred` | Username/Password | SWR 镜像仓库（test+prod project 都要能访问）|
| `k8s-{project}-test` | Secret file (kubeconfig) | 各 K8s 集群（test 环境）|
| `k8s-{project}-prod` | Secret file (kubeconfig) | 各 K8s 集群（prod 环境）|

业务仓库的 git 凭据**通常不需要单独配**——Jenkins Job 选 "Pipeline from SCM" 时会自动用 Job 配置的凭据。

---

## 三、业务方接入：极简 Jenkinsfile

### 3.1 业务仓库根目录的 Jenkinsfile

```groovy
@Library('k8s-deploy-lib@main') _

k8sDeploy(
    projectName:  'adv',                  // 你的项目（namespace 前缀）
    serviceName:  'ad-gateway',           // 服务名
    serviceType:  'java',                 // java / nodejs
    dockerImage:  'ad-gateway',           // 镜像名（不含 SWR project 前缀）
    dockerCredId: 'docker-swr-cred',      // 全局凭据 ID
)
```

**就这 5 个字段**。git URL/凭据都不用写——Jenkins 自动从 Job 的 SCM 配置获取。

### 3.2 Jenkins Job 配置（每服务 3 个 Job）

#### Job: `ad-gateway-test`

```
New Item → Pipeline
Item name: ad-gateway-test

Pipeline:
  Definition:    Pipeline script from SCM
  SCM:           Git
  Repository:    http://gitea.example.com/adv/ad-gateway.git
  Credentials:   git-adv-cred                ← 业务仓库的 git 凭据
  Branch:        */test
  Script Path:   Jenkinsfile

Parameters (首次构建会自动注册):
  DEPLOY_ENV: test                            ← 默认值

Build Triggers:
  ✅ Generic Webhook Trigger
     Token: ad-gateway-test-<random>
     Filter expression: ^refs/heads/test$
```

#### Job: `ad-gateway-prod`

```
配置同上，差异：
  Branch:               */prod   或   refs/tags/v*
  ❌ 不配 Webhook（手动触发）
  默认 DEPLOY_ENV:     prod

Authorization:
  Project-based Matrix
    devops 组: Build, Cancel, Configure, Read
    其他用户:  仅 Read
```

#### （可选）Job: `ad-gateway-dev`

```
如果有 dev 环境，配置同 test，差异：
  Branch:        */main
  Filter expression: ^refs/heads/main$
  默认 DEPLOY_ENV:  dev
```

---

## 四、对比：业务方负担

### 改造前（旧）

```groovy
// 业务方要填 8 个字段，git 信息冗余
k8sDeploy(
    projectName:  'adv',
    serviceName:  'ad-gateway',
    serviceType:  'java',
    gitUrl:       'http://gitea.example.com/adv/ad-gateway.git',  ⚠️ 冗余
    gitCredId:    'git-adv-cred',                                  ⚠️ 冗余
    dockerImage:  'sinozo/ad-gateway',                             ⚠️ project 前缀重复
    dockerCredId: 'docker-swr-cred',
    jdkTool:      'jdk 1.8',
)
```

### 改造后（新）

```groovy
// 业务方只填 5 个字段，git/jdk 自动推断
k8sDeploy(
    projectName:  'adv',
    serviceName:  'ad-gateway',
    serviceType:  'java',
    dockerImage:  'ad-gateway',                ✅ 不含 project 前缀（多 SWR project 时自动加）
    dockerCredId: 'docker-swr-cred',
)
```

**减少 3 个字段，零理解成本**。

---

## 五、Jenkins Job 创建脚本（自动化）

如果有几十个服务，手动创建 Job 太累。可以用 Jenkins Job DSL：

```groovy
// jobs/services.groovy（运维仓库）
def services = [
    [project: 'adv', name: 'ad-gateway',    repo: 'http://gitea.example.com/adv/ad-gateway.git',    cred: 'git-adv-cred'],
    [project: 'adv', name: 'ad-puller',     repo: 'http://gitea.example.com/adv/ad-puller.git',     cred: 'git-adv-cred'],
    [project: 'fcm', name: 'fc05-api',      repo: 'http://gitea.example.com/fcm/fc05-api.git',      cred: 'git-fcm-cred'],
]

services.each { svc ->
    ['test', 'prod'].each { env ->
        pipelineJob("${svc.name}-${env}") {
            definition {
                cpsScm {
                    scm {
                        git {
                            remote {
                                url(svc.repo)
                                credentials(svc.cred)
                            }
                            branch(env == 'prod' ? '*/prod' : "*/${env}")
                        }
                    }
                    scriptPath('Jenkinsfile')
                }
            }
            parameters {
                stringParam('DEPLOY_ENV', env)
            }
            // Webhook 仅 dev/test 配
            if (env != 'prod') {
                triggers {
                    genericTrigger {
                        token("${svc.name}-${env}-token")
                        regexpFilterText('$ref')
                        regexpFilterExpression("^refs/heads/${env}\$")
                    }
                }
            }
        }
    }
}
```

放到 Jenkins Job DSL Plugin 里跑一次，所有 Job 自动建好。

---

## 六、调试 Checklist

如果 Pipeline 跑不起来，按顺序检查：

```
1. ✅ Jenkins 节点上是否有 /var/lib/jenkins/workspace/deploy/k8s-deploy
   → 没有：执行 §2.1 clone

2. ✅ Jenkins Shared Library 配的 Library Path 是否 = shared-library
   → 没配：报错 "No such DSL method 'k8sDeploy'"

3. ✅ 业务仓库 Jenkinsfile 第一行是否 @Library('k8s-deploy-lib@main') _
   → 漏了下划线：报错 "unable to resolve class"

4. ✅ docker-swr-cred 凭据是否能访问 sinozo-test 和 sinozo-prod 两个 SWR project
   → 不能：promotion 阶段会失败

5. ✅ baselines/_global.yaml 的 image.projects 是否覆盖了所有环境
   → 缺少：报错 "未配置 image.projects.xxx"

6. ✅ Jenkins 用户是否能 docker manifest inspect（验证镜像存在用）
   → docker version >= 18.09，且开启了 experimental features
```

---

## 七、相关文档

| 文档 | 用途 |
|------|------|
| `docs/ONBOARDING_GUIDE.md` | 新服务从 0 到 1 接入流程 |
| `docs/IMAGE_PROMOTION.md` | 镜像跨 SWR project 流转机制 |
| `docs/BRANCH_AND_IMAGE_STRATEGY.md` | 分支策略与镜像复用原理 |
| `docs/FIELD_OWNERSHIP.md` | 字段所有权契约（开发能改什么）|
