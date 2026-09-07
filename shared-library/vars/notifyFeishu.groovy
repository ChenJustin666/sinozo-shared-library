/**
 * 飞书机器人通知（区分测试/生产，美观卡片）
 *
 * ═══ 通知策略 ═══
 *   - 测试环境：仅失败时通知（减少噪音）
 *   - 生产环境：成功和失败都通知（全量感知）
 *   - 可通过 notifyOnSuccess 覆盖默认策略
 *
 * ═══ Webhook 解析优先级 ═══
 *   1. 调用时显式传入 webhook
 *   2. Jenkins 全局环境变量 FEISHU_WEBHOOK_<ENV>（如 FEISHU_WEBHOOK_PROD）
 *   3. Jenkins 全局环境变量 FEISHU_WEBHOOK（兜底）
 *   4. 以上都没有 → 跳过通知，不影响流程
 *
 * ═══ 使用方式 ═══
 *   notifyFeishu(
 *       status:  'success',          // 'success' | 'failure'
 *       service: cfg.serviceName,
 *       env:     params.DEPLOY_ENV,  // 'test' | 'dev' | 'prod'
 *       action:  params.ACTION,      // 'deploy' | 'rollback' | 'restart'
 *       tag:     env.DOCKER_TAG,
 *       webhook: cfg.feishuWebhook,  // 可选，显式指定
 *   )
 */
def call(Map config) {
    def deployEnv = config.env ?: 'unknown'
    def status    = config.status ?: 'unknown'
    def isProd    = deployEnv == 'prod'

    // ── 通知策略：测试环境默认只通知失败，生产全量通知 ──
    def notifyOnSuccess = config.notifyOnSuccess
    if (notifyOnSuccess == null) {
        notifyOnSuccess = isProd  // 生产成功也通知，测试成功不通知
    }
    if (status == 'success' && !notifyOnSuccess) {
        echo "ℹ️  ${deployEnv} 环境成功不通知（可通过 notifyOnSuccess: true 覆盖）"
        return
    }

    // ── 解析 webhook ──
    def webhook = resolveWebhook(config.webhook, deployEnv)
    if (!webhook) {
        echo "ℹ️  未配置飞书webhook，跳过通知"
        echo "    配置方式：Jenkins 全局环境变量 FEISHU_WEBHOOK 或 FEISHU_WEBHOOK_${deployEnv.toUpperCase()}"
        return
    }

    // ── 构建卡片并发送 ──
    def card = buildCard(config, isProd)
    sendCard(webhook, card)
}

/**
 * 按优先级解析 webhook
 */
private String resolveWebhook(String explicit, String deployEnv) {
    // 优先级 1：显式传入
    if (explicit?.trim()) return explicit.trim()

    // 优先级 2：环境专用 webhook（FEISHU_WEBHOOK_PROD / FEISHU_WEBHOOK_TEST）
    def envKey = "FEISHU_WEBHOOK_${deployEnv.toUpperCase()}"
    def envSpecific = env."${envKey}"
    if (envSpecific?.trim()) return envSpecific.trim()

    // 优先级 3：全局兜底
    if (env.FEISHU_WEBHOOK?.trim()) return env.FEISHU_WEBHOOK.trim()

    return null
}

/**
 * 构建飞书消息卡片
 */
private Map buildCard(Map config, boolean isProd) {
    def status    = config.status ?: 'unknown'
    def service   = config.service ?: 'N/A'
    def deployEnv = config.env ?: 'N/A'
    def action    = config.action ?: 'deploy'
    def tag       = config.tag ?: 'N/A'
    def buildUrl  = config.buildUrl ?: env.BUILD_URL ?: ''
    def buildNum  = env.BUILD_NUMBER ?: ''
    def triggeredBy = env.BUILD_USER_ID ?: env.BUILD_USER ?: 'CI'

    def isSuccess = status == 'success'

    // ── 操作类型中文 ──
    def actionMap = ['deploy': '部署', 'rollback': '回滚', 'restart': '重启']
    def actionText = actionMap[action] ?: action

    // ── 环境标签样式 ──
    def envLabel = isProd ? '🔴 生产环境' : "🟢 ${deployEnv.toUpperCase()} 环境"

    // ── 标题 ──
    def emoji = isSuccess ? '✅' : '❌'
    def title = "${emoji} ${service} ${actionText}${isSuccess ? '成功' : '失败'}"

    // ── 卡片颜色：生产失败用红色，生产成功用蓝色，测试失败用橙色 ──
    def color
    if (isProd) {
        color = isSuccess ? 'blue' : 'red'
    } else {
        color = isSuccess ? 'green' : 'orange'
    }

    // ── 卡片内容 ──
    def elements = []

    // 环境标签（醒目）
    elements << [
        tag: 'div',
        text: [
            tag: 'lark_md',
            content: "**${envLabel}**"
        ]
    ]

    // 分隔线
    elements << [tag: 'hr']

    // 详细信息（两列布局）
    elements << [
        tag: 'div',
        fields: [
            [is_short: true, text: [tag: 'lark_md', content: "**服务名称**\n${service}"]],
            [is_short: true, text: [tag: 'lark_md', content: "**操作类型**\n${actionText}"]],
            [is_short: true, text: [tag: 'lark_md', content: "**镜像Tag**\n`${tag}`"]],
            [is_short: true, text: [tag: 'lark_md', content: "**触发人**\n${triggeredBy}"]],
        ]
    ]

    // 时间行
    elements << [
        tag: 'div',
        text: [
            tag: 'lark_md',
            content: "**时间**　${new Date().format('yyyy-MM-dd HH:mm:ss')}　　**构建**　[#${buildNum}](${buildUrl})"
        ]
    ]

    // 失败时添加提示
    if (!isSuccess) {
        elements << [tag: 'hr']
        def hint = isProd
            ? '⚠️ 生产环境操作失败，请立即排查！点击上方构建链接查看日志。'
            : '💡 测试环境失败，请检查构建日志和应用配置。'
        elements << [
            tag: 'div',
            text: [
                tag: 'lark_md',
                content: hint
            ]
        ]
    }

    // 操作按钮
    elements << [
        tag: 'action',
        actions: [
            [
                tag: 'button',
                text: [tag: 'plain_text', content: '查看构建日志'],
                url: buildUrl,
                type: isSuccess ? 'default' : 'primary'
            ]
        ]
    ]

    return [
        msg_type: 'interactive',
        card: [
            header: [
                title: [content: title, tag: 'plain_text'],
                template: color
            ],
            elements: elements
        ]
    ]
}

/**
 * 发送卡片到飞书
 */
private void sendCard(String webhook, Map card) {
    def payload = groovy.json.JsonOutput.toJson(card)
    // 写入临时文件避免 shell 转义问题
    writeFile file: '.feishu_payload.json', text: payload

    try {
        def response = sh(
            script: """
                curl -X POST '${webhook}' \\
                  -H 'Content-Type: application/json' \\
                  -d @.feishu_payload.json \\
                  --connect-timeout 5 \\
                  --max-time 10 \\
                  -s -o /dev/null -w '%{http_code}'
            """,
            returnStdout: true
        ).trim()

        if (response == '200') {
            echo "✅ 飞书通知已发送"
        } else {
            echo "⚠️  飞书返回 HTTP ${response}，请检查 webhook 配置"
        }
    } catch (Exception e) {
        // 通知失败绝不阻塞主流程
        echo "⚠️  飞书通知发送失败: ${e.message}（不影响部署结果）"
    } finally {
        sh 'rm -f .feishu_payload.json'
    }
}
