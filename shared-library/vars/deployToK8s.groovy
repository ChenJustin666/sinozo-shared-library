/**
 * 部署到 K8s（Helm upgrade --install）
 *
 * ═══ 合并机制 ═══
 * Helm `-f` 后置覆盖，加载顺序：
 *   ① charts/generic-service/values.yaml          (Chart 默认值)
 *   ② baselines/_global.yaml                      (全局基线 / 运维管 / 必加载)
 *   ③ <业务仓库>/deploy/values.yaml                (业务通用 / 开发管)
 *   ④ values 环境差异（按 deployEnv 分流）：
 *      - test: <业务仓库>/deploy/values-test.yaml          (开发管)
 *      - prod: <运维仓库>/baselines/projects/<proj>/<svc>/values-prod.yaml  (运维管)
 *   ⑤ helm --set image.tag=R<commit> ...          (CI 注入)
 *
 * ═══ 关键设计：开发改不了 prod ═══
 * - test 环境：开发在自己业务仓库写，自由调
 * - prod 环境：业务仓库的 deploy/values-prod.yaml【根本不读】
 *              运维在运维仓库 baselines/projects/ 下管控，开发无 push 权限
 * - 物理隔离：每服务一个独立文件，互不影响
 *
 * ═══ 旧路径兼容 ═══
 * 业务仓库未提供 deploy/values-test.yaml 时回退到：
 *   projects/{project}/{env}/{svc}/values-{env}.yaml
 *
 * ═══ 支持的 Action ═══
 *   - deploy   : helm upgrade --install + 健康检查
 *   - rollback : helm rollback
 *   - restart  : kubectl rollout restart
 */

def call(Map config, String deployEnv, def params) {
    def baseDir     = env.DEPLOY_BASE_DIR
    def chartPath   = "${baseDir}/charts/generic-service"
    def namespace   = resolveNamespace(config, deployEnv, baseDir)
    def releaseName = config.serviceName
    def skipHealth  = params.SKIP_HEALTH_CHECK ?: false


    if (params.ACTION == 'deploy') {
        deploy(config, deployEnv, chartPath, namespace, releaseName, skipHealth, baseDir)
    } else if (params.ACTION == 'rollback') {
        rollback(releaseName, namespace, params)
    } else if (params.ACTION == 'restart') {
        restart(releaseName, namespace)
    }
}

/**
 * 部署
 */
def deploy(Map config, String deployEnv, String chartPath,
           String namespace, String releaseName, boolean skipHealth, String baseDir) {

    // 解析 values 文件链
    def valuesChain = resolveValuesChain(config, deployEnv, baseDir)

    // ── prod 不强制要求业务 values（设计：开发碰不到 prod，全走运维仓库）──
    // ── 非 prod (test/dev) 必须要有业务 values（业务方在自己仓库 deploy/ 下管理）──
    if (deployEnv != 'prod' && valuesChain.businessFiles.isEmpty()) {
        error """❌ 找不到业务 values 文件（${deployEnv} 环境必须）
请在以下任一位置创建：
  新模式（推荐）：业务仓库根目录的 deploy/values-${deployEnv}.yaml
  旧模式（兼容）：${baseDir}/projects/${config.projectName}/${deployEnv}/${config.serviceName}/values-${deployEnv}.yaml
"""
    }


    echo "🚀 部署: ${releaseName} → ${namespace} (tag: ${env.DOCKER_TAG})"
    echo "📋 模式: ${valuesChain.mode}"

    // 校验业务 values（warning 模式，不阻塞 - 先跑通流程）
    // 后续上稳定后可改为 prod=strict
    validateBusinessValues(valuesChain.businessFiles, baseDir, false)


    // 构建 helm 命令
    def helmCmd = buildHelmCommand(config, chartPath, valuesChain.allFiles,
                                   namespace, releaseName)

    // CI 注入：Docker pull secret
    if (env.PULL_SECRET_DATA) {
        helmCmd += " --set image.createPullSecret=true"
        helmCmd += " --set image.pullSecretData=${env.PULL_SECRET_DATA}"
    }

    // CI 注入：Nacos 凭据
    if (env.NACOS_USERNAME && env.NACOS_PASSWORD) {
        helmCmd += " --set nacos.username=${env.NACOS_USERNAME}"
        helmCmd += " --set nacos.password=${env.NACOS_PASSWORD}"
    }

    // 部署前预览（test/prod 都跑）
    //   1. helm template 渲染完整 manifest 备份到 ~/.deploy-previews/<env>/<svc>-<tag>-<ts>.yaml
    //   2. console 打印前 100 行（运维快速看，全文在备份文件里）
    //   3. 30 天前的备份自动清理
    previewConfig(chartPath, valuesChain.allFiles, releaseName, deployEnv)

    // ── prod 部署前 helm diff 预览（显示本次变更内容）──
    if (deployEnv == 'prod') {
        helmDiff(chartPath, valuesChain.allFiles, releaseName, namespace)
    }

    // 部署前 adopt 既有的资源（手工创建的没有 Helm 标签，会冲突）
    // 把可能预先存在的同名资源都打上 Helm 标签让 chart 接管
    //
    // ── prod 智能跳过策略 ──
    //   - release 已存在（helm status 成功）→ 跳过 adopt（资源已被 Helm 管理，节省 10+ 次 kubectl）
    //   - release 不存在（首次部署）或运维手工改过资源 → 必须跑 adopt（防止冲突）
    //   - test/dev 永远跑 adopt（手工调试多，资源可能被手动改）
    def kf = env.KUBECONFIG ? "--kubeconfig ${env.KUBECONFIG}" : ""
    def releaseExists = false
    if (deployEnv == 'prod') {
        releaseExists = sh(
            script: "sudo /usr/local/bin/helm status ${releaseName} -n ${namespace} ${kf} >/dev/null 2>&1",
            returnStatus: true
        ) == 0
    }

    if (releaseExists) {
        echo "  ⏭️  prod release 已存在，跳过 adoptExistingResource（资源已被 Helm 管理）"
    } else {
        adoptExistingResource('secret', 'regcred',                  namespace, releaseName)
        adoptExistingResource('service', "${releaseName}-svc",      namespace, releaseName)
        adoptExistingResource('service', releaseName,               namespace, releaseName)
        adoptExistingResource('deployment', releaseName,            namespace, releaseName)
        adoptExistingResource('configmap', "${releaseName}-config", namespace, releaseName)
    }


    sh helmCmd


    if (!skipHealth) {
        healthCheck(releaseName, namespace)
    }
}

