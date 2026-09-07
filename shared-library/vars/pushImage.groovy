/**
 * 构建 Docker 镜像并推送到仓库
 *
 * ═══ 镜像命名 ═══
 *   {registry}/{project}/{dockerImage}:{tag}
 *   例：swr.ap-southeast-3.myhuaweicloud.com/sinozo/ad-gateway:R<40位Git SHA>
 *
 *   - registry：从 baselines/_global.yaml 的 image.registry 决定
 *   - project：根据 DEPLOY_ENV 从 image.projects 映射决定
 *              （默认所有环境使用 sinozo；项目覆盖不同时才 promotion）
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
    def project = env.IMAGE_PROJECT             // 由 k8sDeploy.groovy 注入，默认 sinozo
    def tag = env.DOCKER_TAG
    def fullImage = "${registry}/${project}/${imageName}:${tag}"

    if (!project) {
        error "❌ env.IMAGE_PROJECT 未设置，pushImage 无法确定目标 SWR project"
    }

    def dockerfile = findDockerfile(config)
    def context = config.dockerContext ?: '.'

    echo "🐳 构建镜像: ${fullImage}"

    withDockerRegistry(config) {
        // 启用 BuildKit 支持 cache mount 和其他现代特性
        // 添加超时防止构建卡死，添加元数据标签用于追溯
        timeout(time: 20, unit: 'MINUTES') {
            def buildArgs = ""
            if (env.GIT_COMMIT_ID) {
                buildArgs += " --build-arg GIT_COMMIT=${env.GIT_COMMIT_ID}"
            }
            if (env.BUILD_NUMBER) {
                buildArgs += " --build-arg BUILD_NUMBER=${env.BUILD_NUMBER}"
            }
            // BUILD_TIME 使用 Jenkins 时间戳
            if (env.BUILD_TIMESTAMP) {
                buildArgs += " --build-arg BUILD_TIME=${env.BUILD_TIMESTAMP}"
            }
            
            sh """
                export DOCKER_BUILDKIT=1
                docker build -t '${fullImage}' -f '${dockerfile}' ${buildArgs} '${context}'
            """
        }
        
        sh "docker push '${fullImage}'"
        
        // 清理本地镜像，避免占用磁盘
        sh "docker rmi '${fullImage}' 2>/dev/null || true"
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
        def javaTemplate = config.jdkTool?.toLowerCase()?.contains('17') ? 'Dockerfile.java17' : 'Dockerfile.java8'
        def tpl = "${baseDir}/shared-library/templates/${javaTemplate}"
        if (fileExists(tpl)) return tpl
    } else if (config.serviceType == 'nodejs') {
        def tpl = "${baseDir}/shared-library/templates/Dockerfile.nodejs-pm2"
        if (fileExists(tpl)) return tpl
    }

    error "❌ 找不到 Dockerfile，请在业务仓库根目录放置 Dockerfile"
}
