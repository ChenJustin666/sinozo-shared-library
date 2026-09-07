# 项目级 Kustomize 配置

本目录由运维维护，保存项目覆盖和每个服务的生产 overlay。

```text
baselines/projects/<project>/
├── _overrides.yaml
└── <service>/
    └── kustomize/prod/
        ├── kustomization.yaml
        ├── deployment-patch.yaml
        ├── ingress.yaml             # 可选
        ├── hpa.yaml                 # 可选
        └── pdb.yaml                 # 可选
```

`_overrides.yaml` 可以覆盖环境对应的镜像 project 和 namespace：

```yaml
image:
  projects:
    test: sinozo
    prod: sinozo
namespaces:
  test: project-test
  prod: project-prod
```

默认所有环境共用镜像 organization `sinozo`，无需跨仓库 promotion；只有确需镜像隔离的项目才覆盖 `image.projects`。Kubernetes namespace 仍默认使用 `<project>-<env>`，可通过上面的 `namespaces` 覆盖。

prod `kustomization.yaml` 必须以 `../../base` 引用业务 base。Pipeline 会把业务 base 和本目录 overlay 复制到同一个临时结构后渲染，因此本目录单独执行 `kubectl kustomize` 会因缺少 base 失败，这是预期行为。

生产 review 至少检查副本数、requests/limits、JVM/Node 环境、探针、Ingress、HPA/PDB 和调度规则。镜像 tag、namespace、`regcred` 不写入这里，由 CI 注入。

旧的 `values-prod.yaml` 只供尚未迁移的 Helm 服务使用。同一服务启用 Kustomize 后不要再修改或触发 Helm release，迁移步骤见 [运维操作指南](../../docs/06-运维操作指南.md)。