/**
 * 解析 values 文件链
 *
 * 返回:
 *   [
 *     mode:           'new' | 'legacy',
 *     allFiles:       [..., ..., ...],   // 全部要传给 helm 的 -f 文件
 *     businessFiles:  [..., ...],         // 业务方提供的 values（用于校验）
 *   ]
 */
def resolveValuesChain(Map config, String deployEnv, String baseDir) {
    def files = []
    def businessFiles = []
    def mode = 'unknown'

    // ── 1. 全局基线（必加载，存在则用）──
    def globalBaseline = "${baseDir}/baselines/_global.yaml"
    if (fileExists(globalBaseline)) {
        files << globalBaseline
        echo "  📎 全局基线: baselines/_global.yaml"
    } else {
        echo "  ⚠️  未找到 baselines/_global.yaml，跳过"
    }

    // ── 1.5. 项目级覆盖（可选，存在则覆盖全局）──
    // 用途：项目要自定义 SWR registry / project 命名 / pullSecret / namespace 等
    //       基础设施时，运维在 baselines/projects/<project>/_overrides.yaml 写覆盖项。
    //       默认不存在 → 完全继承 _global.yaml（90% 项目场景）
    def projectOverrides = "${baseDir}/baselines/projects/${config.projectName}/_overrides.yaml"
    if (fileExists(projectOverrides)) {
        files << projectOverrides
        echo "  📎 项目级覆盖: baselines/projects/${config.projectName}/_overrides.yaml"
    }

    // ── 2. 业务通用（业务仓库 deploy/values.yaml）──

    // 所有环境都加载，业务方写公共配置（端口、健康检查、JVM 默认）
    def businessDir = config.subdirectory ? "${env.WORKSPACE}/${config.subdirectory}/deploy" : "${env.WORKSPACE}/deploy"
    def businessCommon = "${businessDir}/values.yaml"
    if (fileExists(businessCommon)) {
        files << businessCommon
        businessFiles << businessCommon
        echo "  📎 业务通用: deploy/values.yaml"
    }

    // ── 3. 环境差异 ──
    if (deployEnv == 'prod') {
        // ⭐ 生产环境：从【运维仓库】读取，开发碰不到
        // 路径: baselines/projects/<project>/<service>/values-prod.yaml
        def prodValues = "${baseDir}/baselines/projects/${config.projectName}/${config.serviceName}/values-prod.yaml"
        if (fileExists(prodValues)) {
            mode = 'prod-ops-managed'
            files << prodValues
            echo "  📎 生产配置（运维仓库）: baselines/projects/${config.projectName}/${config.serviceName}/values-prod.yaml"
        } else {
            // prod 配置不存在 → 自动从业务 test values 生成 prod 模板 + 尝试 push
            // 不管 push 是否成功，本次部署都 fail，等运维 review 后再触发
            autoGenerateProdValues(config, baseDir, businessDir)
            error generateProdReviewMessage(config, baseDir)
        }
    } else {

        // test / dev：从业务仓库读
        def businessEnv = "${businessDir}/values-${deployEnv}.yaml"
        if (fileExists(businessEnv)) {
            mode = 'new'
            files << businessEnv
            businessFiles << businessEnv
            echo "  📎 业务环境: deploy/values-${deployEnv}.yaml"
        } else {
            // 兼容旧路径
            def legacyValues = "${baseDir}/projects/${config.projectName}/${deployEnv}/${config.serviceName}/values-${deployEnv}.yaml"
            if (fileExists(legacyValues)) {
                mode = 'legacy'
                files << legacyValues
                businessFiles << legacyValues
                echo "  📎 旧模式 values: projects/${config.projectName}/${deployEnv}/${config.serviceName}/values-${deployEnv}.yaml"
                echo "  💡 建议迁移：将 values 挪到业务仓库 deploy/values-${deployEnv}.yaml"
            }
        }
    }

    return [
        mode:          mode,
        allFiles:      files,
        businessFiles: businessFiles,
    ]
}

