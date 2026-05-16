# 进阶配置 - Webhook 自动构建与 Secret 管理

> 本文档解决两个进阶需求：
> 1. 如何使用 K8s Secret 管理 Nacos 密码（而不是明文写在 values.yaml）
> 2. 如何配置 Gitea Webhook 实现代码提交自动构建

---

## 第一部分：使用 K8s Secret 管理敏感信息

### 1.1 问题：密码不应该明文存储

**当前做法（不安全）：**
```yaml
# values-test.yaml
java:
  opts: >-
    -Dspring.cloud.nacos.config.password=nacos123    # ❌ 明文密码
```

**改进方案：使用 K8s Secret**

### 1.2 创建 Nacos Secret

在每个命名空间创建 Secret：

```bash
# 测试环境
kubectl create secret generic nacos-secret \
  --from-literal=username=nacos \
  --from-literal=password='your-nacos-password' \
  -n adv-test

# 生产环境
kubectl create secret generic nacos-secret \
  --from-literal=username=nacos \
  --from-literal=password='your-nacos-prod-password' \
  -n adv-prod
```

或者使用 YAML 文件（密码需要 base64 编码）：

```bash
# 生成 base64 编码
echo -n 'your-nacos-password' | base64
# 输出：eW91ci1uYWNvcy1wYXNzd29yZA==
```

创建 Secret YAML：

```yaml
# nacos-secret.yaml
apiVersion: v1
kind: Secret
metadata:
  name: nacos-secret
  namespace: adv-test
type: Opaque
data:
  username: bmFjb3M=                          # nacos 的 base64
  password: eW91ci1uYWNvcy1wYXNzd29yZA==      # 你的密码的 base64
```

应用：
```bash
kubectl apply -f nacos-secret.yaml
```

### 1.3 修改 Helm Chart 支持 Secret

编辑 `charts/generic-service/templates/deployment.yaml`，添加环境变量注入：

```yaml
{{- if .Values.java.enabled }}
env:
  - name: JAVA_OPTS
    value: {{ .Values.java.opts | quote }}
  
  # 从 Secret 注入 Nacos 凭据
  {{- if .Values.java.nacosSecret }}
  - name: NACOS_USERNAME
    valueFrom:
      secretKeyRef:
        name: {{ .Values.java.nacosSecret }}
        key: username
  - name: NACOS_PASSWORD
    valueFrom:
      secretKeyRef:
        name: {{ .Values.java.nacosSecret }}
        key: password
  {{- end }}
{{- end }}
```

### 1.4 修改 values.yaml 配置

```yaml
# projects/adv/test/ad-gateway/values-test.yaml

java:
  enabled: true
  nacosSecret: nacos-secret    # 引用 Secret 名称
  opts: >-
    -Dspring.application.name=ad-gateway
    -Dspring.profiles.active=test
    -Dspring.cloud.nacos.config.server-addr=nacos.internal:8848
    -Dspring.cloud.nacos.config.namespace=ad-gateway-test
    -Dspring.cloud.nacos.config.username=${NACOS_USERNAME}
    -Dspring.cloud.nacos.config.password=${NACOS_PASSWORD}
    -Dspring.cloud.nacos.discovery.server-addr=nacos.internal:8848
    -Dspring.cloud.nacos.discovery.namespace=ad-gateway-test
    -Xms512m -Xmx1024m
```

**关键点**：
- 密码改为 `${NACOS_USERNAME}` 和 `${NACOS_PASSWORD}` 环境变量
- 添加 `nacosSecret: nacos-secret` 配置
- Secret 在 K8s 中管理，不提交到 Git

### 1.5 批量创建 Secret 脚本

创建一个脚本来批量创建 Secret：

