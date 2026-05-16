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
            // DEPLOY_BASE_DIR 在 "解析镜像目标" stage 中自动定位（Library 已 clone 的路径）
            // 不需要运维手动在节点上 clone 仓库
        }

        parameters {
            choice(
                name: 'DEPLOY_ENV',
                choices: inferEnvChoices(),
                description: '部署环境（默认从 Job 名末段自动推断：xxx-dev / xxx-test / xxx-prod）'
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

            stage('解析镜像目标 + 计算 tag') {
                steps {
                    script {
                        // ═══ 自动定位 DEPLOY_BASE_DIR ═══
                        // Jenkins 加载 Shared Library 时已自动 clone 整个仓库到：
                        //   ${WORKSPACE}@libs/<library-name>/
                        // 这个目录里有完整的 baselines/ charts/ automation/，无需运维手动 clone
                        env.DEPLOY_BASE_DIR = resolveDeployBaseDir()
                        echo "📍 DEPLOY_BASE_DIR (自动定位): ${env.DEPLOY_BASE_DIR}"

                        // ═══ Checkout 策略 ═══
                        // Jenkins "Pipeline from SCM" 模式已自动 checkout（Declarative: Checkout SCM）
                        // 默认情况下不重复 checkout，直接复用 workspace
                        //
                        // 只在 2 种特殊场景才需要再 checkout：
                        //   场景 A：用户传了 GIT_BRANCH 参数，且非首次构建（要切到指定分支/tag）
                        //   场景 B：用户显式传了 gitUrl/gitCredId（monorepo 跨仓库部署）
                        def needRecheckout = false
                        def explicitRepo = (cfg.gitUrl?.trim() && cfg.gitCredId?.trim())
                        def explicitBranch = (params.GIT_BRANCH?.trim() && params.GIT_BRANCH != env.BRANCH_NAME && params.GIT_BRANCH != 'main' && params.GIT_BRANCH != 'master')

                        if (explicitRepo) {
                            echo "📥 Checkout 显式仓库: ${cfg.gitUrl} (branch=${params.GIT_BRANCH})"
                            checkout([
                                $class: 'GitSCM',
                                branches: [[name: "${params.GIT_BRANCH}"]],
                                userRemoteConfigs: [[
                                    credentialsId: cfg.gitCredId,
                                    url: cfg.gitUrl
                                ]]
                            ])
                        } else if (explicitBranch) {
                            echo "📥 切换到用户指定分支/tag: ${params.GIT_BRANCH}"
                            checkout([
                                $class: 'GitSCM',
                                branches: [[name: "${params.GIT_BRANCH}"]],
                                userRemoteConfigs: scm.userRemoteConfigs,
                                extensions: scm.extensions ?: []
                            ])
                        } else {
                            echo "📥 复用 Jenkins SCM 已 checkout 的 workspace（分支: ${env.BRANCH_NAME ?: 'auto'}）"
                        }

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
 * 从 Job 名末段自动推断默认 DEPLOY_ENV，并把它放在 choices 第一位
 *
 * 规则：
 *   - Job 名以 -dev / -test / -prod / -staging / -uat / -pre 结尾 → 用对应环境作默认
 *   - 不匹配 → 用 'test' 作默认（多数情况）
 *
 * 例：
 *   ad-gateway-dev   → ['dev', 'test', 'prod', ...]
 *   ad-gateway-test  → ['test', 'dev', 'prod', ...]
 *   ad-gateway-prod  → ['prod', 'test', 'dev', ...]
 *   ad-gateway       → ['test', 'dev', 'prod', ...]   （无后缀，默认 test）
 */
@NonCPS
def inferEnvChoices() {
    def allEnvs = ['dev', 'test', 'prod']    // 候选环境，可按需扩展
    def jobName = (env?.JOB_NAME ?: '').toLowerCase()

    // 取 Job 名最后一段（去掉 folder 路径前缀）
    def shortName = jobName.tokenize('/').last()

    // 提取末段（最后一个 - 之后）
    def suffix = shortName.tokenize('-').last()

    // 如果末段命中已知环境，把它移到第一位
    if (allEnvs.contains(suffix)) {
        def reordered = [suffix] + allEnvs.findAll { it != suffix }
        return reordered
    }

    // 不匹配，用 test 作默认（最常见场景）
    return ['test', 'dev', 'prod']
}

/**
 * 自动定位 Library 已 clone 的根目录（含 baselines/ charts/ automation/）
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
    // 优先：环境变量显式指定（高级场景或本地测试）
    if (env.K8S_DEPLOY_DIR?.trim() && fileExists("${env.K8S_DEPLOY_DIR}/baselines/_global.yaml")) {
        return env.K8S_DEPLOY_DIR.trim()
    }

    // 自动查找 ${WORKSPACE}@libs/ 下的 Library clone 目录
    def libsRoot = "${env.WORKSPACE}@libs"
    if (fileExists(libsRoot)) {
        // 列出 @libs/ 下所有目录，找到含 baselines/_global.yaml 的那个
        def candidates = sh(
            script: """find ${libsRoot} -maxdepth 3 -type f -name '_global.yaml' -path '*/baselines/_global.yaml' 2>/dev/null | head -5""",
            returnStdout: true
        ).trim().split('\n').findAll { it }

        if (candidates) {
            // 取第一个匹配，去掉 /baselines/_global.yaml 得到根目录
            def found = candidates[0].replaceFirst('/baselines/_global\\.yaml$', '')
            echo "✅ 自动定位到 Library clone 路径"
            return found
        }
    }

    // 兜底：传统路径（运维手动 clone 的方式）
    def legacyPath = "/var/lib/jenkins/workspace/deploy/k8s-deploy"
    if (fileExists("${legacyPath}/baselines/_global.yaml")) {
        echo "ℹ️  使用兼容路径（建议升级到自动定位）: ${legacyPath}"
        return legacyPath
    }

    error """❌ 找不到 k8s-deploy 仓库（baselines/charts/automation 等运行时资产）

期望的位置：
  1. \${WORKSPACE}@libs/k8s-deploy-lib/         (Library 自动 clone)
  2. /var/lib/jenkins/workspace/deploy/k8s-deploy/  (运维手动 clone)
  3. \$K8S_DEPLOY_DIR 环境变量指定的目录

排查：
  - 确认 Jenkins 全局配置的 Shared Library 名称是 'k8s-deploy-lib'
  - 确认 Library Path 配置为 'shared-library'
  - 检查 \${WORKSPACE}@libs/ 目录内容
"""
}

/**
 * 读取 baselines/_global.yaml 的 image.projects 映射
 *
 * 不依赖 Pipeline Utility Steps 插件（readYaml）
 * 用 awk 解析 YAML 中 image.projects 这一段（简单 key-value 结构）
 *
 * 期望的 YAML 片段：
 *   image:
 *     projects:
 *       dev:  sinozo-test
 *       test: sinozo-test
 *       prod: sinozo-prod
 */
def readImageProjectsMap() {
    def globalBaseline = "${env.DEPLOY_BASE_DIR}/baselines/_global.yaml"
    if (!fileExists(globalBaseline)) {
        error "❌ 未找到 ${globalBaseline}"
    }

    // 优先用 python3 + PyYAML（精确）
    // 兜底用 awk（简单 key-value 解析，适合扁平结构）
    // 输出格式：每行 "key=value"
    def kvPairs = sh(
        script: """
set -e
F='${globalBaseline}'

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

    if (projectMap.isEmpty()) {
        error """❌ baselines/_global.yaml 中未解析到 image.projects 映射

期望的格式：
  image:
    projects:
      dev:  sinozo-test
      test: sinozo-test
      prod: sinozo-prod

实际文件: ${globalBaseline}
"""
    }

    echo "📌 解析到 image.projects 映射: ${projectMap}"
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