/**
 * 自动生成 prod values 文件（基于业务 deploy/values-test.yaml + prod 增量调整）
 *
 * 流程:
 *   1. 在 agent 本地的 ${baseDir}（已经是 git clone 的运维仓库）创建文件
 *   2. 内容 = 业务 test values 的副本 + 文件头自动加入 prod 默认调整建议（注释形式）
 *   3. 尝试 git commit + push（用 K8S_DEPLOY_LIB_CRED 凭据）
 *      - 成功 → 运维 review main 分支这次 commit
 *      - 失败（无写权限/冲突）→ console 打印完整内容，运维手工 cp 到运维仓库 push
 *   4. 不管 push 成功与否，本次部署都 fail（运维必须 review 一遍才能放行）
 *
 * 安全设计：
 *   - 这是【模板】，运维必须 review 后才会真正生效（CI 不会用本次生成的文件直接部署）
 *   - 副本/资源/JVM 等关键字段保留 test 默认值，运维必须按实际改
 */
def autoGenerateProdValues(Map config, String baseDir, String businessDir) {
    def proj = config.projectName
    def svc  = config.serviceName
    def prodDir  = "${baseDir}/baselines/projects/${proj}/${svc}"
    def prodFile = "${prodDir}/values-prod.yaml"
    def testFile = "${businessDir}/values-test.yaml"

    if (!fileExists(testFile)) {
        // 业务 test 文件都没有，没法自动生成；交给后续报错
        echo "⚠️  业务 deploy/values-test.yaml 不存在，跳过 prod values 自动生成"
        return
    }

    echo ""
    echo "🤖 检测到 prod values 不存在，自动生成模板..."
    echo "    源文件: ${testFile}"
    echo "    目标:   ${prodFile}"

    // 1. 拷贝 test → prod，并在文件头加上 prod 注释提示
    sh """
        set -e
        mkdir -p '${prodDir}'
        cat > '${prodFile}' <<'PRODHEADER'
# ============================================================
# 生产配置 - ${proj}/${svc}   Owner: 运维
# 路径: baselines/projects/${proj}/${svc}/values-prod.yaml
#
# ⚠️  CI 自动生成（基于业务 deploy/values-test.yaml）
#     必须 review 以下字段后再放行 prod 部署：
#       - service.replicas    (test=1, prod 建议 ≥ 2)
#       - resources.limits    (按压测结果填，避免被 OOM)
#       - resources.requests  (注意 namespace ResourceQuota)
#       - java.opts           (Nacos 改成 prod 地址 + JVM heap 加大)
#       - env                 (LOG_LEVEL=info / API_BASE_URL prod)
#       - probes              (建议先 tcp 跑稳再换 http)
#       - hpa / pdb           (生产建议都开)
#       - affinity            (多副本互斥，podAntiAffinity)
#
# 改完后:
#   cd <运维仓库>
#   git add baselines/projects/${proj}/${svc}/
#   git commit -m "ops: ${svc} prod 配置 review"
#   git push origin main
#   然后 Jenkins ${svc}-prod 重新触发
# ============================================================

PRODHEADER

        # 把 test values 的内容追加（去掉 test 文件原本的 # ===  Owner: 开发  === 那种文件头）
        cat '${testFile}' >> '${prodFile}'
        echo "  ✓ 已生成: ${prodFile}"
    """

    // 2. 尝试 git commit + push（凭据用 agent clone 时用的同一个）
    def libCred = env.K8S_DEPLOY_LIB_CRED?.trim() ?: 'github-token-justin'
    def libBranch = env.K8S_DEPLOY_LIB_BRANCH?.trim() ?: 'main'

    def pushResult = -1
    try {
        withCredentials([usernamePassword(credentialsId: libCred,
                                           usernameVariable: 'GIT_USER',
                                           passwordVariable: 'GIT_PASS')]) {
            pushResult = sh(
                script: """
                    set +e
                    cd '${baseDir}'
                    git config user.name  'jenkins-ci' 2>/dev/null
                    git config user.email 'jenkins@ci.local' 2>/dev/null
                    git config credential.helper '!f() { echo username=\$GIT_USER; echo password=\$GIT_PASS; }; f' 2>/dev/null

                    git add 'baselines/projects/${proj}/${svc}/values-prod.yaml'
                    git commit -m '[ci-auto] ${proj}/${svc}: 自动生成 prod values 模板（待运维 review）'
                    if [ \$? -ne 0 ]; then
                        echo "  ⚠️  没有变更可提交（可能此前有人已 push 过）"
                        git config --unset credential.helper 2>/dev/null
                        exit 99
                    fi

                    git pull --rebase origin '${libBranch}' 2>&1 || true
                    git push origin HEAD:'${libBranch}'
                    rc=\$?
                    git config --unset credential.helper 2>/dev/null
                    exit \$rc
                """,
                returnStatus: true
            )
        }
    } catch (e) {
        echo "  ⚠️  git push 异常: ${e.message}"
        pushResult = -1
    }

    if (pushResult == 0) {
        echo "✅ prod values 模板已自动 push 到运维仓库 main 分支"
        echo "    运维仓库 commit: [ci-auto] ${proj}/${svc}: 自动生成 prod values 模板（待运维 review）"
    } else {
        echo "⚠️  自动 push 失败（rc=${pushResult}），可能原因："
        echo "      - 凭据 ${libCred} 无 push 权限"
        echo "      - 远端有新 commit，rebase 冲突"
        echo "      - 网络问题"
        echo ""
        echo "    完整文件内容已打印到 console，运维参考下方命令手工补：" 

        // push 失败时把完整文件打到 console，运维直接复制
        echo ""
        echo "════════════════ 📄 ${prodFile} 完整内容 ════════════════"
        sh "cat '${prodFile}' || true"
        echo "═══════════════════════════════════════════════════════════════════"
        echo ""
        echo "运维手工补操作（在运维仓库执行）："
        echo "    cd <运维仓库>"
        echo "    mkdir -p baselines/projects/${proj}/${svc}"
        echo "    vi baselines/projects/${proj}/${svc}/values-prod.yaml   # 复制上面 console 内容"
        echo "    git pull --rebase origin main"
        echo "    git add baselines/projects/${proj}/${svc}/"
        echo "    git commit -m 'ops: ${svc} prod values 模板（手工补 + review）'"
        echo "    git push origin main"
        echo ""
    }

}

