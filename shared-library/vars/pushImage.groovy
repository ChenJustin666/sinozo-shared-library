/**
 * 构建 Docker 镜像并推送到仓库
 *
 * ═══ 镜像命名 ═══
 *   {registry}/{project}/{dockerImage}:{tag}
 *   例：swr.ap-southeast-3.myhuaweicloud.com/sinozo-test/ad-gateway:R1a2b3c4d
 *
 *   - registry：从 baselines/_global.yaml 的 image.registry 决定
 *   - project：根据 DEPLOY_ENV 从 image.projects 映射决定
 *              （dev/test 推到 sinozo-test，prod 阶段会 promotion 到 sinozo-prod）
 *   - dockerImage：从 Jenkinsfile 配置（如 ad-gateway）—— 注意：不含 project 前缀
 *   - tag：CI 自动计算 R<commit>
 *
 * ═══ Dockerfile 查找顺序 ═══
 *   1. 业务仓库根目录的 Dockerfile
 *   2. shared-library/templates/ 中的模板
 */
def call(Map config) {
    def registry = config.dockerRegistry
    def imageName = config.dockerImage         // ad-gateway（不含 project 前缀）
    def project = env.IMAGE_PROJECT             // 由 k8sDeploy.groovy 注入：sinozo-test
    def tag = env.DOCKER_TAG
    def fullImage = "${registry}/${project}/${imageName}:${tag}"

    if (!project) {
        error "❌ env.IMAGE_PROJECT 未设置，pushImage 无法确定目标 SWR project"
    }

    def dockerfile = findDockerfile(config)
    def context = config.dockerContext ?: '.'

    echo "🐳 构建镜像: ${fullImage}"

    withCredentials([usernamePassword(
        credentialsId: config.dockerCredId,
        usernameVariable: 'DOCKER_USER',
        passwordVariable: 'DOCKER_PASS'
    )]) {
        sh "docker login ${registry} -u \$DOCKER_USER -p \$DOCKER_PASS"
        sh "docker build -t ${fullImage} -f ${dockerfile} ${context}"
        sh "docker push ${fullImage}"
        sh "docker rmi ${fullImage} 2>/dev/null || true"
    }

    echo "✅ 镜像推送完成: ${fullImage}"
}

/**
 * 查找 Dockerfile
 */
def findDockerfile(Map config) {
    if (fileExists('Dockerfile')) {
        return 'Dockerfile'
    }

    def baseDir = env.DEPLOY_BASE_DIR
    if (config.serviceType == 'java') {
        def tpl = "${baseDir}/shared-library/templates/Dockerfile.java8"
        if (fileExists(tpl)) return tpl
    } else if (config.serviceType == 'nodejs') {
        def tpl = "${baseDir}/shared-library/templates/Dockerfile.nginx"
        if (fileExists(tpl)) return tpl
    }

    error "❌ 找不到 Dockerfile，请在业务仓库根目录放置 Dockerfile"
}
