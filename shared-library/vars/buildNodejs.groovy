/**
 * Node.js / PM2 前端或 SSR 服务构建
 *
 * Docker 多阶段构建会执行 npm ci 和可选的 npm run build，
 * 运行阶段由 pm2-runtime 执行 package.json 的 start script。
 *
 * 因此这里不需要在 Jenkins 节点安装 Node.js，
 * 构建逻辑全在 Dockerfile 中完成。
 *
 * 如果业务仓库没有 Dockerfile，会使用 Dockerfile.nodejs-pm2。
 */
def call(Map config) {
    echo "🌐 Node.js 项目: Docker 内构建，PM2 runtime 运行"
    // 前端构建在 Docker build 阶段完成，这里只做预检查
    if (!fileExists('package.json')) {
        error "❌ 未找到 package.json，请确认代码仓库结构"
    }
    echo "✅ 前端项目预检查通过"
}