/**
 * 生成 prod 部署 fail 时的引导信息（部署被拦下来等运维 review）
 */
def generateProdReviewMessage(Map config, String baseDir) {
    def proj = config.projectName
    def svc  = config.serviceName
    def relPath = "baselines/projects/${proj}/${svc}/values-prod.yaml"

    return """
╔══════════════════════════════════════════════════════════════════════╗
║  ⏸  生产部署已暂停（首次接入 prod）                                    
╠══════════════════════════════════════════════════════════════════════╣
║                                                                        
║  CI 已自动生成 prod values 模板（基于业务 test values）：             
║      ${relPath}
║                                                                        
║  运维 review 流程：                                                    
║                                                                        
║  ① 检查文件是否已 push 到运维仓库 main 分支                            
║     git fetch origin main                                              
║     git log --oneline origin/main | head -3                            
║     # 看到 [ci-auto] ${svc} 那次 commit 即代表自动 push 成功           
║     # 没看到 → 从 console 复制完整内容手工创建文件                     
║                                                                        
║  ② 必改字段 review                                                     
║     vi ${relPath}
║     必须确认：                                                         
║       - service.replicas    (建议 ≥ 2)                                  
║       - resources.limits    (压测后填)                                 
║       - java.opts Nacos 改 prod                                        
║       - env LOG_LEVEL=info                                             
║       - hpa / pdb / affinity 按需开                                    
║                                                                        
║  ③ commit + push                                                       
║     git add baselines/projects/${proj}/${svc}/                       
║     git commit -m "ops: ${svc} prod 配置 review"                       
║     git push origin main                                               
║                                                                        
║  ④ 重新触发 Jenkins ${svc}-prod                                        
║                                                                        
╠══════════════════════════════════════════════════════════════════════╣
║  💡 后续部署：                                                         
║     - prod values 文件存在 → 直接部署（不再触发自动生成）              
║     - 想改 prod 配置 → 编辑 ${relPath} 后 push                         
║     - prod 改动【不会】影响其他服务（每服务独立文件）                  
╚══════════════════════════════════════════════════════════════════════╝
"""
}



