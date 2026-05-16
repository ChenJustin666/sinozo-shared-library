/**
 * 权限检查
 *
 * 分级权限：
 *   - dev:  所有人可触发
 *   - test: 所有人可触发
 *   - prod: 仅 DEVOPS_USERS 白名单可触发（人工审批由 prodApproval() 在另一个 stage 处理）
 *
 * 权限来源：Jenkins 全局环境变量 DEVOPS_USERS（逗号分隔的用户名）
 *   配置位置：Manage Jenkins → Configure System → Global properties → Environment variables
 *   示例：DEVOPS_USERS=admin,ops-zhang,ops-li
 */
def call(String deployEnv) {
    if (deployEnv == 'dev' || deployEnv == 'test') {
        echo "✅ ${deployEnv} 环境，权限检查通过（无需特殊权限）"
        return
    }

    // ── prod 环境，检查白名单 ──
    def currentUser = getCurrentUser()
    def devopsUsers = (env.DEVOPS_USERS ?: 'admin')
                       .split(',')
                       .collect { it.trim() }
                       .findAll { it }

    echo "🔒 prod 触发者: ${currentUser}, 白名单: ${devopsUsers}"

    if (!devopsUsers.contains(currentUser)) {
        error """❌ 用户 '${currentUser}' 无权触发生产部署！

允许的用户：${devopsUsers}

如需添加权限：
  联系平台组管理员，更新 Jenkins 全局环境变量 DEVOPS_USERS
"""
    }

    echo "✅ 生产环境触发权限检查通过: ${currentUser}"
}

/**
 * 获取当前触发用户
 */
def getCurrentUser() {
    try {
        def cause = currentBuild.rawBuild.getCause(hudson.model.Cause$UserIdCause)
        return cause ? cause.getUserId() : 'system'
    } catch (e) {
        return 'unknown'
    }
}
