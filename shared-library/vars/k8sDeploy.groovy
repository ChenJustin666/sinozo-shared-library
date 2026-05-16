/**
 * k8sDeploy - K8s 通用部署入口（Helm + values.yaml）
 *
 * ═══ 核心设计 ═══
 * 1. 镜像 Build Once, Deploy Many
 *    每个 commit 只构建 1 次。第一次部署到非 prod 环境时构建，后续环境复用。
 *
 * 2. 多 SWR project 隔离 + Image Promotion
 *    test 和 prod 用不同的 SWR project（如 sinozo-test / sinozo-prod）
 *    prod 部署前自动用 docker tag + push 把镜像从 test project 提升到 prod project
 *    （不重新构建，layer 完全复用）
 *
 * 3. 兼容 1/2/3 环境
 *    - 3 环境 dev/test/prod：dev 构建，test/prod 复用
 *    - 2 环境 test/prod（多数现有服务）：test 构建，prod 复用 + promotion
 *    - 1 环境 prod：报错（不允许，至少要有一个非 prod 环境构建）
 *
 * 4. 字段所有权契约
 *    - 业务 values 在业务仓库 deploy/，运维基线在 k8s-deploy/baselines/
 *    - CI 部署前自动校验
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
    ]

    def cfg = [:]
    defaults.each { k, v -> cfg[k] = config.containsKey(k) ? config[k] : v }

    pipeline {
        agent { label cfg.agent ?: null }

        environment {
            PROJECT_NAME    = "${cfg.projectName}"
            SERVICE_NAME    = "${cfg.serviceName}"
            DOCKER_REGISTRY = "${cfg.dockerRegistry}"
            DEPLOY_BASE_DIR = "/var/lib/jenkins/workspace/deploy/k8s-deploy"
        }

        parameters {
            choice(
                name: 'DEPLOY_ENV',
                choices: ['dev', 'test', 'prod'],
                description: '部署环境（运维在 Job 配置中设定默认值）'
            )
            gitParameter(
                branchFilter: 'origin/(.*)',
                defaultValue: "${cfg.defaultBranch}",
                name: 'GIT_BRANCH',
                type: 'PT_BRANCH_TAG',
                description: '分支或 Tag'
            )
            choice(
                name: 'ACTION',
                choices: ['deploy', 'rollback', 'restart'],
                description: 'deploy=部署 | rollback=回滚 | restart=重启'
            )
            string(
                name: 'IMAGE_TAG',
                defaultValue: '',
                description: '镜像 tag（留空=自动从代码 commit 计算）'
            )
            string(
                name: 'ROLLBACK_REVISION',
                defaultValue: '0',
                description: 'helm 回滚版本号（0=上一个版本）'
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
                        checkPermission(params.DEPLOY_ENV)
                    }
                }
            }

            stage('Checkout 业务代码 + 解析镜像目标') {
                steps {
                    script {
                        // ═══ Checkout 策略 ═══
                        // 优先级 1：用户显式指定 gitUrl/gitCredId（极少见，用于 monorepo 切换仓库）
                        // 优先级 2：使用 Jenkins Job 自身的 SCM 配置（推荐）
                        //          - 此模式下 Jenkinsfile from SCM 会自动配 SCM
                        //          - GIT_BRANCH 参数仍然生效（覆盖 SCM 默认分支）
                        if (cfg.gitUrl?.trim() && cfg.gitCredId?.trim()) {
                            echo "📥 Checkout 显式仓库: ${cfg.gitUrl}"
                            checkout([
                                $class: 'GitSCM',
                                branches: [[name: "${params.GIT_BRANCH}"]],
                                userRemoteConfigs: [[
                                    credentialsId: cfg.gitCredId,
                                    url: cfg.gitUrl
                                ]]
                            ])
                        } else {
                            // 从 Job 的 SCM 配置自动获取 git 信息
                            // Jenkins 在加载 Jenkinsfile 时已注入 scm 对象（含 url、credId）
                            echo "📥 Checkout (从 Job SCM 配置自动获取): branch=${params.GIT_BRANCH}"
                            checkout([
                                $class: 'GitSCM',
                                branches: [[name: "${params.GIT_BRANCH}"]],
                                userRemoteConfigs: scm.userRemoteConfigs,
                                extensions: scm.extensions ?: []
                            ])
                        }

                        // 自动获取 git 信息回填到 cfg（后续 stage 可能需要）
                        cfg.gitUrl = cfg.gitUrl ?: sh(
                            script: 'git config --get remote.origin.url',
                            returnStdout: true
                        ).trim()
                        echo "📥 当前仓库: ${cfg.gitUrl}"

                        env.GIT_COMMIT_SHORT = sh(
                            script: 'git rev-parse --short=9 HEAD',
                            returnStdout: true
                        ).trim()

                        // 镜像 tag
                        if (params.IMAGE_TAG?.trim()) {
                            env.DOCKER_TAG = params.IMAGE_TAG.trim()
                            echo "📌 手动指定的镜像 tag: ${env.DOCKER_TAG}"
                        } else {
                            env.DOCKER_TAG = "R${env.GIT_COMMIT_SHORT}"
                            echo "📌 自动计算镜像 tag: ${env.DOCKER_TAG}"
                        }

                        // 解析 IMAGE_PROJECT（多 SWR project 隔离）
                        def projectMap = readImageProjectsMap()

                        // 当前环境的 project（部署目标）
                        env.IMAGE_PROJECT = projectMap[params.DEPLOY_ENV]
                        if (!env.IMAGE_PROJECT) {
                            error "❌ baselines/_global.yaml 中未配置 image.projects.${params.DEPLOY_ENV}"
                        }

                        // 镜像构建/源头 project
                        // 优先级：dev project > test project > 当前环境
                        // 这保证：有 dev 时镜像构建在 dev project，没 dev 时 test 也用同一个 project（实际是 sinozo-test）
                        env.IMAGE_PROJECT_BUILD = projectMap['dev'] ?: projectMap['test'] ?: env.IMAGE_PROJECT

                        echo "📌 IMAGE_PROJECT (${params.DEPLOY_ENV}): ${env.IMAGE_PROJECT}"
                        echo "📌 IMAGE_PROJECT_BUILD (镜像源): ${env.IMAGE_PROJECT_BUILD}"

                        if (env.IMAGE_PROJECT != env.IMAGE_PROJECT_BUILD) {
                            echo "📌 跨 project 部署：镜像将从 ${env.IMAGE_PROJECT_BUILD} promotion 到 ${env.IMAGE_PROJECT}"
                        }
                    }
                }
            }

            stage('初始化') {
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

            stage('生产部署审批') {
                when {
                    allOf {
                        expression { params.ACTION == 'deploy' }
                        expression { params.DEPLOY_ENV == 'prod' }
                    }
                }
                steps {
                    script {
                        prodApproval(cfg)
                    }
                }
            }

            stage('部署到 K8s') {
                steps {
                    script {
                        def kubeCred = cfg.kubeconfigCredId ?: "k8s-${cfg.projectName}-${params.DEPLOY_ENV}"

                        withCredentials([file(credentialsId: kubeCred, variable: 'KUBECONFIG')]) {
                            withCredentials([usernamePassword(
                                credentialsId: cfg.dockerCredId,
                                usernameVariable: 'DOCKER_USER',
                                passwordVariable: 'DOCKER_PASS'
                            )]) {
                                def authStr = "${env.DOCKER_USER}:${env.DOCKER_PASS}".bytes.encodeBase64().toString()
                                def dockerJson = """{"auths":{"${cfg.dockerRegistry}":{"username":"${env.DOCKER_USER}","password":"${env.DOCKER_PASS}","auth":"${authStr}"}}}"""
                                env.PULL_SECRET_DATA = dockerJson.bytes.encodeBase64().toString()

                                if (cfg.nacosCredId) {
                                    withCredentials([usernamePassword(
                                        credentialsId: cfg.nacosCredId,
                                        usernameVariable: 'NACOS_USER',
                                        passwordVariable: 'NACOS_PASS'
                                    )]) {
                                        env.NACOS_USERNAME = env.NACOS_USER
                                        env.NACOS_PASSWORD = env.NACOS_PASS
                                        deployToK8s(cfg, params.DEPLOY_ENV, params)
                                    }
                                } else {
                                    deployToK8s(cfg, params.DEPLOY_ENV, params)
                                }
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

/**
 * 读取 baselines/_global.yaml 的 image.projects 映射
 */