/**
 * 构建 helm upgrade 命令
 */
def buildHelmCommand(Map config, String chartPath, List valuesFiles,
                     String namespace, String releaseName) {
    def cmd = "sudo /usr/local/bin/helm upgrade --install ${releaseName} ${chartPath}"

    valuesFiles.each { f ->
        cmd += " -f ${f}"
    }

    cmd += " -n ${namespace}"
    cmd += " --create-namespace"

    // 镜像 tag（CI 计算）
    cmd += " --set image.tag=${env.DOCKER_TAG}"

    // 镜像名（含 SWR project 前缀）：如 sinozo-test/ad-gateway 或 sinozo-prod/ad-gateway
    // env.IMAGE_PROJECT 由 k8sDeploy.groovy 根据 DEPLOY_ENV 注入
    if (env.IMAGE_PROJECT && config.dockerImage) {
        def fullImageName = "${env.IMAGE_PROJECT}/${config.dockerImage}"
        cmd += " --set image.name=${fullImageName}"
    }

    // ── CI 注入字段（业务 / 运维 values 都不要写）──
    // service.name      = 服务名（chart 用它生成 Deployment / Service / ConfigMap 名）
    // service.namespace = {project}-{env} 自动推断
    // project           = 项目名（写到 label / annotation）
    // environment       = 环境名（写到 label / annotation）
    cmd += " --set service.name=${releaseName}"
    cmd += " --set service.namespace=${namespace}"
    cmd += " --set project=${config.projectName}"
    cmd += " --set environment=${env.DEPLOY_ENV}"


    cmd += " --wait --timeout 300s"

    if (env.KUBECONFIG) {
        cmd += " --kubeconfig ${env.KUBECONFIG}"
    }

    return cmd
}

/**
 * 校验业务 values 不含运维字段（warning 模式）
 *
 * @param strict  生产环境时建议 strict=true，违反时直接 error
 */
def validateBusinessValues(List businessFiles, String baseDir, boolean strict) {
    def validator = "${baseDir}/automation/values-validate.sh"
    if (!fileExists(validator)) {
        echo "  ⚠️  values-validate.sh 不存在，跳过字段校验"
        return
    }

    businessFiles.each { f ->
        def rc = sh(script: "bash ${validator} ${f}", returnStatus: true)
        if (rc != 0 && strict) {
            error "❌ 业务 values 字段越界：${f}（详见上方报告）"
        }
    }
}

/**
 * Adopt 既有的 K8s 资源，加上 Helm 标签让 chart 接管
 *
 * 场景：
 *   1. 老服务/手工 kubectl 创建的资源没有 Helm 元数据
 *   2. 阿里云 ACK 等托管 K8s 在创建新 namespace 时会自动注入 regcred
 *   3. 之前用 kubectl apply 部署过的服务（Service / Deployment 等）
 *
 *   Helm 都会报 "cannot be imported: missing key managed-by"
 *
 * 流程：
 *   1. 先确保 namespace 存在（如果不存在，创建 + 等 admission controller 完成注入）
 *   2. 检查资源是否存在；不存在直接返回
 *   3. 检查是否已被 Helm 管理；已管理直接返回（幂等，不重复 patch）
 *   4. patch 标签 + annotation 让 Helm 接管
 *
 * 性能：
 *   - 已 adopt 过的资源会跳过 patch（只查询不修改），无副作用
 *   - 资源不存在时也只查询一次直接返回
 *
 * 安全：只 patch 标签 + annotation，不动数据。
 *
 * @param kind        资源类型: secret / service / deployment / configmap / statefulset
 * @param resourceName 资源名（如 ad-gateway, ad-gateway-svc, regcred）
 * @param namespace   目标 namespace
 * @param releaseName Helm release 名（写入 annotation）
 */
