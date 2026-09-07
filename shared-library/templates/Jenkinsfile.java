/**
 * Java 后端服务 Jenkinsfile 模板
 *
 * 使用：复制到业务仓库根目录重命名为 Jenkinsfile，或粘贴到 Jenkins Pipeline script
 * 环境：由 Job 参数 DEPLOY_ENV 控制（test/prod）
 * kubeconfig：由 Jenkins 管理员通过 K8S_CRED_TEST/K8S_CRED_PROD 配置
 * 首次部署前请用 init-service.sh 生成 Kustomize base/test 与运维 prod overlay
 */
@Library('k8s-deploy-lib@main') _

k8sDeploy(
    // ═══ 必填 ═══
    projectName:      'adv',
    serviceName:      'ad-gateway',
    serviceType:      'java',
    gitUrl:           'http://git.example.com/server/AdGateway.git',
    gitCredId:        'git-adv-cred',
    dockerImage:      'ad-gateway',
    dockerCredId:     'docker-swr-cred',

    // ═══ 构建配置 ═══
    jdkTool:          'jdk 1.8',                    // Jenkins 全局 JDK 工具名
    mavenGoals:       'clean package -DskipTests',
    // mavenTool:     'maven 3.8',                  // Jenkins 全局 Maven（可选）
    // mavenHome:     '/usr/local/maven',            // Maven 路径（不用 mavenTool 时）

    // ═══ 可选 ═══
    // agent:            'java-build',               // 构建节点标签
    // subdirectory:     'module-name',             // MonoRepo 子目录
    // defaultBranch:    'main',
)
