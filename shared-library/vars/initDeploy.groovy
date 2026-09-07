/**
 * initDeploy - Kustomize 部署初始化（轻量版）
 *
 * 职责（只做最小事情）：
 *   1. 检查 baseDir 里的 Kustomize 模板是否存在（不在则报错）
 *   2. 固定并记录本次使用的运维配置 commit
 *
 * ❌ 不再做的事情（旧设计已废弃）：
 *   - 自动生成或提交业务/生产配置
 *
 * 当前机制：业务仓库维护 base、test/prod overlay 和唯一 Jenkinsfile。
 * 缺少任何必需目录时直接中断并提示执行 init-service.sh，不由 CI 写 Git。
 */
def call(Map config, String deployEnv) {
    def baseDir = env.DEPLOY_BASE_DIR

    echo "📋 初始化: ${config.projectName}/${config.serviceName} (${deployEnv})"

    // 检查 Kustomize 示例资产是否完整。
    if (!fileExists("${baseDir}/kustomize/examples/java/base/kustomization.yaml") ||
        !fileExists("${baseDir}/kustomize/examples/nodejs-pm2/base/kustomization.yaml")) {
        error """❌ k8s-deploy 仓库 Kustomize 模板不存在: ${baseDir}
请联系平台组运维确认 Shared Library 是否正确加载。
"""
    }

    // 运行中不再 git pull：一次 Pipeline 必须使用一个确定的共享库版本。
    def configCommit = sh(
        script: "git -C '${baseDir}' rev-parse HEAD 2>/dev/null",
        returnStdout: true
    ).trim()
    if (!(configCommit ==~ /^[0-9a-f]{40}$/)) {
        error "无法确定运维配置的 Git commit: ${baseDir}"
    }

    echo "✅ 初始化完成，共享库版本: ${configCommit}"
}