```bash
#!/bin/bash
# automation/create-nacos-secrets.sh

NACOS_TEST_PASSWORD="test-password-here"
NACOS_PROD_PASSWORD="prod-password-here"

# 测试环境命名空间列表
TEST_NAMESPACES=("adv-test" "fcm-test")

# 生产环境命名空间列表
PROD_NAMESPACES=("adv-prod" "fcm-prod")

echo "创建测试环境 Nacos Secret..."
for ns in "${TEST_NAMESPACES[@]}"; do
    kubectl create namespace $ns --dry-run=client -o yaml | kubectl apply -f -
    kubectl create secret generic nacos-secret \
        --from-literal=username=nacos \
        --from-literal=password="$NACOS_TEST_PASSWORD" \
        -n $ns \
        --dry-run=client -o yaml | kubectl apply -f -
    echo "✅ $ns"
done

echo ""
echo "创建生产环境 Nacos Secret..."
for ns in "${PROD_NAMESPACES[@]}"; do
    kubectl create namespace $ns --dry-run=client -o yaml | kubectl apply -f -
    kubectl create secret generic nacos-secret \
        --from-literal=username=nacos \
        --from-literal=password="$NACOS_PROD_PASSWORD" \
        -n $ns \
        --dry-run=client -o yaml | kubectl apply -f -
    echo "✅ $ns"
done

echo ""
echo "✅ 所有 Secret 创建完成！"
```

使用：
```bash
chmod +x automation/create-nacos-secrets.sh
./automation/create-nacos-secrets.sh
```

### 1.6 验证 Secret

```bash
# 查看 Secret
kubectl get secret nacos-secret -n adv-test

# 查看 Secret 内容（base64 解码）
kubectl get secret nacos-secret -n adv-test -o jsonpath='{.data.password}' | base64 -d
```

---

## 第二部分：Gitea Webhook 自动构建

### 2.1 Jenkins 配置

#### 安装 Gitea Plugin

进入 Jenkins → Manage Jenkins → Manage Plugins → Available

搜索并安装：
```
✅ Gitea Plugin
✅ Generic Webhook Trigger Plugin
```

重启 Jenkins。

#### 配置 Jenkins Job 支持 Webhook

编辑 Jenkins Job（如 `ad-gateway-test`）：

**方式一：使用 Gitea Plugin**

在 Job 配置中：

```
Build Triggers:
  ✅ Gitea webhook
  
  或者
  
  ✅ Generic Webhook Trigger
    Token: ad-gateway-test-webhook-token    # 自定义 token
```

**方式二：使用 Generic Webhook Trigger（推荐，更灵活）**

在 Pipeline 脚本开头添加：

```groovy
@Library('k8s-deploy-lib@main') _

// 配置 Webhook 触发器
properties([
    pipelineTriggers([
        GenericTrigger(
            genericVariables: [
                [key: 'ref', value: '$.ref'],
                [key: 'repository_name', value: '$.repository.name']
            ],
            causeString: 'Triggered by Gitea push',
            token: 'ad-gateway-test-webhook-token',
            printContributedVariables: true,
            printPostContent: true,
            regexpFilterText: '$ref',
            regexpFilterExpression: '^refs/heads/(main|test)$'    // 只触发 main 和 test 分支
        )
    ])
])

k8sDeploy(
    projectName:  'adv',
    serviceName:  'ad-gateway',
    // ... 其他配置
)
```

### 2.2 Gitea 配置 Webhook

#### 在 Gitea 仓库中添加 Webhook

进入 Gitea 仓库 → Settings → Webhooks → Add Webhook → Gitea

```
Target URL: http://jenkins.example.com/generic-webhook-trigger/invoke?token=ad-gateway-test-webhook-token

HTTP Method: POST

POST Content Type: application/json

Secret: (留空或设置一个密钥)

Trigger On:
  ✅ Push events
  
Active: ✅
```

**URL 格式说明**：
- 使用 Gitea Plugin：`http://jenkins.example.com/gitea-webhook/post`
- 使用 Generic Webhook：`http://jenkins.example.com/generic-webhook-trigger/invoke?token=<your-token>`

#### 测试 Webhook

在 Gitea Webhook 页面点击 "Test Delivery"，应该看到：
```
✅ 200 OK
```

然后在 Jenkins 中应该自动触发了一次构建。

### 2.3 配置分支过滤

只在特定分支触发构建：

```groovy
properties([
    pipelineTriggers([
        GenericTrigger(
            genericVariables: [
                [key: 'ref', value: '$.ref'],
                [key: 'pusher_name', value: '$.pusher.username']
            ],
            token: 'ad-gateway-test-webhook-token',
            regexpFilterText: '$ref',
            regexpFilterExpression: '^refs/heads/(main|develop|test)$'  // 只触发这些分支
        )
    ])
])
```

