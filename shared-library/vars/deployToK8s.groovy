/**
 * 部署到 K8s（Helm upgrade --install）
 *
 * ═══ 合并机制（v2，字段所有权契约）═══
 * Helm `-f` 后置覆盖，加载顺序：
 *   ① charts/generic-service/values.yaml          (Chart 默认值)
 *   ② baselines/_global.yaml                      (全公司基线 / 必加载)
 *   ③ baselines/{project}/{svc}/baseline-{env}.yaml (服务级基线 / 可选)
 *   ④ <业务仓库>/deploy/values.yaml                (业务通用 / 开发管)
 *   ⑤ <业务仓库>/deploy/values-{env}.yaml          (业务环境 / 开发管)
 *   ⑥ helm --set image.tag=R<commit> ...          (CI 注入)
 *
 * ═══ 旧路径兼容 ═══
 * 如果业务仓库未提供 deploy/ 目录，回退到旧路径：
 *   projects/{project}/{env}/{svc}/values-{env}.yaml
 * 这样现有服务无需迁移即可继续部署
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

    // 校验业务 values 不含运维字段（warning 模式，不阻塞）
    validateBusinessValues(valuesChain.businessFiles, baseDir, deployEnv == 'prod')

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

    // ── 2. 服务级 baseline（可选）──
    def serviceBaseline = "${baseDir}/baselines/${config.projectName}/${config.serviceName}/baseline-${deployEnv}.yaml"
    if (fileExists(serviceBaseline)) {
        files << serviceBaseline
        echo "  📎 服务基线: baselines/${config.projectName}/${config.serviceName}/baseline-${deployEnv}.yaml"
    }

    // ── 3. 业务 values（新模式：业务仓库 deploy/）──
    // 业务代码已经被 checkout 到 $WORKSPACE
    def businessDir = config.subdirectory ? "${env.WORKSPACE}/${config.subdirectory}/deploy" : "${env.WORKSPACE}/deploy"
    def businessCommon = "${businessDir}/values.yaml"
    def businessEnv    = "${businessDir}/values-${deployEnv}.yaml"

    if (fileExists(businessEnv)) {
        // 新模式
        mode = 'new'
        if (fileExists(businessCommon)) {
            files << businessCommon
            businessFiles << businessCommon
            echo "  📎 业务通用: deploy/values.yaml"
        }
        files << businessEnv
        businessFiles << businessEnv
        echo "  📎 业务环境: deploy/values-${deployEnv}.yaml"
    } else {
        // ── 4. 兼容旧路径 ──
        def legacyValues = "${baseDir}/projects/${config.projectName}/${deployEnv}/${config.serviceName}/values-${deployEnv}.yaml"
        if (fileExists(legacyValues)) {
            mode = 'legacy'
            files << legacyValues
            businessFiles << legacyValues
            echo "  📎 旧模式 values: projects/${config.projectName}/${deployEnv}/${config.serviceName}/values-${deployEnv}.yaml"
            echo "  💡 建议迁移到新模式：将 values 移动到业务仓库 deploy/ 目录"
        }
    }

    return [
        mode:          mode,
        allFiles:      files,
        businessFiles: businessFiles,
    ]
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

    // namespace（pipeline 自动推断 {project}-{env}）
    cmd += " --set service.namespace=${namespace}"

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
