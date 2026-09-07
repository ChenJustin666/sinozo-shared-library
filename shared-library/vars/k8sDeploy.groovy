/**
 * k8sDeploy - K8s 通用部署入口（Kustomize + kubectl）
 *
 * ═══ 核心设计 ═══
 * 1. 镜像 Build Once, Deploy Many
 *    每个 commit 只构建 1 次。第一次部署到非 prod 环境时构建，后续环境复用。
 *
 * 2. 单 SWR organization + 可选 Image Promotion
 *    默认 test/prod 都使用 sinozo，生产直接复用同一不可变 tag。
 *    项目显式配置不同 organization 时才执行 docker tag + push promotion。
 *
 * 3. 两环境模型
 *    - test：开发人员可选择任意已发现分支并构建/部署
 *    - prod：仅管理员/运维白名单触发，固定 PROD_BRANCH，只复用 test 已验证镜像
 *
 * 4. 配置所有权契约
 *    - 业务仓库管理 base、test/prod overlay 和唯一 Jenkinsfile
 *    - CI 只在临时目录注入镜像和 namespace
 *
 * ═══ 使用方式（极简，业务方不用配 git）═══
 * @Library('k8s-deploy-lib@main') _
 * k8sDeploy(
 *     projectName:  'adv',
 *     serviceName:  'ad-gateway',
 *     serviceType:  'java',
 *     dockerImage:  'ad-gateway',           // 不含 SWR project 前缀
 *     dockerCredId: 'docker-swr-cred',
 * )
 *
 * gitUrl/gitCredId 自动从 Jenkins Job 的 SCM 配置获取（Pipeline from SCM 模式）
 * 仅当需要 checkout 其他仓库（极少见）时才显式传 gitUrl/gitCredId
 */
