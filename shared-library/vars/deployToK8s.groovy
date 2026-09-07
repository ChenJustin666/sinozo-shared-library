/**
 * Kustomize deployment entry point.
 *
 * Business repositories own deploy/kustomize/base and test/prod overlays.
 * Existing services may still use the legacy operations-repository prod overlay.
 */
def call(Map config, String deployEnv, def params, boolean applyChanges = true) {
    def baseDir = env.DEPLOY_BASE_DIR
    def namespace = resolveNamespace(config, deployEnv, baseDir)
    def serviceName = config.serviceName

    if (params.ACTION == 'deploy') {
        deploy(config, deployEnv, namespace, serviceName,
               params.SKIP_HEALTH_CHECK ?: false, baseDir, applyChanges)
    } else if (params.ACTION == 'rollback') {
        rollback(serviceName, namespace, params)
    } else if (params.ACTION == 'restart') {
        restart(serviceName, namespace)
    }
}

def deploy(Map config, String deployEnv, String namespace, String serviceName,
           boolean skipHealth, String baseDir, boolean applyChanges) {
    validateDeploymentInputs(config, deployEnv, namespace, serviceName)
    def source = resolveKustomizeSource(config, deployEnv, baseDir)
    validateKustomizeSources(source, baseDir)
    def workDir = "${env.WORKSPACE}/.kustomize-render/${serviceName}-${deployEnv}"
    def overlayDir = "${workDir}/overlays/${deployEnv}"
    def image = "${config.dockerRegistry}/${env.IMAGE_PROJECT}/${config.dockerImage}:${env.DOCKER_TAG}"
    def kctl = resolveKubectl()
    if (!kctl) {
        error 'kubectl 不可用；Kustomize 部署要求 kubectl >= 1.21（需支持 kubectl kustomize）'
    }
    def kf = env.KUBECONFIG ? "--kubeconfig=${env.KUBECONFIG}" : ''

    sh "rm -rf '${workDir}' && mkdir -p '${workDir}/overlays'"
    sh "cp -R '${source.base}' '${workDir}/base'"
    sh "cp -R '${source.overlay}' '${overlayDir}'"
    // Templates use a stable placeholder so the same base can be copied to any service.
    // Substitute only in the throw-away render directory; source repositories stay clean.
    sh "find '${workDir}' -type f -name '*.yaml' -exec sed -i -e 's|SERVICE_NAME|${serviceName}|g' -e 's|APP_IMAGE|${image}|g' {} +"

    def imageCommit = (env.DOCKER_TAG ?: '').replaceFirst(/^R/, '')
    if (!(imageCommit ==~ /^[0-9a-f]{40}$/)) {
        error '部署镜像必须使用 R<40位Git SHA> tag 才能追溯'
    }
    def businessRoot = env.BUSINESS_WORKSPACE ?: env.WORKSPACE
    def businessCommit = resolveGitCommit(businessRoot, env.GIT_COMMIT_ID ?: '')
    def opsCommit = (deployEnv == 'prod' && source.overlay.startsWith("${baseDir}/baselines/")) ?
        resolveGitCommit(baseDir, '') : businessCommit
    sh """
        cat > '${workDir}/kustomization.yaml' <<'TRACEABILITY'
apiVersion: kustomize.config.k8s.io/v1beta1
kind: Kustomization
resources:
  - overlays/${deployEnv}
namespace: ${namespace}
commonAnnotations:
  delivery.sinozo.com/image-commit: "${imageCommit}"
  delivery.sinozo.com/business-commit: "${businessCommit}"
  delivery.sinozo.com/ops-commit: "${opsCommit}"
  delivery.sinozo.com/build-number: "${env.BUILD_NUMBER ?: 'unknown'}"
TRACEABILITY
    """

    def previewDir = "/data/backup/deploy-previews/${deployEnv}"
    def previewFile = "${previewDir}/${serviceName}-${env.DOCKER_TAG}-${env.BUILD_NUMBER ?: 'local'}.yaml"
    echo "🚀 Kustomize 部署: ${serviceName} → ${namespace} (image: ${image})"
    sh """
        set -eu
        mkdir -p '${previewDir}' 2>/dev/null || true
        ${kctl} kustomize '${workDir}' > '${workDir}/manifest.yaml'
    """
    sh "bash '${baseDir}/automation/kustomize-validate.sh' '${workDir}/manifest.yaml'"

    if (!applyChanges) {
        sh """
            set -eu
            echo '--- production change preview (kubectl diff) ---'
            ${kctl} diff -f '${workDir}/manifest.yaml' -n '${namespace}' ${kf} || test \$? -eq 1
        """
        echo "✅ 生产变更预览完成；此阶段未修改集群"
        return
    }

    // 在完整渲染和安全校验通过后才修改集群。
    if (config.manageNamespace) {
        ensureNamespace(kctl, kf, namespace)
    } else {
        echo "ℹ️  namespace/${namespace} 由平台预建，Pipeline 不申请集群级管理权限"
    }
    if (config.manageRegistrySecret) {
        ensureRegistrySecret(kctl, kf, namespace)
    } else {
        echo "ℹ️  imagePullSecret/regcred 由 ACK、SealedSecret 或 ExternalSecret 管理"
    }
    ensureRuntimeSecret(config, kctl, kf, namespace, serviceName)

    sh """
        set -eu
        cp '${workDir}/manifest.yaml' '${previewFile}' 2>/dev/null || true
        sed -n '1,120p' '${workDir}/manifest.yaml'
        ${kctl} diff -f '${workDir}/manifest.yaml' -n '${namespace}' ${kf} || test \$? -eq 1
        ${kctl} apply -f '${workDir}/manifest.yaml' -n '${namespace}' ${kf}
        find '${previewDir}' -type f -name '*.yaml' -mtime +30 -delete 2>/dev/null || true
    """

    if (!skipHealth) {
        healthCheck(kctl, kf, serviceName, namespace)
    }
}

