/**
 * Node.js / 前端项目构建
 *
 * 前端项目使用 Docker 多阶段构建：
 *   - 第一阶段：node 镜像中执行 npm install + npm run build
 *   - 第二阶段：nginx 镜像运行静态文件
 *
 * 因此这里不需要在 Jenkins 节点安装 Node.js，
 * 构建逻辑全在 Dockerfile 中完成。
 *
 * 如果业务仓库没有 Dockerfile，会使用 shared-library/templates/Dockerfile.nginx
 */
def call(Map config) {
    echo "🌐 前端项目: 使用 Docker 多阶段构建，跳过本地 npm 步骤"
    // 前端构建在 Docker build 阶段完成，这里只做预检查
    if (!fileExists('package.json')) {
        error "❌ 未找到 package.json，请确认代码仓库结构"
    }
    echo "✅ 前端项目预检查通过"
}
