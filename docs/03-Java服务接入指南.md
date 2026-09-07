# Java 服务接入指南

适用于 Spring Boot + Maven 服务。部署清单使用 Kustomize，详见 [Kustomize 部署方案](./Kustomize.md)。

## 1. 构建约定

业务仓库的 `pom.xml` 必须生成 `target/app.jar`：

```xml
<build>
  <finalName>app</finalName>
</build>
```

平台模板 [Dockerfile.java17](../shared-library/templates/Dockerfile.java17) 以非 root 用户运行 Java 17。Java 8 服务可改用 `Dockerfile.java8`，并在 Jenkinsfile 填写对应 JDK 工具。

## 2. Kustomize 配置

初始化：

```bash
./automation/init-service.sh adv ad-gateway java
```

开发维护：

```text
deploy/kustomize/base/deployment.yaml
deploy/kustomize/base/service.yaml
deploy/kustomize/overlays/test/deployment-patch.yaml
```

公共端口、Deployment 基线和探针在 base；测试副本、JVM、环境变量和资源在 test patch。生产副本、资源、Nacos 地址和 Ingress 在业务仓库的 prod overlay 管理，但该目录只允许受保护 `PROD_BRANCH` 的受控合并。

## 3. JVM 与 Nacos

Deployment 通过 `JAVA_OPTS` 传递 JVM 参数，使用 `MaxRAMPercentage` 时要给 metaspace 和 native memory 留余量。Nacos 地址、namespace 等非敏感值写入 ConfigMap；用户名和密码默认由 overlay 中的 SealedSecret 生成 `<service>-runtime` Secret，容器通过 `NACOS_USERNAME`、`NACOS_PASSWORD` 读取。

不要把密码、数据库连接串或生产凭据提交到业务仓库。探针默认 TCP，确认应用健康接口后可以将 `tcpSocket` 改成 `httpGet`。

## 4. Jenkinsfile

```groovy
@Library('k8s-deploy-lib@main') _
k8sDeploy(
    projectName:  'adv',
    serviceName:  'ad-gateway',
    serviceType:  'java',
    dockerImage:  'ad-gateway',
    dockerCredId: 'docker-swr-cred',
    jdkTool:      'jdk 17',
)
```

## 5. 本地检查与排障

```bash
kubectl kustomize deploy/kustomize/overlays/test > /tmp/ad-gateway.yaml
kubectl apply --dry-run=client -f /tmp/ad-gateway.yaml
kubectl logs deployment/ad-gateway -n adv-test --previous
```

`ImagePullBackOff` 检查 `regcred` 和镜像 tag；`CrashLoopBackOff` 先看 Java 参数和 Nacos 连接；rollout 超时检查 Pod events、资源配额与探针。