def resolveGitCommit(String directory, String fallback) {
    def commit = sh(
        script: "git -C '${directory}' rev-parse HEAD 2>/dev/null || true",
        returnStdout: true
    ).trim()
    if (!commit) commit = fallback
    if (!(commit ==~ /^[0-9a-f]{40}$/)) {
        error "无法获取可追溯的 Git commit: ${directory}"
    }
    return commit
}

def validateKustomizeSources(Map source, String baseDir) {
    def validator = "${baseDir}/automation/kustomize-validate.sh"
    if (!fileExists(validator)) {
        error "缺少 Kustomize 安全校验器: ${validator}"
    }
    sh "bash '${validator}' '${source.base}' '${source.overlay}'"
}

def validateDeploymentInputs(Map config, String deployEnv, String namespace, String serviceName) {
    def dnsName = /^[a-z0-9]([-a-z0-9]*[a-z0-9])?$/
    if (!(serviceName ==~ dnsName) || !(config.projectName ==~ dnsName) || !(namespace ==~ dnsName)) {
        error 'projectName、serviceName 和 namespace 必须是小写 Kubernetes DNS 名称'
    }
    if (!(deployEnv ==~ /^[a-z0-9-]+$/)) {
        error "非法部署环境: ${deployEnv}"
    }
    if (!(config.dockerImage ==~ /^[a-z0-9][a-z0-9._\/-]*$/) ||
        !(config.dockerRegistry ==~ /^[A-Za-z0-9.:-]+$/)) {
        error 'dockerRegistry 或 dockerImage 格式非法'
    }
    if (!(env.DOCKER_TAG ==~ /^[A-Za-z0-9_][A-Za-z0-9._-]{0,127}$/)) {
        error 'IMAGE_TAG 格式非法'
    }
    if (config.subdirectory &&
        (!(config.subdirectory ==~ /^[A-Za-z0-9._\/-]+$/) || config.subdirectory.contains('..') || config.subdirectory.startsWith('/'))) {
        error 'subdirectory 必须是业务仓库内的相对路径'
    }
}

def resolveKustomizeSource(Map config, String deployEnv, String baseDir) {
    def repositoryRoot = env.BUSINESS_WORKSPACE ?: env.WORKSPACE
    def businessRoot = config.subdirectory ? "${repositoryRoot}/${config.subdirectory}" : repositoryRoot
    def base = "${businessRoot}/deploy/kustomize/base"
    if (!fileExists("${base}/kustomization.yaml")) {
        error """找不到 Kustomize base: ${base}/kustomization.yaml
请执行 automation/init-service.sh 生成模板，或按 docs/Kustomize.md 创建目录。"""
    }

    def overlay
    if (deployEnv == 'prod') {
        // Preferred model: one business repository owns base and both overlays.
        // Keep the ops path as a migration fallback for existing services.
        def businessProd = "${businessRoot}/deploy/kustomize/overlays/prod"
        def opsProd = "${baseDir}/baselines/projects/${config.projectName}/${config.serviceName}/kustomize/prod"
        if (fileExists("${businessProd}/kustomization.yaml")) {
            overlay = businessProd
        } else if (fileExists("${opsProd}/kustomization.yaml")) {
            overlay = opsProd
            echo "兼容旧服务：使用运维仓库生产 overlay ${opsProd}"
        } else {
            error """找不到生产 overlay：${businessProd}/kustomization.yaml
请在业务仓库创建 deploy/kustomize/overlays/prod，或迁移旧服务的运维 overlay。"""
        }
    } else {
        overlay = "${businessRoot}/deploy/kustomize/overlays/${deployEnv}"
        if (!fileExists("${overlay}/kustomization.yaml")) {
            error "找不到业务 ${deployEnv} overlay: ${overlay}/kustomization.yaml"
        }
    }
    return [base: base, overlay: overlay]
}