def adoptExistingResource(String kind, String resourceName, String namespace, String releaseName) {
    def kf = env.KUBECONFIG ? "--kubeconfig=${env.KUBECONFIG}" : ""

    // 缓存 kubectl 路径在 env，避免每次都重复 resolveKubectl
    if (!env.KUBECTL_CMD) {
        def kctl = resolveKubectl()
        if (!kctl) {
            // 缓存失败状态，避免后续重复探测
            env.KUBECTL_CMD = '__NONE__'
        } else {
            env.KUBECTL_CMD = kctl
        }
    }
    if (env.KUBECTL_CMD == '__NONE__') {
        return  // kubectl 不可用，跳过 adopt（首次已经打过 warning）
    }
    def kctl = env.KUBECTL_CMD

    // 1. 确保 namespace 存在（只在第一次资源 adopt 时创建）
    if (!env.NS_ENSURED?.contains(namespace)) {
        def nsExists = sh(
            script: "${kctl} get namespace ${namespace} ${kf} >/dev/null 2>&1",
            returnStatus: true
        )
        if (nsExists != 0) {
            echo "  📦 创建 namespace: ${namespace}（提前创建以触发 admission 注入）"
            sh "${kctl} create namespace ${namespace} ${kf}"
            // 等 admission controller 完成 regcred 自动注入（阿里云 ACK 等）
            sh "sleep 3"
        }
        env.NS_ENSURED = (env.NS_ENSURED ?: '') + ',' + namespace
    }

    // 2. 检查资源是否存在
    def exists = sh(
        script: "${kctl} get ${kind} ${resourceName} -n ${namespace} ${kf} >/dev/null 2>&1",
        returnStatus: true
    )
    if (exists != 0) {
        // 不存在 = 不需要 adopt（Helm 会自动创建）；静默处理避免日志吵
        return
    }

    // 3. 检查是否已被 Helm 管理（已管理直接跳过，幂等）
    def alreadyAdopted = sh(
        script: """${kctl} get ${kind} ${resourceName} -n ${namespace} ${kf} \
                   -o jsonpath='{.metadata.labels.app\\.kubernetes\\.io/managed-by}' 2>/dev/null""",
        returnStdout: true
    ).trim()
    if (alreadyAdopted == 'Helm') {
        echo "  ✓ ${kind}/${resourceName} 已被 Helm 管理（跳过）"
        return
    }

    // 4. patch 标签 + annotation 让 Helm 接管
    echo "  🔧 Adopt: ${kind}/${resourceName} → Helm release '${releaseName}'"
    sh """
        ${kctl} label ${kind} ${resourceName} -n ${namespace} ${kf} \
            app.kubernetes.io/managed-by=Helm --overwrite >/dev/null
        ${kctl} annotate ${kind} ${resourceName} -n ${namespace} ${kf} \
            meta.helm.sh/release-name=${releaseName} \
            meta.helm.sh/release-namespace=${namespace} --overwrite >/dev/null
    """
}


/**
 * 解析 namespace（支持项目级覆盖，否则用默认 {project}-{env}）
 *
 * 优先级:
 *   1. baselines/projects/<project>/_overrides.yaml 的 namespaces.<env>  (运维显式配)
 *   2. ${project}-${env}                                                 (默认约定)
 *
 * 用途：兼容历史命名（如 adv 项目的 namespace 是 adv-ops-test 而非 adv-test）
 *
 * @return 实际 namespace 字符串
 */
def resolveNamespace(Map config, String deployEnv, String baseDir) {
    def projectOverrides = "${baseDir}/baselines/projects/${config.projectName}/_overrides.yaml"
    if (fileExists(projectOverrides)) {
        def ns = parseYamlNestedKey(projectOverrides, 'namespaces', deployEnv)
        if (ns) {
            echo "  📌 namespace (项目级覆盖): ${ns}"
            return ns
        }
    }
    def defaultNs = "${config.projectName}-${deployEnv}"
    echo "  📌 namespace (默认约定): ${defaultNs}"
    return defaultNs
}

/**
 * 解析 YAML 中嵌套两层的 key（如 namespaces.test 或 image.projects）
 * 优先用 python3 + PyYAML，兜底 awk 状态机。
 *
 * @param file     YAML 文件路径
 * @param parent   父 key（如 'namespaces'）
 * @param child    子 key（如 'test'）
 * @return 字符串值，找不到返回 null
 */
