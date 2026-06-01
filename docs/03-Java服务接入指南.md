# Java 服务接入指南

> 适用于：Spring Boot + Maven 后端服务  
> 配套阅读：[变量外置规范](./变量外置规范.md)

---

## 一、接入前提

- Maven 项目，`pom.xml` 打包为可执行 jar
- 运维已执行 `init-service.sh` 脚本，生成了文件模板
- Jenkins 已配置好共享库和 SWR 凭据（联系运维确认）

---

## 二、pom.xml 必须配置

Dockerfile 固定从 `target/app.jar` 复制，所以 pom 必须指定 finalName：

```xml
<build>
    <finalName>app</finalName>
    <plugins>
        <plugin>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-maven-plugin</artifactId>
        </plugin>
    </plugins>
</build>
```

---

## 三、Dockerfile

直接复制 `shared-library/templates/Dockerfile.java8`（或 java17）到业务仓库根目录，**通常不需要修改**：

```dockerfile
FROM eclipse-temurin:8-jre-alpine

RUN apk add --no-cache curl tzdata && \
    cp /usr/share/zoneinfo/Asia/Shanghai /etc/localtime && \
    echo "Asia/Shanghai" > /etc/timezone && \
    apk del tzdata && \
    addgroup -S appgroup && adduser -S appuser -G appgroup

WORKDIR /app
COPY target/app.jar app.jar
RUN chown -R appuser:appgroup /app

USER appuser

ENV JAVA_OPTS="" \
    TZ=Asia/Shanghai

EXPOSE 8080

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
```

> `JAVA_OPTS` 由 Helm Chart 在部署时注入，对应 `values-test.yaml` 里的 `java.opts` 字段。

**使用 Java 17**：把 FROM 改成 `eclipse-temurin:17-jre-alpine`，并在 Jenkinsfile 里改 `jdkTool: 'JDK 17'`。

---

## 四、Jenkinsfile 配置

从模板复制后，按实际修改以下字段：

```groovy
@Library('k8s-deploy-lib@main') _

k8sDeploy(
    // ── 必填（改成实际值）──
    projectName:  'adv',             // 项目名（小写），决定 K8s namespace 前缀
    serviceName:  'ad-gateway',      // 服务名（小写），K8s Deployment / Service 名
    serviceType:  'java',
    dockerImage:  'ad-gateway',      // SWR 镜像名（不含 project 前缀）
    dockerCredId: 'docker-swr-cred', // Jenkins 凭据 ID（联系运维确认）

    // ── Java 构建工具（名称要与 Jenkins → Tools 配置一致）──
    jdkTool:   'jdk 1.8',            // 或 'JDK 17'
    mavenTool: 'Maven 3.8.8',        // 留空则用 /usr/local/maven

    // ── K8s 集群凭据（运维提供凭据 ID）──
    kubeconfigCredId: [
        test: 'test-k8s-cluster',
        prod: 'prod-k8s-cluster',
    ],

    // ── 以下可选，默认值通常够用 ──
    // mavenGoals:   'clean package -DskipTests',
    // defaultBranch: 'main',
    // nacosCredId:  'nacos-adv-cred',   // 如果 Nacos 需要认证，凭据由运维创建
    // subdirectory: 'services/ad-gateway',  // MonoRepo 子目录
    // agent:        'java-build',           // 构建节点标签
)
```

### Jenkinsfile 参数说明

| 参数 | 必填 | 说明 |
|------|------|------|
| `projectName` | ✅ | 项目名，K8s namespace = `<project>-<env>` |
| `serviceName` | ✅ | 服务名，Deployment/Service 资源名 |
| `serviceType` | ✅ | `java` |
| `dockerImage` | ✅ | 镜像名，不含 SWR organization 前缀 |
| `dockerCredId` | ✅ | SWR 凭据 Jenkins ID |
| `kubeconfigCredId` | ✅ | K8s kubeconfig 凭据，推荐 Map 形式按环境分开 |
| `jdkTool` | - | Jenkins 全局工具名，默认 `jdk 1.8` |
| `mavenTool` | - | Jenkins 全局 Maven 工具名，空则用节点上的 Maven |
| `mavenGoals` | - | Maven 命令，默认 `clean package -DskipTests` |
| `nacosCredId` | - | Nacos 账号凭据 ID，会以 `--set nacos.username/password` 注入 |
| `subdirectory` | - | MonoRepo 子目录，`deploy/` 路径相对子目录 |
| `defaultBranch` | - | 默认分支，默认 `main` |

---

## 五、deploy/values-test.yaml 配置

### 5.1 最小可用配置

```yaml
# deploy/values-test.yaml
service:
  port: 8080      # 服务监听端口
  replicas: 1

resources:
  enabled: true
  requests:
    cpu: 300m
    memory: 512Mi
  limits:
    cpu: 800m
    memory: 1Gi

java:
  enabled: true
  opts: >-
    -Dspring.application.name=ad-gateway
    -Dspring.profiles.active=test
    -DNACOS_SERVERS=nacos-test.internal:8848
    -DNACOS_NAMESPACE=ad-gateway-test
    -Xms256m -Xmx512m
```

### 5.2 生产推荐 JVM 写法（test 也可用）

```yaml
java:
  enabled: true
  opts: >-
    -XX:+UseContainerSupport
    -XX:MaxRAMPercentage=72.0
    -XX:MaxMetaspaceSize=256m
    -XX:+UseG1GC
    -XX:MaxGCPauseMillis=200
    -XX:+HeapDumpOnOutOfMemoryError
    -XX:HeapDumpPath=/tmp/heapdump.hprof
    -Djava.security.egd=file:/dev/./urandom
    -Dfile.encoding=UTF-8
    -Dspring.application.name=ad-gateway
    -Dspring.profiles.active=test
    -DNACOS_SERVERS=nacos-test.internal:8848
    -DNACOS_NAMESPACE=ad-gateway-test
    -DNACOS_GROUP=DEFAULT_GROUP
```

