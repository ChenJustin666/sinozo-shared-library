# 项目级覆盖（baselines/projects/）

## 这个目录是干啥的

`baselines/_global.yaml` 是**全局基线**，所有项目都加载。
但有些项目的基础设施跟全局不一样，比如：

- 历史 namespace 命名（`adv-ops-test` 不是 `adv-test`）
- 镜像 SWR organization 命名（`pay-test` 不是 `sinozo-test`）
- 镜像 registry / region（不同业务线不同 region）
- pullSecret 名（不同 namespace 用不同的 secret）

这些**项目级差异**写在 `baselines/projects/<project>/_overrides.yaml` 里。

## 加载链

```
① charts/generic-service/values.yaml             chart 默认
② baselines/_global.yaml                         全局基线（必加载）
③ baselines/projects/<project>/_overrides.yaml   项目级（存在才加载） ← 本目录
④ <业务仓库>/deploy/values.yaml                  业务通用
⑤ <业务仓库>/deploy/values-<env>.yaml            业务环境
⑥ baselines/projects/<project>/<svc>/...     prod 运维管
⑦ helm --set image.tag ...                      CI 注入
```

Helm `-f` 后置覆盖：项目级写了的 key 覆盖全局，不写的字段继承全局。

## 默认 90% 项目用不上这个目录

如果你的项目按规范命名（`<project>-<env>` namespace + 跟全局一致的 SWR project），
**不需要建任何文件**，自动走 `_global.yaml` 默认。

## 什么时候用

✅ 应该用：
- 历史 namespace 命名跟规范不一致（不能改 / 不能删）
- 项目走独立的 SWR organization（如 `pay-test/pay-prod`）
- 项目走不同 SWR region

❌ 不应该用：
- 写业务字段（端口、resources、env、JVM）→ 应该写到 `<业务仓库>/deploy/`
- 写 prod 配置（副本数、HPA、PDB）→ 应该写到 `baselines/projects/<project>/<svc>/`

## 完整模板（按需保留）

```yaml
# baselines/projects/<project>/_overrides.yaml
# <project> 项目特殊基础设施配置（覆盖 baselines/_global.yaml）
# 不写的字段继承全局

# ── 镜像基础设施（按需写，不写继承全局）──
image:
  # 不同 region：覆盖全局 swr.ap-southeast-3
  # registry: swr.cn-north-4.myhuaweicloud.com

  # 不同 SWR organization：覆盖全局 sinozo-test/sinozo-prod
  projects:
    test: <project>-test
    prod: <project>-prod

  # 不同 pullSecret 名：覆盖全局 regcred
  # pullSecret: <project>-regcred

# ── K8s namespace 命名（按需写，不写默认 {project}-{env}）──
namespaces:
  test: <project>-ops-test    # 例：adv-ops-test
  prod: <project>-ops-prd     # 例：adv-ops-prd
```

## 运维操作

### 1. 创建项目级覆盖
```bash
cd <运维仓库>
mkdir -p baselines/projects/<project>
vi baselines/projects/<project>/_overrides.yaml
# 写入需要覆盖的字段
git add baselines/projects/<project>/
git commit -m "ops: <project> 项目自定义基础设施"
git push origin main
```

### 2. 验证生效（看 Jenkins 部署日志）

部署时日志会显示：
```
  📎 项目级覆盖: baselines/projects/<project>/_overrides.yaml
  📌 namespace (项目级覆盖): <project>-ops-test
  📌 image.projects 来源: 项目级覆盖 ...
```

如果显示的是「全局基线 / 默认约定」说明项目级文件没生效，检查：
- 文件路径是否正确（`<project>` 跟 Jenkinsfile 里 `projectName` 一致）
- 文件是否已 push 到 main 分支（agent 拉的是 main 分支）

### 3. 已部署服务的迁移

如果项目级 namespace 跟之前部署的 namespace 不一样（比如之前是 `adv-test`，现在改成 `adv-ops-test`）：

```bash
# 先 uninstall 旧 namespace 的 release（避免两个 release 并存）
sudo helm uninstall <service> -n adv-test --kubeconfig=...

# 然后 Jenkins 重跑（会部署到新 namespace）
# adopt 函数会接管 adv-ops-test 里手工创建的资源
```

## 字段所有权速查

| 字段 | 谁管 | 写在哪 |
|------|------|--------|
| `image.registry/projects/pullSecret` | 运维 | `baselines/_global.yaml` 或 `baselines/projects/<proj>/_overrides.yaml` |
| `namespaces` | 运维 | `baselines/projects/<proj>/_overrides.yaml`（默认走 `{proj}-{env}`） |
| `service.port/replicas/probes/env/java` | 开发 | `<业务仓库>/deploy/values-<env>.yaml` |
| `prod 配置（副本数/资源/HPA）` | 运维 | `baselines/projects/<proj>/<svc>/values-prod.yaml` |
| `image.tag/name/createPullSecret` | CI 注入 | helm --set 自动 |
