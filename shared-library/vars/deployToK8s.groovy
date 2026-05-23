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
 *      - prod: <运维仓库>/baselines/prod-values/<proj>/<svc>/values-prod.yaml  (运维管)
 *   ⑤ helm --set image.tag=R<commit> ...          (CI 注入)
 *
 * ═══ 关键设计：开发改不了 prod ═══
 * - test 环境：开发在自己业务仓库写，自由调
 * - prod 环境：业务仓库的 deploy/values-prod.yaml【根本不读】
 *              运维在运维仓库 baselines/prod-values/ 下管控，开发无 push 权限
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
    def namespace   = "${config.projectName}-${deployEnv}"
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

    if (valuesChain.businessFiles.isEmpty()) {
        error """❌ 找不到业务 values 文件
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

    // 部署前预览（生产环境打印前 50 行配置摘要）
    if (deployEnv == 'prod') {
        previewConfig(chartPath, valuesChain.allFiles, releaseName)
    }

    // 部署前 adopt 既有的资源（手工创建的没有 Helm 标签，会冲突）
    // 把可能预先存在的同名资源都打上 Helm 标签让 chart 接管
    adoptExistingResource('secret', 'regcred',                  namespace, releaseName)
    adoptExistingResource('service', "${releaseName}-svc",      namespace, releaseName)
    adoptExistingResource('service', releaseName,               namespace, releaseName)
    adoptExistingResource('deployment', releaseName,            namespace, releaseName)
    adoptExistingResource('configmap', "${releaseName}-config", namespace, releaseName)


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
        // 路径: baselines/prod-values/<project>/<service>/values-prod.yaml
        def prodValues = "${baseDir}/baselines/prod-values/${config.projectName}/${config.serviceName}/values-prod.yaml"
        if (fileExists(prodValues)) {
            mode = 'prod-ops-managed'
            files << prodValues
            echo "  📎 生产配置（运维仓库）: baselines/prod-values/${config.projectName}/${config.serviceName}/values-prod.yaml"
        } else {
            // prod 配置不存在 → 直接 fail 并给出运维操作指引
            error generateProdMissingError(config, baseDir, businessDir)
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
 * 生成"prod 配置缺失"错误信息（含运维操作指引和 git 冲突解决方案）
 */
def generateProdMissingError(Map config, String baseDir, String businessDir) {
    def proj = config.projectName
    def svc  = config.serviceName
    def relPath = "baselines/prod-values/${proj}/${svc}/values-prod.yaml"

    return """
╔══════════════════════════════════════════════════════════════════════╗
║  ❌ 生产部署失败：${svc} 还没有 prod 配置                              
╠══════════════════════════════════════════════════════════════════════╣
║                                                                        
║  期望路径（运维仓库）:                                                 
║      ${relPath}
║                                                                        
║  原因：开发不能管 prod 副本数 / 资源 / 安全字段，必须由运维管控。     
║                                                                        
╠══════════════════════════════════════════════════════════════════════╣
║  🛠 运维操作步骤（在运维仓库 sinozo-shared-library）:                  
╠══════════════════════════════════════════════════════════════════════╣
║                                                                        
║  1. clone / pull 运维仓库到本地                                        
║     git clone <sinozo-shared-library> /tmp/ops-repo                    
║     cd /tmp/ops-repo                                                   
║     git pull origin main          # 或者已经 clone 过：直接 pull       
║                                                                        
║  2. 创建 prod 配置（推荐：从 test 复制再改）                           
║     mkdir -p baselines/prod-values/${proj}/${svc}                       
║     cp <业务仓库>/deploy/values-test.yaml \\\\                            
║        baselines/prod-values/${proj}/${svc}/values-prod.yaml             
║                                                                        
║  3. 编辑实际 prod 值（副本数 / 资源 / Nacos 地址 / env）              
║     vi baselines/prod-values/${proj}/${svc}/values-prod.yaml             
║                                                                        
║  4. 提交并 push                                                        
║     git add baselines/prod-values/${proj}/${svc}/                        
║     git commit -m "ops: ${svc} prod 配置"                              
║     git push origin main                                               
║                                                                        
║  5. 重新触发 Jenkins ${svc}-prod 即可                                  
║                                                                        
╠══════════════════════════════════════════════════════════════════════╣
║  ⚠️  push 时遇到冲突怎么办：                                            
╠══════════════════════════════════════════════════════════════════════╣
║                                                                        
║  报错: ! [rejected] main -> main (fetch first)                         
║                                                                        
║  原因：另一个运维同事先 push 了                                         
║                                                                        
║  解决（按顺序执行）:                                                   
║     git pull --rebase origin main                                      
║     # 如果显示 CONFLICT 字样，编辑冲突文件保留你想要的部分              
║     # 然后:                                                            
║     git add <冲突文件>                                                  
║     git rebase --continue                                              
║     git push origin main                                               
║                                                                        
║  极端情况（rebase 太复杂搞不定）:                                       
║     git rebase --abort       # 放弃 rebase 回到原状                    
║     git pull origin main     # 用 merge 方式                            
║     # 解决冲突后:                                                      
║     git add <冲突文件>                                                  
║     git commit -m "merge"                                              
║     git push origin main                                               
║                                                                        
║  实在搞不定 → 联系另一位运维确认改动 → 二选一                           
║                                                                        
╠══════════════════════════════════════════════════════════════════════╣
║  💡 注意：                                                             
║     - 业务仓库下也有 deploy/values-prod.yaml？【会被忽略】不读它       
║     - prod 配置改动不影响其他服务部署（每服务一个独立文件）             
║     - Jenkins 是只读运维仓库的，运维 push 不影响其他正在跑的 Job       
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
 * 部署前预览（仅 prod）
 */
def previewConfig(String chartPath, List valuesFiles, String releaseName) {

    def fArgs = valuesFiles.collect { "-f ${it}" }.join(' ')
    echo "📊 部署配置预览（前 100 行）："
    sh "sudo /usr/local/bin/helm template ${releaseName} ${chartPath} ${fArgs} | head -100 || true"
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