> `-XX:+UseContainerSupport` 让 JVM 感知容器 memory limit，自动计算堆大小，不要同时写 `-Xmx`。

### 5.3 完整配置（按需开启）

```yaml
# ============================================================
# 测试环境配置  Owner: 开发
# ⚠️ 不能写：image.* / service.namespace / service.name / project / environment
# ============================================================

service:
  port: 8080
  replicas: 1

resources:
  enabled: true
  requests:
    cpu: 300m
    memory: 512Mi
  limits:
    cpu: 800m
    memory: 1Gi

java:
  enabled: true
  opts: >-
    -XX:+UseContainerSupport
    -XX:MaxRAMPercentage=72.0
    -XX:MaxMetaspaceSize=256m
    -XX:+UseG1GC
    -Dfile.encoding=UTF-8
    -Dspring.application.name=ad-gateway
    -Dspring.profiles.active=test
    -DNACOS_SERVERS=nacos-test.internal:8848
    -DNACOS_NAMESPACE=ad-gateway-test

# 业务环境变量（非敏感）
env:
  - name: LOG_LEVEL
    value: debug
  # 敏感变量从 Secret 引用，不写明文：
  # - name: DB_PASSWORD
  #   valueFrom:
  #     secretKeyRef:
  #       name: ad-gateway-secret
  #       key: DB_PASSWORD

# 健康探针（先关，服务稳定后开）
probes:
  enabled: false
  type: tcp             # 推荐先用 tcp，稳定后换 http
  port: 8080
  liveness:
    periodSeconds: 10
    failureThreshold: 3
  readiness:
    periodSeconds: 5
    failureThreshold: 3
  startup:
    periodSeconds: 10
    failureThreshold: 30    # 5 分钟启动窗口

# Prometheus 监控（有 /actuator/prometheus 才开）
monitoring:
  enabled: false
  path: /actuator/prometheus
  port: 8080

# HPA 自动扩缩容
hpa:
  enabled: false
  minReplicas: 1
  maxReplicas: 3
  cpuTarget: 70

# PDB 中断保护（test 一般不开）
pdb:
  enabled: false
  minAvailable: 1

# Ingress（如需对外暴露）
ingress:
  enabled: false
  items:
    - host: ad-gateway-test.example.com
      paths:
        - path: /
          pathType: Prefix
      tls: false

# ConfigMap 挂载（如有配置文件需要挂入容器）
configmap:
  enabled: false
  mountPath: /app/config
  data:
    logback.xml: |
      <configuration>...</configuration>
```

---

## 六、镜像 tag 规则

CI 自动计算，格式：`R` + commit SHA 前 9 位

```
swr.ap-southeast-3.myhuaweicloud.com/sinozo-test/ad-gateway:R1a2b3c4d9
```

- **test 部署**：构建新镜像推到 `sinozo-test` organization
- **prod 部署**：从 `sinozo-test` Promotion（pull + tag + push）到 `sinozo-prod`，不重新构建
- 手动指定：在 Jenkins Build 参数里填 `IMAGE_TAG`，跳过构建

---

## 七、接入检查清单

```
□ pom.xml 配置了 <finalName>app</finalName>
□ Dockerfile 已放到业务仓库根目录
□ Jenkinsfile projectName / serviceName / dockerImage 已填写正确
□ Jenkinsfile kubeconfigCredId 凭据 ID 已确认（联系运维）
□ deploy/values-test.yaml 端口与服务实际端口一致
□ deploy/values-test.yaml java.opts 里 Nacos 地址已填写（联系运维确认地址）
□ application.yml 已改为读取环境变量（参考变量外置规范）
□ Jenkins Job 已创建，指向业务仓库 Jenkinsfile
```

---

## 八、常见问题

### Q1: 构建失败 —— target/app.jar 不存在

检查 `pom.xml` 是否有 `<finalName>app</finalName>`，否则 jar 带版本号，Dockerfile `COPY` 找不到。

### Q2: Pod CrashLoopBackOff

常见原因及排查顺序：

1. **JVM 内存超出 limits**：`-Xmx` 或 `MaxRAMPercentage` 算出的堆 + Metaspace 超过 `resources.limits.memory`
   - 检查：`kubectl logs <pod> -n <project>-test --previous` 看是否有 OOM
2. **Nacos 连不上**：`java.opts` 里 Nacos 地址写错
   - 检查：`kubectl logs <pod> -n <project>-test` 看连接报错
3. **探针配置错误**：先把 `probes.enabled: false`，确认服务能起来再开探针
4. **application.yml 读不到变量**：确认 `${NACOS_SERVERS:}` 占位符语法正确

### Q3: 想切换 Java 17

1. Dockerfile 第一行改 `FROM eclipse-temurin:17-jre-alpine`
2. Jenkinsfile 改 `jdkTool: 'JDK 17'`（需运维在 Jenkins 工具里配置同名工具）
3. JVM 参数里去掉 Java 8 不兼容的参数（如部分 GC flags）

### Q4: MonoRepo 多服务在同一个仓库

在 Jenkinsfile 加 `subdirectory` 参数：

```groovy
k8sDeploy(
    subdirectory: 'services/ad-gateway',   // 子目录路径
    // ...
)
```

`deploy/values-test.yaml` 路径变为 `services/ad-gateway/deploy/values-test.yaml`。