def parseYamlNestedKey(String file, String parent, String child) {
    def value = sh(
        script: """
set +e
F='${file}'
PARENT='${parent}'
CHILD='${child}'

# 方案 1: python3 + yaml（精确）
if command -v python3 >/dev/null 2>&1 && python3 -c 'import yaml' 2>/dev/null; then
    python3 - "\$F" "\$PARENT" "\$CHILD" <<'PYEOF'
import sys, yaml
with open(sys.argv[1]) as f:
    data = yaml.safe_load(f) or {}
v = (data.get(sys.argv[2]) or {}).get(sys.argv[3])
if v is not None:
    print(v)
PYEOF
    exit 0
fi

# 方案 2: awk 兜底
awk -v parent="\$PARENT" -v child="\$CHILD" '
BEGIN { in_block=0 }
\$0 ~ "^"parent":[[:space:]]*\$"  { in_block=1; next }
in_block && /^[a-zA-Z_]/          { in_block=0 }
in_block && \$0 ~ "^[[:space:]]+"child"[[:space:]]*:" {
    line = \$0
    sub(/^[[:space:]]+/, "", line)
    sub(/[[:space:]]*#.*\$/, "", line)
    colon = index(line, ":")
    if (colon > 0) {
        v = substr(line, colon+1)
        gsub(/^[[:space:]]+|[[:space:]]+\$/, "", v)
        gsub(/^["'"'"']|["'"'"']\$/, "", v)
        if (v != "") { print v; exit }
    }
}
' "\$F"
        """,
        returnStdout: true
    ).trim()
    return value ?: null
}

/**
 * 找出可用的 kubectl 路径
 * Jenkins agent 上 jenkins 用户可能没把 /usr/local/bin 加进 PATH，
 * 优先尝试常见路径 + sudo（跟 helm 调用方式保持一致）
 */
def resolveKubectl() {

    def candidates = [
        "sudo /usr/local/bin/kubectl",
        "sudo /usr/bin/kubectl",
        "/usr/local/bin/kubectl",
        "/usr/bin/kubectl",
        "kubectl",
    ]
    for (c in candidates) {
        def rc = sh(script: "${c} version --client >/dev/null 2>&1", returnStatus: true)
        if (rc == 0) {
            echo "  🔧 使用 kubectl: ${c}"
            return c
        }
    }
    return null
}



/**
 * 部署前预览（test/prod 都跑）
 *
 * 流程：
 *   1. helm template 把整个 manifest 渲染出来
 *   2. 完整文件备份到 ~/.deploy-previews/<env>/<svc>-<tag>-<timestamp>.yaml
 *   3. console 打印前 100 行（运维快速看，全文在备份文件里）
 *   4. 自动清理 30 天前的旧备份（cleanup 是 best effort，失败不阻塞）
 *
 * 备份位置：
 *   ~/.deploy-previews/<env>/<svc>-<tag>-<YYYYMMDDHHMMSS>.yaml
 *   - 跨 Job 共享（agent 用户家目录）
 *   - 不 git push（只服务器本地保留）
 *   - 30 天自动清理
 *
 * 注意：失败不阻塞部署 (|| true)。这是辅助功能不影响核心。
 */
def previewConfig(String chartPath, List valuesFiles, String releaseName, String deployEnv) {
    def fArgs = valuesFiles.collect { "-f ${it}" }.join(' ')
    def previewDir = "\$HOME/.deploy-previews/${deployEnv}"
    def tag = env.DOCKER_TAG ?: 'unknown'
    def ts  = new Date().format('yyyyMMddHHmmss')
    def previewFile = "${previewDir}/${releaseName}-${tag}-${ts}.yaml"

    echo ""
    echo "📊 部署配置预览（helm template 渲染整个 manifest）"

    sh """
        set +e
        mkdir -p '${previewDir}'

        # 渲染完整 manifest 到备份文件（同时把 helm 命令注入的关键字段也带上，跟实际部署一致）
        sudo /usr/local/bin/helm template ${releaseName} ${chartPath} ${fArgs} \\
            --set service.name=${releaseName} \\
            --set service.namespace=${env.SERVICE_NAMESPACE ?: ''} \\
            --set image.tag=${tag} \\
            > '${previewFile}' 2>&1

        if [ -s '${previewFile}' ]; then
            echo ""
            echo "📁 完整 Manifest 已备份: ${previewFile}"
            echo "   行数: \$(wc -l < '${previewFile}')"
            echo ""
            echo "═════════════ 前 100 行预览（全文看备份文件） ═════════════"
            head -100 '${previewFile}'
            echo "═══════════════════════════════════════════════════════════════"
            echo ""
        else
            echo "⚠️  helm template 渲染失败（备份文件为空），跳过预览"
            rm -f '${previewFile}'
        fi

        # 清理 30 天前的旧备份（best effort）
        find '\$HOME/.deploy-previews' -type f -name '*.yaml' -mtime +30 -delete 2>/dev/null || true

        true   # 确保 sh 步骤永远成功（预览失败不阻塞部署）
    """
}

