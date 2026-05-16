/**
 * Java 项目构建（Maven）
 *
 * JDK 版本通过 Jenkins 全局工具配置切换：
 *   - jdkTool: Jenkins 中配置的 JDK 工具名称（如 "jdk 1.8"、"jdk 17"）
 *   - mavenTool: Jenkins 中配置的 Maven 工具名称（如 "maven 3.8"）
 *
 * 如果不使用 Jenkins 工具管理，也支持直接指定路径：
 *   - jdkHome: JDK 安装路径
 *   - mavenHome: Maven 安装路径
 */
def call(Map config) {
    def mavenGoals = config.mavenGoals ?: 'clean package -DskipTests'

    // 优先使用 Jenkins 全局工具名称
    def jdkTool = config.jdkTool ?: 'jdk 1.8'
    def mavenTool = config.mavenTool ?: ''

    echo "☕ Java 构建: JDK=${jdkTool}, 命令: ${mavenGoals}"

    // 使用 Jenkins tool 指令获取路径
    def jdkHome = tool name: jdkTool, type: 'jdk'

    def mvnCmd
    if (mavenTool) {
        def mavenHome = tool name: mavenTool, type: 'maven'
        mvnCmd = "${mavenHome}/bin/mvn"
    } else {
        def mavenHome = config.mavenHome ?: '/usr/local/maven'
        mvnCmd = "${mavenHome}/bin/mvn"
    }

    sh """
        export JAVA_HOME=${jdkHome}
        export PATH=\$JAVA_HOME/bin:\$PATH
        java -version
        ${mvnCmd} ${mavenGoals}
    """
    echo "✅ Java 构建完成"
}
