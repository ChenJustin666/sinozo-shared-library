/**
 * initDeploy - 部署初始化（轻量版）
 *
 * 职责（只做最小事情）：
 *   1. 检查 baseDir 里的 chart 是否存在（不在则报错）
 *   2. 拉取最新代码（best effort）
 *
 * ❌ 不再做的事情（旧设计已废弃）：
 *   - 自动从 examples 模板生成 projects/<proj>/<env>/<svc>/values-<env>.yaml
 *     （旧路径已经废弃，新设计不再用这个目录）
 *   - 自动 git push values 文件
 *
 * ✅ 取而代之的新机制（在 deployToK8s.groovy::resolveValuesChain）：
 *   - test 环境：业务方在【业务仓库 deploy/values-test.yaml】里写
 *   - prod 环境：CI 检测无 prod values 时，自动从 deploy/values-test.yaml
 *                派生模板到 baselines/projects/<proj>/<svc>/values-prod.yaml，
 *                尝试 push 到运维仓库，本次部署 fail 等运维 review
 */
def call(Map config, String deployEnv) {
    def baseDir = env.DEPLOY_BASE_DIR

    echo "📋 初始化: ${config.projectName}/${config.serviceName} (${deployEnv})"

    // ── 1. 检查 chart 是否存在 ──
    if (!fileExists("${baseDir}/charts/generic-service/Chart.yaml")) {
        error """❌ k8s-deploy 仓库 chart 不存在: ${baseDir}
请联系平台组运维确认 Shared Library 是否正确加载。
"""
    }

    // ── 2. 拉取最新代码（best effort，不影响主流程）──
    sh "cd ${baseDir} && git pull --rebase origin main 2>/dev/null || true"

    echo "✅ 初始化完成"
}