/**
 * Helm Diff 预览（仅 prod 部署前调用）
 *
 * 显示本次部署与当前运行版本的差异（类似 git diff）
 * 需要 helm-diff 插件：helm plugin install https://github.com/databus23/helm-diff
 *
 * 流程：
 *   1. 检查 helm-diff 插件是否已安装
 *   2. 执行 helm diff upgrade 对比当前 release 与即将部署的版本
 *   3. 输出变更内容（运维确认后再继续）
 *
 * 注意：
 *   - 插件未安装时跳过（不阻塞部署）
 *   - 首次部署（release 不存在）时跳过 diff
 *   - 失败不阻塞部署 (|| true)
 */
def helmDiff(String chartPath, List valuesFiles, String releaseName, String namespace) {
    def fArgs = valuesFiles.collect { "-f ${it}" }.join(' ')
    def kf = env.KUBECONFIG ? "--kubeconfig ${env.KUBECONFIG}" : ""
    def tag = env.DOCKER_TAG ?: 'unknown'

    echo ""
    echo "🔍 Helm Diff 预览（对比当前 release 与即将部署的版本）"

    sh """
        set +e

        # 1. 检查 helm-diff 插件是否已安装
        if ! sudo /usr/local/bin/helm plugin list 2>/dev/null | grep -q diff; then
            echo "⚠️  helm-diff 插件未安装，跳过 diff 预览"
            echo "   安装命令: helm plugin install https://github.com/databus23/helm-diff"
            exit 0
        fi

        # 2. 检查 release 是否存在（首次部署无 diff 可看）
        if ! sudo /usr/local/bin/helm status ${releaseName} -n ${namespace} ${kf} >/dev/null 2>&1; then
            echo "ℹ️  首次部署（release 不存在），跳过 diff"
            exit 0
        fi

        # 3. 执行 helm diff
        echo ""
        echo "═════════════ Helm Diff（本次变更内容） ═════════════"
        sudo /usr/local/bin/helm diff upgrade ${releaseName} ${chartPath} ${fArgs} \\
            --set service.name=${releaseName} \\
            --set service.namespace=${namespace} \\
            --set image.tag=${tag} \\
            --set project=${env.PROJECT_NAME ?: ''} \\
            --set environment=${env.DEPLOY_ENV ?: ''} \\
            -n ${namespace} ${kf} \\
            --allow-unreleased \\
            2>&1 || true
        echo "═══════════════════════════════════════════════════════════════"
        echo ""

        true   # 确保 sh 步骤永远成功
    """
}


/**
 * 回滚
 */
def rollback(String releaseName, String namespace, def params) {
    def revision = params.ROLLBACK_REVISION ?: '0'
    echo "⏪ 回滚: ${releaseName} → revision ${revision}"

    def cmd = "sudo /usr/local/bin/helm rollback ${releaseName} ${revision} -n ${namespace}"
    if (env.KUBECONFIG) {
        cmd += " --kubeconfig ${env.KUBECONFIG}"
    }
    sh cmd
}

/**
 * 重启
 */
def restart(String releaseName, String namespace) {
    echo "🔄 重启: ${releaseName}"
    def kf = env.KUBECONFIG ? "--kubeconfig ${env.KUBECONFIG}" : ""
    sh "kubectl rollout restart deployment/${releaseName} -n ${namespace} ${kf} 2>/dev/null || " +
       "kubectl rollout restart statefulset/${releaseName} -n ${namespace} ${kf}"
}

/**
 * 健康检查
 */
def healthCheck(String releaseName, String namespace) {
    echo "🏥 健康检查..."
    def kf = env.KUBECONFIG ? "--kubeconfig ${env.KUBECONFIG}" : ""
    sh """
        kubectl rollout status deployment/${releaseName} -n ${namespace} --timeout=120s ${kf} 2>/dev/null || \
        kubectl rollout status statefulset/${releaseName} -n ${namespace} --timeout=120s ${kf}
    """
    echo "✅ ${releaseName} 部署成功，健康检查通过"
}