def ensureNamespace(String kctl, String kf, String namespace) {
    sh """
        set -eu
        if ${kctl} get namespace '${namespace}' ${kf} >/dev/null 2>&1; then
            echo 'namespace/${namespace} 已存在，跳过创建'
        else
            echo 'namespace/${namespace} 不存在，执行创建'
            ${kctl} create namespace '${namespace}' ${kf} --dry-run=client -o yaml | ${kctl} apply -f - ${kf}
        fi
    """
}

def ensureRegistrySecret(String kctl, String kf, String namespace) {
    if (sh(script: "${kctl} get secret regcred -n '${namespace}' ${kf} >/dev/null 2>&1", returnStatus: true) == 0) {
        echo "imagePullSecret/regcred 已存在于 ${namespace}，跳过创建"
        return
    }
    if (!env.DOCKER_USER || !env.DOCKER_PASS || !env.DOCKER_REGISTRY) {
        error '缺少 Docker registry 凭据，无法创建 imagePullSecret/regcred'
    }
    sh """
        ${kctl} create secret docker-registry regcred \
          --docker-server='${env.DOCKER_REGISTRY}' \
          --docker-username=\"\$DOCKER_USER\" \
          --docker-password=\"\$DOCKER_PASS\" \
          -n '${namespace}' ${kf} --dry-run=client -o yaml | \
        ${kctl} apply -f - ${kf}
    """
}

def ensureRuntimeSecret(Map config, String kctl, String kf, String namespace, String serviceName) {
    if (config.serviceType != 'java' || config.nacosSecretMode != 'jenkins' ||
        !env.NACOS_USERNAME || !env.NACOS_PASSWORD) {
        return
    }
    sh """
        ${kctl} create secret generic '${serviceName}-runtime' \
          --from-literal=NACOS_USERNAME=\"\$NACOS_USERNAME\" \
          --from-literal=NACOS_PASSWORD=\"\$NACOS_PASSWORD\" \
          -n '${namespace}' ${kf} --dry-run=client -o yaml | \
        ${kctl} apply -f - ${kf}
    """
}

def resolveNamespace(Map config, String deployEnv, String baseDir) {
    def projectOverrides = "${baseDir}/baselines/projects/${config.projectName}/_overrides.yaml"
    if (fileExists(projectOverrides)) {
        def ns = parseYamlNestedKey(projectOverrides, 'namespaces', deployEnv)
        if (ns) return ns
    }
    return "${config.projectName}-${deployEnv}"
}

def parseYamlNestedKey(String file, String parent, String child) {
    return sh(script: """
        awk -v parent='${parent}' -v child='${child}' '
        \$0 ~ "^"parent":[[:space:]]*\$" { inside=1; next }
        inside && /^[^[:space:]#]/ { inside=0 }
        inside && \$0 ~ "^[[:space:]]+"child"[[:space:]]*:" {
            line=\$0; sub(/^[^:]*:[[:space:]]*/, "", line); sub(/[[:space:]]*#.*/, "", line)
            gsub(/[\"]/, "", line); print line; exit
        }' '${file}' | tr -d "'"
    """, returnStdout: true).trim() ?: null
}

def resolveKubectl() {
    for (candidate in ['sudo /usr/local/bin/kubectl', 'sudo /usr/bin/kubectl',
                       '/usr/local/bin/kubectl', '/usr/bin/kubectl', 'kubectl']) {
        if (sh(script: "${candidate} version --client >/dev/null 2>&1", returnStatus: true) == 0) {
            return candidate
        }
    }
    return null
}

def rollback(String serviceName, String namespace, def params) {
    def kctl = resolveKubectl()
    if (!kctl) error 'kubectl 不可用'
    def kf = env.KUBECONFIG ? "--kubeconfig=${env.KUBECONFIG}" : ''
    def revision = params.ROLLBACK_REVISION ?: '0'
    def target = revision == '0' ? '' : "--to-revision=${revision}"
    sh "${kctl} rollout history deployment/${serviceName} -n ${namespace} ${kf}"
    sh "${kctl} rollout undo deployment/${serviceName} -n ${namespace} ${target} ${kf}"
    healthCheck(kctl, kf, serviceName, namespace)
}

def restart(String serviceName, String namespace) {
    def kctl = resolveKubectl()
    if (!kctl) error 'kubectl 不可用'
    def kf = env.KUBECONFIG ? "--kubeconfig=${env.KUBECONFIG}" : ''
    sh "${kctl} rollout restart deployment/${serviceName} -n ${namespace} ${kf}"
    healthCheck(kctl, kf, serviceName, namespace)
}

def healthCheck(String kctl, String kf, String serviceName, String namespace) {
    sh "${kctl} rollout status deployment/${serviceName} -n ${namespace} --timeout=300s ${kf}"
}