### 2.4 多环境 Webhook 配置

**测试环境**：监听 `test` 分支
```groovy
// Jenkinsfile (测试环境)
properties([
    pipelineTriggers([
        GenericTrigger(
            token: 'ad-gateway-test-webhook',
            regexpFilterExpression: '^refs/heads/test$'
        )
    ])
])
```

**生产环境**：监听 `main` 分支
```groovy
// Jenkinsfile (生产环境)
properties([
    pipelineTriggers([
        GenericTrigger(
            token: 'ad-gateway-prod-webhook',
            regexpFilterExpression: '^refs/heads/main$'
        )
    ])
])
```

### 2.5 完整的 Jenkinsfile 示例（支持 Webhook）

```groovy
@Library('k8s-deploy-lib@main') _

// Webhook 触发配置
properties([
    pipelineTriggers([
        GenericTrigger(
            genericVariables: [
                [key: 'ref', value: '$.ref'],
                [key: 'commit_message', value: '$.commits[0].message'],
                [key: 'pusher', value: '$.pusher.username']
            ],
            causeString: 'Triggered by $pusher: $commit_message',
            token: 'ad-gateway-test-webhook-token',
            printContributedVariables: true,
            printPostContent: false,
            regexpFilterText: '$ref',
            regexpFilterExpression: '^refs/heads/(main|test)$'
        )
    ])
])

k8sDeploy(
    projectName:  'adv',
    serviceName:  'ad-gateway',
    serviceType:  'java',
    gitUrl:       'http://gitea.example.com/server/AdGateway.git',
    gitCredId:    'git-adv-cred',
    dockerImage:  'sinozo/ad-gateway',
    dockerCredId: 'docker-swr-cred',
    jdkTool:      'jdk 1.8',
)
```

### 2.6 Gitea 认证配置

如果 Gitea 需要认证才能访问 Webhook：

在 Jenkins 中配置 Gitea Server：

```
Manage Jenkins → Configure System → Gitea Servers

Name: Gitea
Server URL: http://gitea.example.com
Credentials: (添加 Gitea 的 API Token)
```

### 2.7 调试 Webhook

#### 查看 Webhook 日志

Gitea 仓库 → Settings → Webhooks → 点击 Webhook → Recent Deliveries

可以看到：
- Request Headers
- Request Body
- Response

#### Jenkins 日志

```bash
# 查看 Jenkins 日志
tail -f /var/log/jenkins/jenkins.log

# 或者在 Jenkins UI 中
Manage Jenkins → System Log → Add new log recorder
  Name: Webhook Debug
  Loggers:
    - org.jenkinsci.plugins.gwt (ALL)
```

### 2.8 安全建议

1. **使用 Token 认证**
   ```
   每个 Job 使用不同的 token
   Token 要足够复杂：ad-gateway-test-$(openssl rand -hex 16)
   ```

2. **限制 IP 访问**
   ```bash
   # 在 Jenkins 前面的 Nginx 配置
   location /generic-webhook-trigger/ {
       allow 192.168.1.100;  # Gitea 服务器 IP
       deny all;
       proxy_pass http://jenkins:8080;
   }
   ```

3. **使用 Secret 验证**
   ```groovy
   GenericTrigger(
       token: 'your-token',
       genericHeaderVariables: [
           [key: 'X-Gitea-Signature', regexpFilter: '']
       ]
   )
   ```

---

## 第三部分：完整工作流示例

### 3.1 开发提交代码自动部署到测试环境

```
开发本地:
  git add .
  git commit -m "feat: 新功能"
  git push origin test
    ↓
Gitea:
  触发 Webhook
    ↓
Jenkins (ad-gateway-test):
  1. 自动触发构建
  2. 拉取 test 分支代码
  3. Maven 构建
  4. Docker 构建并推送
  5. Helm 部署到 adv-test 命名空间
  6. 健康检查
    ↓
测试环境:
  ✅ 新版本自动部署完成
```

### 3.2 生产环境手动审批

