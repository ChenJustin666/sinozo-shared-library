# Git 工作流程与分支管理

> **适用于：20+ 开发、1000+ Pod 规模**

---

## 一、k8s-deploy 仓库分支策略

### 1.1 分支结构

```
k8s-deploy/
├── main                    # 主分支（保护分支）
├── dev                     # 开发分支（可选）
└── feature/*               # 功能分支（开发提 MR 用）
```

### 1.2 分支保护规则

```yaml
# GitLab/GitHub 分支保护设置
main:
  - 保护分支
  - 不允许直接 push
  - 需要 Merge Request
  - 需要 Code Review
  
  # 路径级别保护
  projects/*/prod/**:
    - 只有运维（Maintainer）可以 approve
  
  projects/*/test/**:
    - 开发（Developer）可以 approve
    - 或配置自动 merge
  
  projects/*/dev/**:
    - 开发（Developer）可以 approve
    - 或配置自动 merge
```

---

## 二、开发人员工作流程

### 2.1 改测试环境配置（完整流程）

#### Step 1：Fork 或 Clone k8s-deploy 仓库

```bash
# 方式 A：Fork（推荐大团队）
# 在 GitLab/GitHub 上点击 Fork
git clone git@git.com:your-name/k8s-deploy.git
cd k8s-deploy
git remote add upstream git@git.com:ops/k8s-deploy.git

# 方式 B：直接 Clone（推荐小团队）
git clone git@git.com:ops/k8s-deploy.git
cd k8s-deploy
```

#### Step 2：创建功能分支

```bash
# 从 main 分支创建
git checkout main
git pull origin main

# 创建功能分支（命名规范）
git checkout -b feature/ad-gateway-increase-memory
# 或
git checkout -b fix/ad-gateway-env-config
```

#### Step 3：修改配置

```bash
# 修改测试环境配置
vim projects/adv/test/ad-gateway/values-test.yaml

# 示例：增加内存
resources:
  limits:
    memory: 2Gi      # 原来 1Gi
```

#### Step 4：提交更改

```bash
# 查看修改
git status
git diff

# 提交
git add projects/adv/test/ad-gateway/values-test.yaml
git commit -m "feat(adv/ad-gateway): 增加测试环境内存到 2Gi

- 原因：测试环境压测发现内存不足
- 影响：测试环境 ad-gateway 服务
- 测试：本地 helm lint 通过
"

# 推送到远程
git push origin feature/ad-gateway-increase-memory
```

#### Step 5：创建 Merge Request

**GitLab：**
```
1. 打开 GitLab → k8s-deploy 仓库
2. 点击 "Create merge request"
3. 填写信息：
   Title: feat(adv/ad-gateway): 增加测试环境内存到 2Gi
   Description: 
     - 原因：测试环境压测发现内存不足
     - 修改内容：memory 1Gi → 2Gi
     - 影响范围：adv-test/ad-gateway
   Source branch: feature/ad-gateway-increase-memory
   Target branch: main
   Assignee: @ops-team（或留空）
4. 点击 "Create merge request"
```

**GitHub：**
```
1. 打开 GitHub → k8s-deploy 仓库
2. 点击 "Pull requests" → "New pull request"
3. 选择分支：
   base: main
   compare: feature/ad-gateway-increase-memory
4. 填写标题和描述
5. 点击 "Create pull request"
```

#### Step 6：等待自动检查和合并

**自动化流程：**
```
1. CI Pipeline 自动运行：
   ✓ Helm lint 检查
   ✓ 资源配额检查
   ✓ 安全检查
   ✓ YAML 格式检查

2. 测试环境配置：
   ✓ 自动 approve（如果配置了）
   ✓ 自动 merge 到 main
   ✓ 触发 Jenkins 自动部署

3. 生产环境配置：
   ⏳ 等待运维 review
   ⏳ 运维 approve
   ✓ Merge 到 main
   ⏳ 运维手动触发部署
```

#### Step 7：验证部署

```bash
# 查看 Jenkins Job 状态
# 或查看 Pod 状态
kubectl get pods -n adv-test -l app=ad-gateway
```

### 2.2 改开发环境配置（同上）

流程完全相同，只是修改 `projects/adv/dev/ad-gateway/values-dev.yaml`

---

## 三、自动合并配置（测试/开发环境）

### 3.1 GitLab CI 自动合并

```yaml
# .gitlab-ci.yml
auto-merge-test:
  stage: deploy
  script:
    - |
      # 检查是否是测试环境配置的 MR
      if echo "$CI_MERGE_REQUEST_SOURCE_BRANCH_NAME" | grep -E "projects/.*/test/"; then
        # 自动检查通过后 merge
        if [ "$CI_PIPELINE_STATUS" == "success" ]; then
          curl -X PUT "${CI_API_V4_URL}/projects/${CI_PROJECT_ID}/merge_requests/${CI_MERGE_REQUEST_IID}/merge" \
            --header "PRIVATE-TOKEN: ${GITLAB_TOKEN}"
        fi
      fi
  only:
    - merge_requests
  when: on_success
```

### 3.2 GitHub Actions 自动合并

```yaml
# .github/workflows/auto-merge.yml
name: Auto Merge Test Config
on:
  pull_request:
    types: [opened, synchronize]
    paths:
      - 'projects/*/test/**'
      - 'projects/*/dev/**'

jobs:
  auto-merge:
    runs-on: ubuntu-latest
    steps:
      - name: Auto merge
        if: github.event.pull_request.head.ref contains 'test' || contains 'dev'
        uses: pascalgn/automerge-action@v0.15.6
        env:
          GITHUB_TOKEN: "${{ secrets.GITHUB_TOKEN }}"
          MERGE_LABELS: ""
          MERGE_METHOD: "squash"
```