def call(Map config) {

    // ── 默认值 ──
    def defaults = [
        projectName:      '',
        serviceName:      '',
        serviceType:      'java',
        agent:            '',
        gitUrl:           '',
        gitCredId:        '',
        defaultBranch:    'main',
        dockerImage:      '',                 // 服务名（不含 SWR project 前缀）
        dockerCredId:     '',
        dockerRegistry:   'swr.ap-southeast-3.myhuaweicloud.com',
        dockerContext:    '.',
        kubeconfigCredId: '',
        jdkTool:          'jdk 1.8',
        mavenTool:        '',
        mavenHome:        '/usr/local/maven',
        mavenGoals:       'clean package -DskipTests',
        subdirectory:     '',
        nacosCredId:      '',
        // sealed: Secret由Git中的SealedSecret生成（默认）；jenkins: 兼容旧项目
        nacosSecretMode:  'sealed',
        // regcred 已存在时复用，不存在时用 Jenkins Docker 凭据幂等创建
        manageRegistrySecret: true,
        // namespace 已存在时复用，不存在时自动创建
        manageNamespace:     true,
    ]

    def cfg = [:]
    defaults.each { k, v -> cfg[k] = config.containsKey(k) ? config[k] : v }

    pipeline {
        agent {
            label resolveAgentLabel(cfg)
        }

        environment {
            PROJECT_NAME    = "${cfg.projectName}"
            SERVICE_NAME    = "${cfg.serviceName}"
            DOCKER_REGISTRY = "${cfg.dockerRegistry}"
            // DEPLOY_BASE_DIR 在 "解析镜像目标" stage 中自动定位（Library 已 clone 的路径）
            // 不需要运维手动在节点上 clone 仓库
        }

        parameters {
            choice(
                name: 'DEPLOY_ENV',
                choices: inferEnvChoices(),
                description: '部署环境（仅 test/prod；由 Job 名末段绑定）'
            )
            // Requires Jenkins Git Parameter plugin. The list is loaded from
            // the Pipeline SCM repository; prod is still hard-locked below.
            gitParameter(
                name: 'GIT_BRANCH',
                type: 'PT_BRANCH',
                branchFilter: 'origin/(.*)',
                defaultValue: resolveDefaultGitBranch(cfg.defaultBranch),
                selectedValue: 'DEFAULT',
                sortMode: 'DESCENDING_SMART',
                description: 'test 可动态选择仓库分支；prod 只能使用管理员配置的 PROD_BRANCH'
            )
            choice(
                name: 'ACTION',
                choices: ['deploy', 'rollback', 'restart'],
                description: 'deploy=部署 | rollback=回滚 | restart=重启'
            )
            string(
                name: 'IMAGE_TAG',
                defaultValue: '',
                description: '镜像 tag（留空=自动计算；手工值必须为 R<40位Git SHA>）'
            )
            string(
                name: 'ROLLBACK_REVISION',
                defaultValue: '0',
                description: 'Deployment revision（0=上一个版本）'

        // ── Gitea Webhook 自动触发（Generic Webhook Trigger Plugin）──
        // token = Job 短名（如 ad-gateway-test），Gitea 侧用批量脚本配置
        // 生产 Job 永远不自动触发；手动 Build with Parameters 不受影响
        triggers {
            GenericTrigger(
                genericVariables: [
                    [key: 'GITEA_REF',    value: '$.ref'],
                    [key: 'GITEA_AFTER',  value: '$.after'],
                    [key: 'GITEA_PUSHER', value: '$.pusher.login'],
                    [key: 'GITEA_REPO',   value: '$.repository.full_name'],
                ],
                token: env.JOB_NAME?.tokenize('/')?.last() ?: '',
                regexpFilterText: '$GITEA_REF',
                regexpFilterExpression: resolveWebhookBranchFilter(cfg),
                causeString: 'Gitea push by $GITEA_PUSHER to $GITEA_REF',
                printContributedVariables: true,
                printPostContent: false,
                silentResponse: false,
            )
        }

            )
            booleanParam(
                name: 'SKIP_HEALTH_CHECK',
                defaultValue: false,
                description: '跳过健康检查'
            )
        }

        stages {
            stage('权限检查') {
                steps {
                    script {
                        validatePipelineConfig(cfg)
                        validateJobEnvironment(params.DEPLOY_ENV)
                        validateRequestedBranch(params.GIT_BRANCH)
                        checkPermission(params.DEPLOY_ENV)
                        validateProductionRequest(cfg, params.DEPLOY_ENV, params.ACTION, params.GIT_BRANCH)
                    }
                }
            }

            // ────────────────────────────────────────────────────────────────
            // 「定位运行时资产」: rollback / restart / deploy 都需要
            //   作用：定位运维仓库的 baselines、Kustomize 模板和项目覆盖配置
            //   resolveNamespace 需要它，所以即使 rollback/restart 也得跑
            //   restart/rollback 用最少的代价跑这个 stage
            // ────────────────────────────────────────────────────────────────
            stage('定位运行时资产') {
                steps {
                    script {
                        env.DEPLOY_BASE_DIR = resolveDeployBaseDir()
                        echo "📍 DEPLOY_BASE_DIR (自动定位): ${env.DEPLOY_BASE_DIR}"
                    }
                }
            }

            // ────────────────────────────────────────────────────────────────
            // 「解析镜像目标 + 计算 tag」: 仅 ACTION=deploy 才需要
            //   restart 不构建/不切镜像，rollback 使用 Deployment revision
            //   两者都不需要 git checkout / docker tag / IMAGE_PROJECT
            // ────────────────────────────────────────────────────────────────
            stage('解析镜像目标 + 计算 tag') {
                when { expression { params.ACTION == 'deploy' } }
                steps {
                    script {
                        // ═══ Checkout 策略 ═══
                        // Jenkins "Pipeline from SCM" 模式已自动 checkout（Declarative: Checkout SCM）
                        // 默认情况下不重复 checkout，直接复用 workspace
                        //
                        // 只在 2 种特殊场景才需要再 checkout：
                        //   场景 A：用户传了 GIT_BRANCH 参数，且非首次构建（要切到指定分支/tag）
                        //   场景 B：用户显式传了 gitUrl/gitCredId（monorepo 跨仓库部署）
                        def explicitRepo = (cfg.gitUrl?.trim() && cfg.gitCredId?.trim())
                        def requestedBranch = params.GIT_BRANCH?.trim()?.replaceFirst(/^refs\/heads\//, '')?.replaceFirst(/^origin\//, '')
                        def currentBranch = (env.BRANCH_NAME ?: '').replaceFirst(/^origin\//, '')
                        def explicitBranch = (requestedBranch && requestedBranch != currentBranch)

                        if (explicitRepo) {
                            echo "📥 Checkout 显式仓库: ${cfg.gitUrl} (branch=${params.GIT_BRANCH})"
                            env.BUSINESS_WORKSPACE = "${env.WORKSPACE}/.business-source"
                            dir(env.BUSINESS_WORKSPACE) {
                                deleteDir()
                                checkout([
                                    $class: 'GitSCM',
                                    branches: [[name: "*/${requestedBranch}"]],
                                    userRemoteConfigs: [[
                                        credentialsId: cfg.gitCredId,
                                        url: cfg.gitUrl
                                    ]]
                                ])
                            }
                        } else if (explicitBranch) {
                            env.BUSINESS_WORKSPACE = env.WORKSPACE
                            echo "📥 切换到用户指定分支/tag: ${params.GIT_BRANCH}"
                            checkout([
                                $class: 'GitSCM',
                                branches: [[name: "${params.GIT_BRANCH}"]],
                                userRemoteConfigs: scm.userRemoteConfigs,
                                extensions: scm.extensions ?: []
                            ])
                        } else {
                            env.BUSINESS_WORKSPACE = env.WORKSPACE
                            echo "📥 复用 Jenkins SCM 已 checkout 的 workspace（分支: ${env.BRANCH_NAME ?: 'auto'}）"
                        }

                        dir(env.BUSINESS_WORKSPACE) {
                            // 自动获取 git 信息回填到 cfg
                            cfg.gitUrl = cfg.gitUrl ?: sh(
                                script: 'git config --get remote.origin.url',
                                returnStdout: true
                            ).trim()
                            def actualBranch = sh(
                                script: 'git rev-parse --abbrev-ref HEAD 2>/dev/null || echo "(detached)"',
                                returnStdout: true
                            ).trim()
                            echo "📥 当前仓库: ${cfg.gitUrl}"
                            echo "📥 当前分支: ${actualBranch}"

                            if (params.DEPLOY_ENV == 'prod') {
                                verifyProductionCommit()
                            }

                            env.GIT_COMMIT_ID = sh(
                                script: 'git rev-parse HEAD',
                                returnStdout: true
                            ).trim()
                        }

                        // 镜像 tag
                        if (params.IMAGE_TAG?.trim()) {
                            env.DOCKER_TAG = params.IMAGE_TAG.trim()
                            echo "📌 手动指定的镜像 tag: ${env.DOCKER_TAG}"
                        } else {
                            env.DOCKER_TAG = "R${env.GIT_COMMIT_ID}"
                            echo "📌 自动计算镜像 tag: ${env.DOCKER_TAG}"
                        }
                        if (!(env.DOCKER_TAG ==~ /^R[0-9a-f]{40}$/)) {
                            error '镜像 tag 必须为 R<40位小写Git SHA>'
                        }
                        if (params.DEPLOY_ENV == 'prod') {
                            def requestedImageCommit = env.DOCKER_TAG.substring(1)
                            if (requestedImageCommit != env.GIT_COMMIT_ID) {
                                error "生产镜像必须对应 ${env.PROD_BRANCH} 当前 HEAD ${env.GIT_COMMIT_ID}；请先将已测试 commit 合并为该分支 HEAD，再发布"
                            }
                            echo "✅ 生产镜像 commit 与 ${env.PROD_BRANCH} HEAD 一致: ${requestedImageCommit}"
                        }

                        // 解析 IMAGE_PROJECT（默认所有环境使用同一 organization）
                        def projectMap = readImageProjectsMap()
                        projectMap.each { projectEnv, project ->
                            if (!(projectEnv ==~ /^[a-z0-9-]+$/) ||
                                !(project ==~ /^[a-z0-9][a-z0-9._-]*$/)) {
                                error "baselines 中的镜像 organization 非法: ${projectEnv}=${project}"
                            }
                        }

                        // 当前环境的 project（部署目标）
                        env.IMAGE_PROJECT = projectMap[params.DEPLOY_ENV]
                        if (!env.IMAGE_PROJECT) {
                            error "❌ baselines/_global.yaml 中未配置 image.projects.${params.DEPLOY_ENV}"
                        }

                        // 两环境模型：test 构建，prod 复用同一 organization/tag。
                        env.IMAGE_PROJECT_BUILD = projectMap['test'] ?: env.IMAGE_PROJECT

                        echo "📌 IMAGE_PROJECT (${params.DEPLOY_ENV}): ${env.IMAGE_PROJECT}"
                        echo "📌 IMAGE_PROJECT_BUILD (镜像源): ${env.IMAGE_PROJECT_BUILD}"

                        if (env.IMAGE_PROJECT != env.IMAGE_PROJECT_BUILD) {
                            echo "📌 跨 organization 部署：镜像将从 ${env.IMAGE_PROJECT_BUILD} promotion 到 ${env.IMAGE_PROJECT}"
                        }
                    }
                }
            }

            // 仅 ACTION=deploy 需要做 initDeploy（git pull 运维仓库）
            // restart/rollback 不依赖运维仓库代码版本
            stage('初始化') {
                when { expression { params.ACTION == 'deploy' } }
                steps {
                    script {
                        initDeploy(cfg, params.DEPLOY_ENV)
                    }
                }
            }


            stage('构建镜像（按需）') {
                when {
                    allOf {
                        expression { params.ACTION == 'deploy' }
                        expression { params.DEPLOY_ENV != 'prod' }   // prod 永远不构建
                        expression { !params.IMAGE_TAG?.trim() }      // 手动指定 tag 时跳过
                        expression { needBuildImage(cfg) }            // 镜像不存在才构建
                    }
                }
                steps {
                    script {
                        echo "🔨 镜像构建目标 project: ${env.IMAGE_PROJECT_BUILD}"
                        // pushImage 会用 env.IMAGE_PROJECT_BUILD 作为推送目标
                        // （由 pushImage.groovy 内部读取 env.IMAGE_PROJECT，但构建阶段我们覆盖一下）
                        env.IMAGE_PROJECT_PUSH_TARGET = env.IMAGE_PROJECT_BUILD
                        dir(env.BUSINESS_WORKSPACE ?: env.WORKSPACE) {
                            if (cfg.subdirectory) {
                                dir(cfg.subdirectory) {
                                    buildAndPush(cfg)
                                }
                            } else {
                                buildAndPush(cfg)
                            }
                        }
                    }
                }
            }

            stage('Image Promotion（跨 project 流转）') {
                when {
                    allOf {
                        expression { params.ACTION == 'deploy' }
                        expression { params.DEPLOY_ENV == 'prod' }
                        expression { env.IMAGE_PROJECT != env.IMAGE_PROJECT_BUILD }
                    }
                }
                steps {
                    script {
                        promoteImage(cfg, env.IMAGE_PROJECT_BUILD, env.IMAGE_PROJECT, env.DOCKER_TAG)
                    }
                }
            }

            stage('验证镜像（部署前最后一道关）') {
                when {
                    expression { params.ACTION == 'deploy' }
                }
                steps {
                    script {
                        verifyImageExists(cfg, env.IMAGE_PROJECT)
                    }
                }
            }

            stage('生产变更预览') {
                when {
                    allOf {
                        expression { params.DEPLOY_ENV == 'prod' }
                        expression { params.ACTION == 'deploy' }
                    }
                }
                steps {
                    script {
                        def kubeCred = resolveKubeconfigCred(cfg, params.DEPLOY_ENV)
                        withCredentials([file(credentialsId: kubeCred, variable: 'KUBECONFIG')]) {
                            // 渲染、校验并执行 kubectl diff，不修改集群。审批人先看差异再批准。
                            deployToK8s(cfg, params.DEPLOY_ENV, params, false)
                        }
                    }
                }
            }

            stage('生产操作审批') {
                when {
                    expression { params.DEPLOY_ENV == 'prod' }
                }
                steps {
                    script {
                        prodApproval(cfg)
                    }
                }
            }

            // ────────────────────────────────────────────────────────────────
            // 「部署到 K8s」: 三种 ACTION 走不同分支
            //   - deploy:   kubectl kustomize/apply + Docker pull secret + Nacos 凭据
            //   - rollback: kubectl rollout undo (只需 kubeconfig)
            //   - restart:  kubectl rollout restart (只需 kubeconfig)
            // ────────────────────────────────────────────────────────────────
            stage('部署到 K8s') {
                steps {
                    script {
                        def kubeCred = resolveKubeconfigCred(cfg, params.DEPLOY_ENV)
                        echo "🔑 使用 kubeconfig 凭据: ${kubeCred}"

                        withCredentials([file(credentialsId: kubeCred, variable: 'KUBECONFIG')]) {
                            if (params.ACTION == 'deploy') {
                                // 默认 regcred 由平台预置，部署阶段不额外读取 registry 凭据。
                                if (cfg.manageRegistrySecret) {
                                    withCredentials([usernamePassword(
                                        credentialsId: cfg.dockerCredId,
                                        usernameVariable: 'DOCKER_USER',
                                        passwordVariable: 'DOCKER_PASS'
                                    )]) {
                                        deployWithRuntimeCredentials(cfg, params.DEPLOY_ENV, params)
                                    }
                                } else {
                                    deployWithRuntimeCredentials(cfg, params.DEPLOY_ENV, params)
                                }
                            } else {
                                // restart / rollback：只需要 kubeconfig，不读 Docker / Nacos 凭据
                                deployToK8s(cfg, params.DEPLOY_ENV, params)
                            }
                        }
                    }
                }
            }

        }

        post {
            success {
                echo "✅ ${params.ACTION} 成功: ${cfg.serviceName} (${params.DEPLOY_ENV}) tag=${env.DOCKER_TAG}"
            }
            failure {
                echo "❌ ${params.ACTION} 失败: ${cfg.serviceName} (${params.DEPLOY_ENV})"
            }
        }
    }
}

// ════════════════════════════════════════════════════════════════════════
// Helper functions
// ════════════════════════════════════════════════════════════════════════

def validatePipelineConfig(Map cfg) {
    ['projectName', 'serviceName', 'dockerImage', 'dockerCredId'].each { key ->
        if (!cfg[key]?.toString()?.trim()) {
            error "k8sDeploy 缺少必填参数: ${key}"
        }
    }
    if (!['java', 'nodejs'].contains(cfg.serviceType)) {
        error "serviceType 只支持 java 或 nodejs，当前值: ${cfg.serviceType}"
    }
    if (!['sealed', 'jenkins'].contains(cfg.nacosSecretMode)) {
        error "nacosSecretMode 只支持 sealed 或 jenkins，当前值: ${cfg.nacosSecretMode}"
    }
    if (cfg.nacosSecretMode == 'jenkins' && cfg.serviceType == 'java' && !cfg.nacosCredId?.trim()) {
        error 'nacosSecretMode=jenkins 时必须配置 nacosCredId'
    }
    def dnsName = /^[a-z0-9]([-a-z0-9]*[a-z0-9])?$/
    if (!(cfg.projectName ==~ dnsName) || !(cfg.serviceName ==~ dnsName)) {
        error 'projectName 和 serviceName 必须是小写 Kubernetes DNS 名称'
    }
    if (!(cfg.dockerRegistry ==~ /^[A-Za-z0-9.:-]+$/) ||
        !(cfg.dockerImage ==~ /^[a-z0-9][a-z0-9._\/-]*$/)) {
        error 'dockerRegistry 或 dockerImage 格式非法'
    }
    if (cfg.subdirectory &&
        (!(cfg.subdirectory ==~ /^[A-Za-z0-9._\/-]+$/) || cfg.subdirectory.contains('..') || cfg.subdirectory.startsWith('/'))) {
        error 'subdirectory 必须是业务仓库内的相对路径'
    }
    if (!(cfg.dockerContext ==~ /^[A-Za-z0-9._\/-]+$/) ||
        cfg.dockerContext.contains('..') || cfg.dockerContext.startsWith('/')) {
        error 'dockerContext 必须是业务仓库内的相对路径'
    }
    def hasGitUrl = cfg.gitUrl?.trim() as boolean
    def hasGitCred = cfg.gitCredId?.trim() as boolean
    if (hasGitUrl != hasGitCred) {
        error 'gitUrl 和 gitCredId 必须同时配置或同时留空'
    }
}

def deployWithRuntimeCredentials(Map cfg, String deployEnv, def runtimeParams) {
    if (cfg.serviceType == 'java' && cfg.nacosSecretMode == 'jenkins') {
        withCredentials([usernamePassword(
            credentialsId: cfg.nacosCredId,
            usernameVariable: 'NACOS_USERNAME',
            passwordVariable: 'NACOS_PASSWORD'
        )]) {
            deployToK8s(cfg, deployEnv, runtimeParams)
        }
    } else {
        deployToK8s(cfg, deployEnv, runtimeParams)
    }
}

/**
 * 从 Job 名末段绑定 DEPLOY_ENV。
 *
 * 规则：
 *   - Job 名以 -test / -prod 结尾 → 只允许对应环境
 *   - 不匹配 → 只提供 test；生产必须使用独立的 *-prod Job
 *
 * 例：
 *   ad-gateway-test  → ['test']
 *   ad-gateway-prod  → ['prod']
 *   ad-gateway       → ['test']
 */
@NonCPS
def inferEnvChoices() {
    def allEnvs = ['test', 'prod']
    def jobName = (env?.JOB_NAME ?: '').toLowerCase()

    // 取 Job 名最后一段（去掉 folder 路径前缀）
    def shortName = jobName.tokenize('/').last()

    // 提取末段（最后一个 - 之后）
    def suffix = shortName.tokenize('-').last()

    // 环境专用 Job 不能切换到其他环境，避免 test Job 请求生产凭据。
    if (allEnvs.contains(suffix)) {
        return [suffix]
    }

    // 无环境后缀的兼容 Job 永远不能部署生产。
    return ['test']
}

def validateJobEnvironment(String deployEnv) {
    def allEnvs = ['test', 'prod']
    def shortName = (env.JOB_NAME ?: '').tokenize('/').last()?.toLowerCase() ?: ''
    def suffix = shortName.tokenize('-').last()
    if (allEnvs.contains(suffix) && suffix != deployEnv) {
        error "Job '${shortName}' 只允许部署 ${suffix}，不能选择 ${deployEnv}"
    }
    if (deployEnv == 'prod' && suffix != 'prod') {
        error '生产部署必须由名称以 -prod 结尾的独立 Job 执行'
    }
}

def validateRequestedBranch(String requestedRef) {
    def branch = requestedRef?.trim()
    if (!branch || !(branch ==~ /^[A-Za-z0-9][A-Za-z0-9._\/-]*$/) ||
        branch.contains('..') || branch.contains('//') || branch.endsWith('/') || branch.endsWith('.lock')) {
        error "非法业务仓库分支: '${requestedRef ?: ''}'"
    }
}

/**
 * 解析 agent label，三层 fallback
 *
 * 优先级：
 *   1. 业务 Jenkinsfile 显式传入：k8sDeploy(agent: 'k8s-node', ...)
 *   2. Jenkins 全局环境变量：BUILD_AGENT_LABEL=k8s-node    (运维统一配)
 *   3. null（任何节点）
 *
 * 注意：函数在 pipeline {} 块求值时调用，所以只能用纯 Groovy / env 变量，
 * 不能用 fileExists/sh 等 step
 */
@NonCPS
def resolveAgentLabel(Map cfg) {
    if (cfg.agent?.trim()) {
        return cfg.agent.trim()
    }
    def globalLabel = env?.BUILD_AGENT_LABEL
    if (globalLabel?.trim()) {
        return globalLabel.trim()
    }
    return null
}

/**
 * 自动定位 Library 已 clone 的根目录（含 baselines/ kustomize/ automation/）
 *
 * Jenkins 加载 Shared Library 时，会把整个仓库 clone 到：
 *   ${WORKSPACE}@libs/<library-name>/                    (单 library 默认)
 *   ${WORKSPACE}@libs/<library-name>@<version>/<hash>/   (新版 Jenkins 用 hash)
 *
 * 我们直接在 ${WORKSPACE}@libs/ 下找包含 baselines/_global.yaml 的目录
 *
 * 备用：环境变量 K8S_DEPLOY_DIR 显式指定（高级场景）
 */
def resolveDeployBaseDir() {
    // 优先级 1：环境变量显式指定（高级场景或本地测试）
    if (env.K8S_DEPLOY_DIR?.trim() && fileExists("${env.K8S_DEPLOY_DIR}/baselines/_global.yaml")) {
        echo "📍 使用环境变量 K8S_DEPLOY_DIR"
        return env.K8S_DEPLOY_DIR.trim()
    }

    // 优先级 2：复用当前 Job SCM 中的平台基线（旧服务兼容）。
    // 业务仓库通常没有 baselines，因此会继续查找 Shared Library 资产。
    if (fileExists("${env.WORKSPACE}/baselines/_global.yaml")) {
        echo "📍 使用当前 Job SCM 中的运维配置"
        return env.WORKSPACE
    }

    // 优先级 3：${WORKSPACE}@libs/ 下查找（controller 节点 OK，agent 节点通常找不到）
    def libsRoot = "${env.WORKSPACE}@libs"
    if (fileExists(libsRoot)) {
        def candidates = sh(
            script: """find '${libsRoot}' -maxdepth 3 -type f -name '_global.yaml' -path '*/baselines/_global.yaml' 2>/dev/null | head -5""",
            returnStdout: true
        ).trim().split('\n').findAll { it }

        if (candidates) {
            def found = candidates[0].replaceFirst('/baselines/_global\\.yaml$', '')
            echo "📍 使用 Library clone 路径（controller 上的 @libs/）"
            return found
        }
    }

    // 优先级 4：传统路径（运维手动 clone 的兼容路径）
    def legacyPath = "/var/lib/jenkins/workspace/deploy/k8s-deploy"
    if (fileExists("${legacyPath}/baselines/_global.yaml")) {
        echo "📍 使用兼容路径: ${legacyPath}"
        return legacyPath
    }

    // 优先级 5：在 agent 上使用 Jenkins GitSCM 受管 checkout。
    // 不把凭据拼进 URL，避免敏感信息留在 .git/config。
    def libUrl = env.K8S_DEPLOY_LIB_URL?.trim()
    def libCred = env.K8S_DEPLOY_LIB_CRED?.trim()
    def libBranch = env.K8S_DEPLOY_LIB_BRANCH?.trim() ?: 'main'
    def runtimeDir = "${env.WORKSPACE}/.k8s-deploy-runtime"

    if (!libUrl || !libCred) {
        error '无法访问 Shared Library 运行时资产；请由 Jenkins 管理员配置 K8S_DEPLOY_LIB_URL 和 K8S_DEPLOY_LIB_CRED'
    }
    if (!(libBranch ==~ /^[A-Za-z0-9._\/-]+$/) || libBranch.contains('..') || libBranch.startsWith('/')) {
        error 'K8S_DEPLOY_LIB_BRANCH 格式非法'
    }

    echo "📥 agent 节点上未找到运行时资产，自动 clone..."
    echo "    URL:    ${libUrl}"
    echo "    Branch: ${libBranch}"
    echo "    To:     ${runtimeDir}"

    dir(runtimeDir) {
        deleteDir()
        checkout([
            $class: 'GitSCM',
            branches: [[name: "*/${libBranch}"]],
            doGenerateSubmoduleConfigurations: false,
            extensions: [[
                $class: 'CloneOption',
                depth: 1,
                noTags: true,
                shallow: true,
                timeout: 10
            ]],
            userRemoteConfigs: [[
                credentialsId: libCred,
                url: libUrl
            ]]
        ])
    }

    if (!fileExists("${runtimeDir}/baselines/_global.yaml")) {
        error """❌ 自动 clone 后仍找不到 baselines/_global.yaml

可能原因：
  - 凭据 ${libCred} 不存在或权限不足
  - 仓库 URL 错误：${libUrl}
  - 分支不存在：${libBranch}

可在 Jenkins 全局环境变量配置覆盖：
  K8S_DEPLOY_LIB_URL    自定义仓库 URL
  K8S_DEPLOY_LIB_CRED   自定义凭据 ID
  K8S_DEPLOY_LIB_BRANCH 自定义分支
"""
    }

    echo "✅ Clone 成功"
    return runtimeDir
}

/**
 * 读取 image.projects 映射（项目级 _overrides.yaml 优先，全局 _global.yaml 兜底）
 *
 * 加载顺序：
 *   1. baselines/projects/<project>/_overrides.yaml （存在且含 image.projects 时优先用）
 *   2. baselines/_global.yaml                       （默认）
 *
 * 不依赖 Pipeline Utility Steps 插件（readYaml）
 * 用 python3+yaml（首选）或 awk 状态机（兜底）解析
 *
 * 期望的 YAML 片段：
 *   image:
 *     projects:
 *       dev:  sinozo
 *       test: sinozo
 *       prod: sinozo
 */
def readImageProjectsMap() {
    def baseDir = env.DEPLOY_BASE_DIR
    def projectName = env.PROJECT_NAME ?: ''

    // 候选 YAML 文件（按优先级顺序）
    def candidates = []
    if (projectName) {
        def projectOverrides = "${baseDir}/baselines/projects/${projectName}/_overrides.yaml"
        if (fileExists(projectOverrides)) {
            candidates << [path: projectOverrides, source: '项目级覆盖']
        }
    }
    def globalBaseline = "${baseDir}/baselines/_global.yaml"
    if (fileExists(globalBaseline)) {
        candidates << [path: globalBaseline, source: '全局基线']
    }

    if (candidates.isEmpty()) {
        error "❌ 未找到 baselines/_global.yaml 和任何项目级 _overrides.yaml"
    }

    // 依次尝试，第一个含 image.projects 的就用它（项目级整体覆盖全局）
    for (c in candidates) {
        def map = parseImageProjectsFromFile(c.path)
        if (map && !map.isEmpty()) {
            echo "📌 image.projects 来源: ${c.source} (${c.path})"
            echo "📌 解析到 image.projects 映射: ${map}"
            return map
        }
    }

    error """❌ 未在以下文件解析到 image.projects 映射：
${candidates.collect { '  - ' + it.path }.join('\n')}

期望的格式：
  image:
    projects:
      dev:  sinozo
      test: sinozo
      prod: sinozo
"""
}

/**
 * 解析单个 YAML 文件的 image.projects 映射
 * @return 非空 Map（找到了 image.projects 且非空）/ 空 Map（没这个字段或为空）
 */
def parseImageProjectsFromFile(String filePath) {
    def kvPairs = sh(
        script: """
set +e
F='${filePath}'

# 方案 1：python3 + yaml
if command -v python3 >/dev/null 2>&1 && python3 -c 'import yaml' 2>/dev/null; then
    python3 - "\$F" <<'PYEOF'
import sys, yaml
with open(sys.argv[1]) as f:
    data = yaml.safe_load(f) or {}
projects = (data.get('image') or {}).get('projects') or {}
for k, v in projects.items():
    print(f'{k}={v}')
PYEOF
    exit 0
fi

# 方案 2：awk 状态机
awk '
BEGIN { in_image=0; in_projects=0 }
/^image:[[:space:]]*\$/  { in_image=1; in_projects=0; next }
/^[a-zA-Z]/              { if (!/^image:/) { in_image=0; in_projects=0 } }
in_image && /^[[:space:]]+projects:[[:space:]]*\$/ { in_projects=1; next }
in_projects && /^[[:space:]]{4,}[a-zA-Z][a-zA-Z0-9_-]*:[[:space:]]/ {
    line = \$0
    sub(/^[[:space:]]+/, "", line)
    sub(/[[:space:]]*#.*\$/, "", line)
    colon = index(line, ":")
    if (colon > 0) {
        k = substr(line, 1, colon-1)
        v = substr(line, colon+1)
        gsub(/^[[:space:]]+|[[:space:]]+\$/, "", v)
        gsub(/^["'"'"']|["'"'"']\$/, "", v)
        if (k != "" && v != "") print k "=" v
    }
}
in_projects && /^[[:space:]]{0,3}[a-zA-Z]/ { in_projects=0 }
' "\$F"
        """,
        returnStdout: true
    ).trim()

    def projectMap = [:]
    if (kvPairs) {
        kvPairs.split('\n').each { line ->
            def parts = line.split('=', 2)
            if (parts.length == 2) {
                projectMap[parts[0].trim()] = parts[1].trim()
            }
        }
    }
    return projectMap
}


/**
 * 判断是否需要构建镜像（在 IMAGE_PROJECT_BUILD 中检查同 tag 是否已存在）
 */
def needBuildImage(Map cfg) {
    def fullImage = "${cfg.dockerRegistry}/${env.IMAGE_PROJECT_BUILD}/${cfg.dockerImage}:${env.DOCKER_TAG}"

    echo "🔍 检查镜像是否已存在: ${fullImage}"

    def rc = withDockerRegistry(cfg) {
        sh(
            script: "docker manifest inspect '${fullImage}' >/dev/null 2>&1",
            returnStatus: true
        )
    }

    if (rc == 0) {
        echo "  ✅ 镜像已存在，跳过构建"
        return false
    } else {
        echo "  ⚠️  镜像不存在，需要构建"
        return true
    }
}

/**
 * 构建并推送镜像
 *
 * 推送目标：env.IMAGE_PROJECT_BUILD（默认 sinozo）
 */
def buildAndPush(Map cfg) {
    if (cfg.serviceType == 'java') {
        buildJava(cfg)
    } else if (cfg.serviceType == 'nodejs') {
        buildNodejs(cfg)
    }

    // 临时把 env.IMAGE_PROJECT 设置为 BUILD 目标，让 pushImage 使用它
    def originalProject = env.IMAGE_PROJECT
    env.IMAGE_PROJECT = env.IMAGE_PROJECT_BUILD
    try {
        pushImage(cfg)
    } finally {
        env.IMAGE_PROJECT = originalProject
    }
}

/**
 * 验证镜像在指定 project 中存在
 */
def verifyImageExists(Map cfg, String project) {
    def fullImage = "${cfg.dockerRegistry}/${project}/${cfg.dockerImage}:${env.DOCKER_TAG}"

    echo "🔍 验证镜像存在: ${fullImage}"

    withDockerRegistry(cfg) {
        def rc = sh(
            script: "docker manifest inspect '${fullImage}' >/dev/null 2>&1",
            returnStatus: true
        )

        if (rc != 0) {
            error """❌ 镜像不存在: ${fullImage}

可能原因：
  1. 该 commit 镜像从未构建（没有任何非 prod 环境部署过）
  2. 镜像被清理过期了
  3. 跨 project 但 promotion 阶段失败

修复方法：
  - 先去 ${cfg.serviceName}-test Job 部署一次（构建镜像）
  - 或手动指定一个已存在的镜像 tag（参数 IMAGE_TAG）
"""
        }
        echo "✅ 镜像已存在，可部署"
    }
}

/**
 * 解析 kubeconfig 凭据 ID
 *
 * 推荐由 Jenkins 管理员配置 K8S_CRED_<ENV>。
 *
 * 旧业务 Jenkinsfile 的 Map/字符串写法只兼容非生产环境：
 *   k8sDeploy(
 *       kubeconfigCredId: [
 *           test: 'test-k8s-aliyun-am',
 *           prod: 'prod-k8s-aliyun-am',
 *       ],
 *   )
 *
 * 字符串形式：
 *   k8sDeploy(kubeconfigCredId: 'k8s-bigdata')
 *
 * 不传（推荐）：
 *   k8sDeploy(...)
 *
 * 解析规则：prod 只读 K8S_CRED_PROD；非生产优先 K8S_CRED_<ENV>，再兼容旧写法。
 */
def resolveKubeconfigCred(Map cfg, String deployEnv) {
    def credValue = cfg.kubeconfigCredId

    // 生产凭据只能由 Jenkins 管理员在全局环境中配置。业务 Jenkinsfile 无权覆盖。
    if (deployEnv == 'prod') {
        def prodCred = env.K8S_CRED_PROD?.trim()
        if (!prodCred) {
            error '未配置 Jenkins 全局环境变量 K8S_CRED_PROD，拒绝生产操作'
        }
        echo '    (来源: Jenkins 管理员配置 K8S_CRED_PROD；忽略业务 Jenkinsfile 的 prod 凭据)'
        return prodCred
    }

    // 非生产也优先使用管理员定义的环境凭据。
    def envKey = "K8S_CRED_${deployEnv.toUpperCase()}"
    def globalCred = env."${envKey}"
    if (globalCred?.trim()) {
        echo "    (来源: Jenkins 全局环境变量 ${envKey})"
        return globalCred.trim()
    }

    // 兼容旧业务 Jenkinsfile，仅允许覆盖非生产凭据。
    if (credValue instanceof Map) {
        def envSpecific = credValue[deployEnv]
        if (envSpecific?.toString()?.trim()) {
            echo "    (来源: Jenkinsfile kubeconfigCredId.${deployEnv})"
            return envSpecific.toString().trim()
        }
        echo "    (cfg.kubeconfigCredId.${deployEnv} 未配置，fallback 到全局变量)"
    }
    // 优先级 2：字符串形式 - 所有环境共用
    else if (credValue instanceof CharSequence && credValue.toString().trim()) {
        echo "    (来源: Jenkinsfile kubeconfigCredId 字符串)"
        return credValue.toString().trim()
    }

    // 旧约定（仅非生产）
    def legacyId = "k8s-${cfg.projectName}-${deployEnv}"
    echo "    (来源: 默认约定 k8s-{project}-{env})"
    return legacyId
}

@NonCPS
def resolveDefaultGitBranch(String configuredDefault) {
    def shortJob = (env?.JOB_NAME ?: '').tokenize('/').last()?.toLowerCase() ?: ''
    if (shortJob.endsWith('-prod')) return env?.PROD_BRANCH ?: 'master'
    if (shortJob.endsWith('-test')) return 'test'
    return configuredDefault ?: 'main'
}

/**
 * 生产发布只允许来自管理员配置的分支。rollback/restart 不依赖源码分支。
 */
def validateProductionRequest(Map cfg, String deployEnv, String action, String requestedRef) {
    if (deployEnv != 'prod') return
    // A single business Jenkinsfile is supported. Explicit gitUrl/gitCredId
    // remains available for the legacy ops-wrapper/monorepo model.
    if (action != 'deploy') return
    def prodBranch = env.PROD_BRANCH?.trim()
    if (!prodBranch) {
        error '未配置 Jenkins 全局环境变量 PROD_BRANCH，拒绝生产部署'
    }
    if (!(prodBranch ==~ /^[A-Za-z0-9._\/-]+$/) || prodBranch.contains('..') || prodBranch.startsWith('/')) {
        error 'Jenkins 全局变量 PROD_BRANCH 格式非法'
    }
    def normalized = (requestedRef ?: '').replaceFirst(/^refs\/heads\//, '').replaceFirst(/^origin\//, '')
    if (normalized != prodBranch) {
        error "生产部署只允许分支 '${prodBranch}'，当前选择: '${requestedRef}'"
    }
}

/**
 * 防止参数显示 master、实际 workspace 却是其他 commit。
 */
def verifyProductionCommit() {
    def prodBranch = env.PROD_BRANCH?.trim()
    def headCommit = sh(script: 'git rev-parse HEAD', returnStdout: true).trim()
    def prodCommit = sh(
        script: "git rev-parse refs/remotes/origin/${prodBranch} 2>/dev/null || git rev-parse refs/heads/${prodBranch}",
        returnStdout: true
    ).trim()
    if (headCommit != prodCommit) {
        error "生产 workspace 不是 origin/${prodBranch} 最新 commit，拒绝部署"
    }
    if (params.IMAGE_TAG?.trim()) {
        def match = params.IMAGE_TAG.trim() =~ /^R([0-9a-f]{40})$/
        if (!match.matches()) {
            error '生产手工 IMAGE_TAG 必须是 R<40位Git SHA>'
        }
        def imageCommit = match[0][1]
        def inHistory = sh(script: "git merge-base --is-ancestor ${imageCommit} HEAD", returnStatus: true) == 0
        if (!inHistory) {
            error "指定镜像 commit ${imageCommit} 不属于 ${prodBranch} 历史，拒绝部署"
        }
    }
    echo "✅ 生产源码校验通过: ${prodBranch}@${headCommit.take(9)}"
}

/**
 * 生产环境人工审批
 */
def prodApproval(Map cfg) {
    def approvalTimeout = env.PROD_APPROVAL_TIMEOUT ?: '60'
    def approvers = env.PROD_APPROVERS?.trim()
    if (!approvers) {
        error '未配置 Jenkins 全局环境变量 PROD_APPROVERS，生产部署默认拒绝'
    }
    if (!(approvalTimeout ==~ /^\d+$/) || approvalTimeout.toInteger() < 1 || approvalTimeout.toInteger() > 1440) {
        error 'PROD_APPROVAL_TIMEOUT 必须是 1-1440 分钟的整数'
    }
    def target = "deployment/${cfg.serviceName}"
    if (params.ACTION == 'deploy') {
        target = "${cfg.dockerRegistry}/${env.IMAGE_PROJECT}/${cfg.dockerImage}:${env.DOCKER_TAG}"
    } else if (params.ACTION == 'rollback') {
        target += " revision=${params.ROLLBACK_REVISION}"
    }

    echo """
╔═══════════════════════════════════════════════════════════════╗
║  ⚠️  生产操作审批                                              ║
╠═══════════════════════════════════════════════════════════════╣
║  Service:  ${cfg.serviceName}
║  Action:   ${params.ACTION}
║  Target:   ${target}
║  Approvers: ${approvers}
╚═══════════════════════════════════════════════════════════════╝
"""

    timeout(time: Integer.parseInt(approvalTimeout), unit: 'MINUTES') {
        input(
            message: "确认在生产环境执行 ${params.ACTION}: ${cfg.serviceName}？",
            ok: "✅ 确认执行",
            submitter: approvers,
            parameters: [
                string(
                    name: 'CHANGE_TICKET',
                    defaultValue: '',
                    description: '变更单号（如有）'
                )
            ]
        )
    }
    echo "✅ 生产操作已批准"
}

/**
 * Gitea Webhook 分支过滤规则
 *
 * 根据 Job 名称后缀自动决定哪些分支的 push 能触发构建：
 *   *-prod  → '^$'（永不匹配，生产只能手动）
 *   *-test  → '^refs/heads/(test|main)$'
 *   *-dev   → '^refs/heads/(dev|develop)$'
 *   无后缀  → '^refs/heads/(test|dev|main)$'
 *
 * 支持业务 Jenkinsfile 自定义：
 *   k8sDeploy(webhookBranches: ['test', 'release/.*'])
 */
def resolveWebhookBranchFilter(Map cfg = [:]) {
    def shortName = (env?.JOB_NAME ?: '').tokenize('/').last()?.toLowerCase() ?: ''
    def suffix = shortName.tokenize('-').last()

    // 生产 Job 永远不自动触发
    if (suffix == 'prod') return '^$'

    // 业务 Jenkinsfile 显式指定了触发分支
    if (cfg.webhookBranches) {
        def pattern = cfg.webhookBranches.collect { "refs/heads/${it}" }.join('|')
        return "^(${pattern})\$"
    }

    // 默认规则
    if (suffix == 'dev')  return '^refs/heads/(dev|develop)$'
    if (suffix == 'test') return '^refs/heads/(test|main)$'
    return '^refs/heads/(test|dev|main)$'
}