```
测试通过后:
  git checkout main
  git merge test
  git push origin main
    ↓
Gitea:
  触发 Webhook
    ↓
Jenkins (ad-gateway-prod):
  1. 自动触发构建
  2. 权限检查（需要运维审批）
  3. 等待人工确认...
    ↓
运维审批:
  Jenkins → ad-gateway-prod → 点击 "Proceed"
    ↓
继续部署:
  4. 拉取 main 分支代码
  5. Maven 构建
  6. Docker 构建并推送
  7. Helm 部署到 adv-prod 命名空间
  8. 健康检查
    ↓
生产环境:
  ✅ 新版本部署完成
```

### 3.3 配置生产环境人工审批

修改 `shared-library/vars/checkPermission.groovy`：

```groovy
def call(String env) {
    if (env == 'prod') {
        echo "⚠️  生产环境部署需要审批"
        
        timeout(time: 30, unit: 'MINUTES') {
            input(
                message: "确认部署到生产环境？",
                ok: "确认部署",
                submitter: "admin,ops-team",  // 只有这些用户可以审批
                parameters: [
                    string(
                        name: 'APPROVER',
                        description: '审批人姓名',
                        defaultValue: ''
                    )
                ]
            )
        }
        
        echo "✅ 生产部署已获批准"
    } else {
        echo "✅ ${env} 环境，无需审批"
    }
}
```

---

## 第四部分：最佳实践

### 4.1 Secret 管理最佳实践

```
✅ 所有密码都用 K8s Secret
✅ Secret 不提交到 Git
✅ 使用 RBAC 限制 Secret 访问
✅ 定期轮换密码
✅ 生产和测试使用不同的密码
```

### 4.2 Webhook 最佳实践

```
✅ 每个 Job 使用不同的 token
✅ 使用分支过滤避免不必要的构建
✅ 测试环境自动部署，生产环境人工审批
✅ 配置构建通知（邮件/钉钉/企业微信）
✅ 保留 Webhook 日志用于调试
```

### 4.3 多环境部署策略

```
开发环境 (dev):
  - 自动部署
  - 监听 develop 分支
  - 无需审批

测试环境 (test):
  - 自动部署
  - 监听 test 分支
  - 无需审批

预发环境 (pre):
  - 手动触发
  - 监听 main 分支
  - 需要测试人员审批

生产环境 (prod):
  - 手动触发或自动触发+审批
  - 监听 main 分支
  - 需要运维审批
```

---

## 第五部分：故障排查

### 5.1 Webhook 不触发

```bash
# 1. 检查 Gitea Webhook 配置
Gitea → Settings → Webhooks → Recent Deliveries
  查看是否有请求记录

# 2. 检查 Jenkins 日志
tail -f /var/log/jenkins/jenkins.log | grep webhook

# 3. 手动测试 Webhook
curl -X POST \
  "http://jenkins.example.com/generic-webhook-trigger/invoke?token=your-token" \
  -H "Content-Type: application/json" \
  -d '{"ref":"refs/heads/main"}'

# 4. 检查防火墙
# 确保 Gitea 能访问 Jenkins
```

### 5.2 Secret 注入失败

```bash
# 1. 检查 Secret 是否存在
kubectl get secret nacos-secret -n adv-test

# 2. 检查 Secret 内容
kubectl describe secret nacos-secret -n adv-test

# 3. 检查 Pod 环境变量
kubectl exec -it <pod-name> -n adv-test -- env | grep NACOS

# 4. 查看 Pod 事件
kubectl describe pod <pod-name> -n adv-test
```

### 5.3 分支过滤不生效

```groovy
// 调试：打印所有变量
GenericTrigger(
    printContributedVariables: true,
    printPostContent: true,
    // ...
)

// 查看 Jenkins 构建日志，会显示：
// ref = refs/heads/main
// 然后检查正则表达式是否匹配
```

---

## 总结

通过本文档，你学会了：

✅ 使用 K8s Secret 管理 Nacos 密码（不再明文存储）  
✅ 配置 Gitea Webhook 实现代码提交自动构建  
✅ 配置分支过滤和多环境部署策略  
✅ 配置生产环境人工审批流程  
✅ 排查 Webhook 和 Secret 相关问题  

**下一步**：
1. 创建 Nacos Secret：`./automation/create-nacos-secrets.sh`
2. 修改 values.yaml 使用 Secret
3. 配置 Gitea Webhook
4. 测试自动构建流程

如有问题，请参考 [04-完整部署实战指南.md](04-完整部署实战指南.md)。