def readImageProjectsMap() {
    def globalBaseline = "${env.DEPLOY_BASE_DIR}/baselines/_global.yaml"
    if (!fileExists(globalBaseline)) {
        error "❌ 未找到 ${globalBaseline}"
    }
    def yaml = readYaml(file: globalBaseline)
    def projectMap = yaml?.image?.projects
    if (!projectMap || !(projectMap instanceof Map)) {
        error "❌ baselines/_global.yaml 缺少 image.projects 映射"
    }
    return projectMap
}

/**
 * 判断是否需要构建镜像（在 IMAGE_PROJECT_BUILD 中检查同 tag 是否已存在）
 */
def needBuildImage(Map cfg) {
    def fullImage = "${cfg.dockerRegistry}/${env.IMAGE_PROJECT_BUILD}/${cfg.dockerImage}:${env.DOCKER_TAG}"

    echo "🔍 检查镜像是否已存在: ${fullImage}"

    def rc = -1
    withCredentials([usernamePassword(
        credentialsId: cfg.dockerCredId,
        usernameVariable: 'DOCKER_USER',
        passwordVariable: 'DOCKER_PASS'
    )]) {
        rc = sh(
            script: """
                docker login ${cfg.dockerRegistry} -u \$DOCKER_USER -p \$DOCKER_PASS >/dev/null 2>&1
                docker manifest inspect ${fullImage} >/dev/null 2>&1
            """,
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
 * 推送目标：env.IMAGE_PROJECT_BUILD（如 sinozo-test）
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

    withCredentials([usernamePassword(
        credentialsId: cfg.dockerCredId,
        usernameVariable: 'DOCKER_USER',
        passwordVariable: 'DOCKER_PASS'
    )]) {
        def rc = sh(
            script: """
                docker login ${cfg.dockerRegistry} -u \$DOCKER_USER -p \$DOCKER_PASS >/dev/null 2>&1
                docker manifest inspect ${fullImage} >/dev/null 2>&1
            """,
            returnStatus: true
        )

        if (rc != 0) {
            error """❌ 镜像不存在: ${fullImage}

可能原因：
  1. 该 commit 镜像从未构建（没有任何非 prod 环境部署过）
  2. 镜像被清理过期了
  3. 跨 project 但 promotion 阶段失败

修复方法：
  - 先去 ${cfg.serviceName}-test 或 ${cfg.serviceName}-dev Job 部署一次（构建镜像）
  - 或手动指定一个已存在的镜像 tag（参数 IMAGE_TAG）
"""
        }
        echo "✅ 镜像已存在，可部署"
    }
}

/**
 * 生产环境人工审批
 */
def prodApproval(Map cfg) {
    def approvalTimeout = env.PROD_APPROVAL_TIMEOUT ?: '60'
    def approvers = env.PROD_APPROVERS ?: 'admin,ops'

    echo """
╔═══════════════════════════════════════════════════════════════╗
║  ⚠️  生产部署审批                                              ║
╠═══════════════════════════════════════════════════════════════╣
║  Service:  ${cfg.serviceName}
║  Branch:   ${params.GIT_BRANCH}
║  Image:    ${cfg.dockerRegistry}/${env.IMAGE_PROJECT}/${cfg.dockerImage}:${env.DOCKER_TAG}
║  Approvers: ${approvers}
╚═══════════════════════════════════════════════════════════════╝
"""

    timeout(time: Integer.parseInt(approvalTimeout), unit: 'MINUTES') {
        input(
            message: "确认部署 ${cfg.serviceName} 到生产环境？",
            ok: "✅ 确认部署",
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
    echo "✅ 生产部署已批准"
}
