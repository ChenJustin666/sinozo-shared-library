/**
 * Image Promotion - 镜像跨 project 流转
 *
 * ═══ 用途 ═══
 * test/prod 用不同 SWR project（如 sinozo-test / sinozo-prod）时，
 * prod 部署前要把 test project 的镜像"提升"到 prod project。
 *
 * ═══ 关键特性 ═══
 * 1. 不重新构建：用 docker pull + tag + push（layer 完全复用）
 * 2. layer hash 不变：retag 后还是同一个镜像
 * 3. 幂等：如果 prod 已有该 tag，直接跳过
 * 4. 自动跳过：如果 src 和 dst project 相同（单 project 模式），不执行任何动作
 *
 * ═══ 参数 ═══
 * @param config       Pipeline 配置（含 dockerImage、dockerCredId、dockerRegistry）
 * @param srcProject   源 project（如 sinozo-test）
 * @param dstProject   目标 project（如 sinozo-prod）
 * @param tag          镜像 tag（如 R1a2b3c4d）
 *
 * ═══ 行业实践 ═══
 *   - Google Cloud Build: gcrane copy
 *   - Red Hat OpenShift: skopeo copy
 *   - 通用 Docker:        docker pull + tag + push（本实现）
 */
def call(Map config, String srcProject, String dstProject, String tag) {

    // ── 单 project 模式，直接跳过 ──
    if (srcProject == dstProject) {
        echo "ℹ️  src/dst project 相同（${srcProject}），跳过 image promotion"
        return
    }

    def registry = config.dockerRegistry
    def imageName = config.dockerImage    // 如：ad-gateway（不含 project 前缀）
    def srcImage = "${registry}/${srcProject}/${imageName}:${tag}"
    def dstImage = "${registry}/${dstProject}/${imageName}:${tag}"

    echo """
╔═══════════════════════════════════════════════════════════════
║  📤 Image Promotion
╠═══════════════════════════════════════════════════════════════
║  Source:      ${srcImage}
║  Destination: ${dstImage}
║  Strategy:    docker pull + tag + push (no rebuild, layer reuse)
╚═══════════════════════════════════════════════════════════════
"""

    withCredentials([usernamePassword(
        credentialsId: config.dockerCredId,
        usernameVariable: 'DOCKER_USER',
        passwordVariable: 'DOCKER_PASS'
    )]) {

        // ── 1. 登录 registry（同一 registry，不同 project 共用账号）──
        sh "docker login ${registry} -u \$DOCKER_USER -p \$DOCKER_PASS"

        // ── 2. 幂等检查：如果 dst 已存在该 tag，直接跳过（节省时间）──
        def dstExists = sh(
            script: "docker manifest inspect ${dstImage} >/dev/null 2>&1",
            returnStatus: true
        ) == 0

        if (dstExists) {
            echo "✅ 目标镜像已存在，跳过 promotion: ${dstImage}"
            return
        }

        // ── 3. Pull source image ──
        echo "  ⬇️  docker pull ${srcImage}"
        sh "docker pull ${srcImage}"

        // ── 4. Retag（不重新构建，layer 完全复用）──
        echo "  🏷️  docker tag ${srcImage} → ${dstImage}"
        sh "docker tag ${srcImage} ${dstImage}"

        // ── 5. Push to destination ──
        echo "  ⬆️  docker push ${dstImage}"
        sh "docker push ${dstImage}"

        // ── 6. 清理本地（节省 Jenkins 节点磁盘）──
        sh "docker rmi ${srcImage} ${dstImage} 2>/dev/null || true"
    }

    echo "✅ Image promotion 完成: ${dstImage}"
}