---

## 四、运维人员工作流程

### 4.1 改生产环境配置

```bash
# 1. 直接在 main 分支操作（运维有权限）
cd k8s-deploy
git checkout main
git pull

# 2. 修改生产配置
vim projects/adv/prod/ad-gateway/values-prod.yaml

# 3. 提交
git add projects/adv/prod/ad-gateway/values-prod.yaml
git commit -m "feat(adv/ad-gateway): 扩容生产环境到 5 副本"
git push origin main

# 4. 触发 Jenkins 部署
# Jenkins → ad-gateway-prod → Build
```

### 4.2 Review 开发的 MR

```
1. 收到 MR 通知
2. 查看修改内容：
   - 资源配置是否合理
   - 是否会影响其他服务
   - CI 检查是否通过
3. 如果 OK：点击 "Approve" → "Merge"
4. 如果有问题：留言要求修改
```

### 4.3 紧急修复

```bash
# 1. 创建 hotfix 分支
git checkout -b hotfix/ad-gateway-memory-leak main

# 2. 修改配置
vim projects/adv/prod/ad-gateway/values-prod.yaml

# 3. 提交并推送
git commit -am "hotfix: 临时降低 ad-gateway 副本数"
git push origin hotfix/ad-gateway-memory-leak

# 4. 创建 MR（紧急）
# 标题加 [URGENT]

# 5. 自己 approve 并 merge

# 6. 立即部署
```

---

## 五、分支命名规范

### 5.1 功能分支

```
feature/项目-服务-功能描述
示例：
  feature/adv-ad-gateway-increase-memory
  feature/fcm-service-add-env
```

### 5.2 修复分支

```
fix/项目-服务-问题描述
示例：
  fix/adv-ad-gateway-env-config
  fix/fcm-service-probe-timeout
```

### 5.3 紧急修复

```
hotfix/项目-服务-问题描述
示例：
  hotfix/adv-ad-gateway-memory-leak
  hotfix/fcm-service-crash
```

---

## 六、Commit 消息规范

### 6.1 格式

```
<type>(<scope>): <subject>

<body>

<footer>
```

### 6.2 Type 类型

```
feat:     新功能
fix:      修复
docs:     文档
style:    格式（不影响代码运行）
refactor: 重构
test:     测试
chore:    构建过程或辅助工具的变动
```

### 6.3 示例

```bash
# 好的 commit
git commit -m "feat(adv/ad-gateway): 增加测试环境内存到 2Gi

- 原因：压测发现内存不足
- 修改：memory 1Gi → 2Gi
- 影响：adv-test namespace
"

# 不好的 commit
git commit -m "update config"
git commit -m "fix bug"
```

---

## 七、常见问题

### Q1: 我的 MR 一直没有被 merge？

**A:** 检查：
1. CI 检查是否通过？
2. 是否修改了生产配置（需要运维 approve）？
3. 是否有冲突需要解决？

### Q2: 如何解决冲突？

```bash
# 1. 更新 main 分支
git checkout main
git pull origin main

# 2. 切换到功能分支
git checkout feature/your-branch

# 3. Rebase
git rebase main

# 4. 解决冲突
# 编辑冲突文件
git add .
git rebase --continue

# 5. 强制推送
git push origin feature/your-branch --force
```

### Q3: 测试环境配置多久会自动部署？

**A:** 
- MR merge 后 → 立即触发 Jenkins
- Jenkins 构建 → 3-5 分钟
- 总计：5-10 分钟

### Q4: 我可以直接 push 到 main 吗？

**A:** 
- 开发：❌ 不可以，必须提 MR
- 运维：✅ 可以（但建议也走 MR 流程）

---

## 八、最佳实践

### 8.1 提 MR 前

```bash
# 1. 本地验证
helm lint charts/generic-service -f projects/adv/test/ad-gateway/values-test.yaml

# 2. 查看 diff
git diff main...feature/your-branch

# 3. 确保只修改了需要修改的文件
git status
```

### 8.2 MR 描述模板

```markdown
## 修改内容
- 增加 ad-gateway 测试环境内存到 2Gi

## 修改原因
- 压测发现内存不足，OOM 频繁

## 影响范围
- 项目：adv
- 环境：test
- 服务：ad-gateway

## 测试
- [x] 本地 helm lint 通过
- [x] 资源配额检查通过
- [ ] 部署后验证（merge 后）

## 相关链接
- 压测报告：http://...
- 监控面板：http://grafana.../ad-gateway
```

### 8.3 Code Review 检查清单

运维 review 时检查：
```
□ 资源配置是否合理（不要申请过多）
□ 是否会影响其他服务
□ 命名是否规范
□ CI 检查是否全部通过
□ Commit 消息是否清晰
```

---

## 九、总结

### 开发人员

```
1. Fork/Clone k8s-deploy
2. 创建 feature 分支
3. 修改 projects/{project}/{env}/{service}/values-{env}.yaml
4. Commit + Push
5. 创建 MR
6. 等待自动检查 + 自动 merge（测试环境）
7. 验证部署
```

### 运维人员

```
1. Review 开发的 MR（生产环境）
2. Approve + Merge
3. 或直接修改 main 分支（生产配置）
4. 触发 Jenkins 部署
```

### 自动化

```
测试/开发环境：
  MR → CI 检查 → 自动 merge → 自动部署

生产环境：
  MR → CI 检查 → 运维 approve → Merge → 运维手动部署
```
