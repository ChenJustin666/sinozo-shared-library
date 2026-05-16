/**
 * initDeploy - 部署初始化
 *
 * 职责：
 * 1. 检查 k8s-deploy 仓库是否存在（不存在则报错+指引）
 * 2. git pull 拉取最新配置
 * 3. 检查 values-{env}.yaml 是否存在
 *    - 存在 → 跳过
 *    - 不存在 → 自动从模板生成初始 values 文件（需人工确认后再部署）
 *
 * 自动生成逻辑：
 *   根据 serviceType 选择模板：
 *     java    → examples/values-java.yaml
 *     nodejs  → examples/values-frontend.yaml
 *   自动替换 service.name / namespace / image.name
 *   生成后提交到 Git，首次部署需要运维审核 values 后再次触发
 */
def call(Map config, String deployEnv) {
    def baseDir = env.DEPLOY_BASE_DIR
    def projectDir = "${baseDir}/projects/${config.projectName}/${deployEnv}/${config.serviceName}"
    def valuesFile = "${projectDir}/values-${deployEnv}.yaml"
    def namespace = "${config.projectName}-${deployEnv}"

    echo "📋 初始化: ${config.projectName}/${config.serviceName} (${deployEnv})"

    // ── 1. 检查 k8s-deploy 仓库 ──
    if (!fileExists("${baseDir}/charts/generic-service/Chart.yaml")) {
        error """❌ k8s-deploy 仓库不存在: ${baseDir}
请先在 Jenkins 节点执行:
  mkdir -p /var/lib/jenkins/workspace/deploy
  cd /var/lib/jenkins/workspace/deploy
  sudo chown jenkins:jenkins /var/lib/jenkins/workspace/deploy
  git clone <k8s-deploy仓库地址> k8s-deploy"""
    }

    // ── 2. 拉取最新代码 ──
    sh "cd ${baseDir} && git pull --rebase origin main 2>/dev/null || true"

    // ── 3. 检查 values 文件 ──
    if (fileExists(valuesFile)) {
        echo "✅ values 文件已存在: ${valuesFile}"
        return
    }

    // ── 4. 自动生成初始 values 文件 ──
    echo "⚠️ values 文件不存在，自动生成初始配置..."

    def templateFile
    if (config.serviceType == 'nodejs') {
        templateFile = "${baseDir}/charts/generic-service/examples/values-frontend.yaml"
    } else {
        templateFile = "${baseDir}/charts/generic-service/examples/values-java.yaml"
    }

    // 创建目录
    sh "mkdir -p ${projectDir}"

    // 从模板复制并替换关键字段
    sh """
        cp ${templateFile} ${valuesFile}

        # 替换 service.name
        sed -i 's/^  name: .*/  name: ${config.serviceName}/' ${valuesFile}

        # 替换 namespace
        sed -i 's/^  namespace: .*/  namespace: ${namespace}/' ${valuesFile}

        # 替换 image.name（保留 registry 前缀）
        sed -i '/^  name:.*sinozo/s|name: .*|name: ${config.dockerImage}|' ${valuesFile}

        # 替换 project 和 environment
        sed -i 's/^project: .*/project: ${config.projectName}/' ${valuesFile}
        sed -i 's/^environment: .*/environment: ${deployEnv}/' ${valuesFile}
    """

    // 提交到 Git
    sh """
        cd ${baseDir}
        git add ${valuesFile}
        git config user.name "jenkins-ci" 2>/dev/null || true
        git config user.email "jenkins@ci.local" 2>/dev/null || true
        git commit -m "[init] ${config.projectName}/${config.serviceName}: 自动生成 values-${deployEnv}.yaml" 2>/dev/null || true
        git push origin main 2>/dev/null || true
    """

    echo """
⚠️ ════════════════════════════════════════════════════════
   已自动生成初始配置: ${valuesFile}
   
   请运维检查并修改以下内容：
   1. java.opts 中的 Nacos 地址和 namespace
   2. resources 资源限制
   3. 其他业务相关配置
   
   修改完成后重新触发此 Job 进行部署
════════════════════════════════════════════════════════"""

    // 首次生成不部署，需要运维确认配置后再部署
    error("首次初始化完成，请检查 values 文件后重新触发部署")
}
