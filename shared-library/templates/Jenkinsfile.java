/**
 * Java 后端服务 Jenkinsfile 模板
 *
 * 使用：复制到业务仓库根目录重命名为 Jenkinsfile，或粘贴到 Jenkins Pipeline script
 * 环境：由 Job 参数 DEPLOY_ENV 控制（test/prod）
 * kubeconfig：默认自动推断 k8s-{projectName}-{DEPLOY_ENV}
 * 首次部署：自动生成 values 文件，运维检查后再次构建即可
 */
@Library('k8s-deploy-lib@main') _

k8sDeploy(
    // ═══ 必填 ═══
    projectName:      'adv',
    serviceName:      'ad-gateway',
    serviceType:      'java',
    gitUrl:           'http://git.example.com/server/AdGateway.git',
    gitCredId:        'git-adv-cred',
    dockerImage:      'sinozo/ad-gateway',
    dockerCredId:     'docker-swr-cred',

    // ═══ 构建配置 ═══
    jdkTool:          'jdk 1.8',                    // Jenkins 全局 JDK 工具名
    mavenGoals:       'clean package -DskipTests',
    // mavenTool:     'maven 3.8',                  // Jenkins 全局 Maven（可选）
    // mavenHome:     '/usr/local/maven',            // Maven 路径（不用 mavenTool 时）

    // ═══ 可选 ═══
    // agent:            'java-build',               // 构建节点标签
    // kubeconfigCredId: 'k8s-adv-test',            // 手动指定集群（默认自动推断）
    // nacosCredId:      'nacos-adv-cred',          // Nacos 凭据
    // subdirectory:     'module-name',             // MonoRepo 子目录
    // defaultBranch:    'main',
)
