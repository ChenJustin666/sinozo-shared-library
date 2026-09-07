# Java服务Docker内构建配置指南

## 📋 概述

本文档说明如何为Java服务配置Docker内多阶段构建，使用企业Maven私服和BuildKit缓存优化。

---

## 🎯 推荐方案总结

| 配置项 | 推荐值 | 说明 |
|-------|--------|------|
| **私服配置** | 1个（settings.xml） | 测试私服即可，生产复用镜像 |
| **基础镜像** | 阿里云私有仓库 | `sinozo-registry.ap-southeast-1.cr.aliyuncs.com/platform/` |
| **缓存机制** | BuildKit cache mount | 性能等同宿主机Maven缓存 |
| **JDK版本** | 两套模板 | Java 8 和 Java 17 |

---

## 🚀 快速开始

### 1. 准备基础镜像（运维一次性操作）

```bash
# 登录阿里云镜像仓库
docker login sinozo-registry.ap-southeast-1.cr.aliyuncs.com

# 推送基础镜像
bash automation/push-base-images.sh
```

这会推送以下镜像：
- `sinozo-registry.ap-southeast-1.cr.aliyuncs.com/platform/maven:3.6-jdk-8-alpine`
- `sinozo-registry.ap-southeast-1.cr.aliyuncs.com/platform/eclipse-temurin:8-jre-alpine`
- `sinozo-registry.ap-southeast-1.cr.aliyuncs.com/platform/maven:3.9-eclipse-temurin-17-alpine`
- `sinozo-registry.ap-southeast-1.cr.aliyuncs.com/platform/eclipse-temurin:17-jre-alpine`

### 2. 业务项目配置

#### 文件结构

```
my-java-service/
├── Dockerfile              # 从模板复制
├── settings.xml            # Maven私服配置
├── pom.xml
├── src/
└── Jenkinsfile
```

#### 复制模板

**Java 17 项目**：
```bash
cp shared-library/templates/Dockerfile.java17-multistage Dockerfile
cp shared-library/templates/settings.xml.example settings.xml
```

**Java 8 项目**：
```bash
cp shared-library/templates/Dockerfile.java8-multistage Dockerfile
cp shared-library/templates/settings.xml.example settings.xml
```

#### 修改 settings.xml

```xml
<?xml version="1.0" encoding="UTF-8"?>
<settings>
  <mirrors>
    <mirror>
      <id>sinozo-nexus</id>
      <mirrorOf>*</mirrorOf>
      <!-- 修改为你们的私服地址 -->
      <url>http://nexus-test.sinozo.internal/repository/maven-public/</url>
    </mirror>
  </mirrors>
  
  <!-- 如果私服需要认证 -->
  <servers>
    <server>
      <id>sinozo-nexus</id>
      <username>readonly</username>
      <password>your-password</password>
    </server>
  </servers>
</settings>
```

#### 配置 Jenkinsfile

```groovy
@Library('k8s-deploy-lib@main') _
k8sDeploy(
    projectName:  'myproject',
    serviceName:  'my-service',
    serviceType:  'java',
    buildMode:    'docker',  // ← 使用Docker内构建
    dockerImage:  'my-service',
    dockerCredId: 'docker-swr-cred',
    // jdkTool 和 mavenGoals 不再需要（Docker内构建）
)
```

---

## 📝 为什么只需要一个私服配置？

### 部署流程

```
测试环境构建：
  1. test Job 触发
  2. docker build（使用 settings.xml 测试私服）
  3. 生成镜像: my-service:R<commit-sha>
  4. kubectl apply 到 test namespace

生产环境部署（不重新构建）：
  1. prod Job 触发
  2. 复用镜像: my-service:R<commit-sha>  ← 同一个tag！
  3. kubectl apply 到 prod namespace
```

**关键点**：生产环境只执行 `kubectl apply`，不会 `docker build`，因此不需要 `prod.xml`。

这符合平台的 **"Build Once, Deploy Many"** 原则。

---

## 🔧 构建缓存机制详解

### BuildKit Cache Mount 工作原理

```dockerfile
RUN --mount=type=cache,target=/root/.m2/repository \
    mvn dependency:go-offline -B
```

这行指令的效果：
- `/root/.m2/repository` 目录在构建间持久化
- 不属于任何Docker layer（不会进入镜像）
- 效果等同于Jenkins节点的 `.m2` 缓存

### 性能对比

| 场景 | 宿主机构建 | Docker无cache mount | Docker有cache mount |
|------|-----------|-------------------|-------------------|
| 首次构建 | 3-5min | 3-5min | 3-5min |
| 只改代码 | 30-60s | 30-60s | 30-60s |
| pom.xml加1个依赖 | 10-30s | 2-5min ❌ | 10-30s ✅ |

### 构建命令

```bash
# Jenkins节点需要开启BuildKit
export DOCKER_BUILDKIT=1

# 构建命令
docker build -t my-service:latest .

# 或在共享库中（pushImage.groovy）
sh "DOCKER_BUILDKIT=1 docker build -t '${fullImage}' ."
```

---

## 🏗️ 模板文件说明

### Dockerfile.java17-multistage

```dockerfile
# syntax=docker/dockerfile:1
FROM sinozo-registry.ap-southeast-1.cr.aliyuncs.com/platform/maven:3.9-eclipse-temurin-17-alpine AS builder
WORKDIR /app

# 注入私服配置
COPY settings.xml /root/.m2/settings.xml

# 依赖下载（利用Docker layer cache）
COPY pom.xml .
RUN --mount=type=cache,target=/root/.m2/repository \
    mvn dependency:go-offline -B

# 编译
COPY src ./src
RUN --mount=type=cache,target=/root/.m2/repository \
    mvn clean package -DskipTests -B && \
    mv target/*.jar target/app.jar

# 运行阶段
FROM sinozo-registry.ap-southeast-1.cr.aliyuncs.com/platform/eclipse-temurin:17-jre-alpine
COPY --from=builder /app/target/app.jar app.jar
# ... 其他配置
```

**关键点**：
1. `COPY pom.xml` 在 `COPY src` 之前 → 利用layer cache
2. `--mount=type=cache` → 依赖在构建间持久化
3. 多阶段构建 → settings.xml不会进入运行镜像

### settings.xml.example

示例配置文件，包含：
- Maven镜像仓库配置
- 私服认证配置
- 注释说明

---

## 🔐 密钥管理

### 方案1：settings.xml明文提交（简单，适合非敏感环境）

```bash
# 测试私服账号可以明文提交到业务仓库
git add settings.xml
git commit -m "chore: 添加Maven私服配置"
```

### 方案2：Docker BuildKit Secret（更安全）

```dockerfile
# syntax=docker/dockerfile:1
FROM maven:... AS builder
WORKDIR /app

# 使用secret mount，settings.xml不进入任何layer
RUN --mount=type=secret,id=maven_settings,target=/root/.m2/settings.xml \
    mvn dependency:go-offline -B

COPY src ./src
RUN --mount=type=secret,id=maven_settings,target=/root/.m2/settings.xml \
    mvn clean package -DskipTests -B
```

构建命令：
```groovy
// Jenkins共享库中
sh """
    DOCKER_BUILDKIT=1 docker build \
      --secret id=maven_settings,src=settings.xml \
      -t '${fullImage}' .
"""
```

### 方案3：构建参数注入（适合多环境）

```dockerfile
ARG MAVEN_USER
ARG MAVEN_PASS

# 在settings.xml中使用环境变量
RUN mvn ... 
```

```groovy
// Jenkins构建
withCredentials([usernamePassword(...)]) {
    sh "docker build --build-arg MAVEN_USER=$USER --build-arg MAVEN_PASS=$PASS ..."
}
```

---

## 🎓 最佳实践

### ✅ 推荐做法

1. **业务仓库提交 settings.xml**
   - 测试私服配置可以明文提交
   - 开发者本地也能直接 `docker build`

2. **使用阿里云私有仓库的基础镜像**
   - 加速基础镜像拉取
   - 减少对公网Docker Hub的依赖

3. **开启BuildKit**
   - Jenkins节点配置 `DOCKER_BUILDKIT=1`
   - 获得cache mount能力

4. **pom.xml配置 `<finalName>app</finalName>`**
   - 统一jar包名称
   - Dockerfile不需要通配符

### ❌ 避免做法

1. **不要在Dockerfile中硬编码密码**
   ```dockerfile
   # ❌ 错误
   RUN echo "password=123456" > /root/.m2/settings.xml
   ```

2. **不要用生产私服构建**
   - 测试环境构建，生产复用镜像
   - 符合"Build Once"原则

3. **不要省略 `# syntax=docker/dockerfile:1`**
   - 这行启用BuildKit特性
   - 没有它 `--mount=type=cache` 会失败

---

## 🐛 常见问题

### 1. 构建报错：`unknown flag: --mount`

**原因**：Docker版本太低或未开启BuildKit

**解决**：
```bash
# 检查Docker版本（需要19.03+）
docker --version

# 开启BuildKit
export DOCKER_BUILDKIT=1

# 或在Dockerfile第一行加上
# syntax=docker/dockerfile:1
```

### 2. 每次都重新下载依赖

**原因**：BuildKit cache mount未生效

**排查**：
```bash
# 1. 确认Dockerfile第一行
# syntax=docker/dockerfile:1

# 2. 确认构建命令
DOCKER_BUILDKIT=1 docker build ...

# 3. 查看构建日志，应该看到
[cache] --> RUN mvn dependency:go-offline -B
```

### 3. 私服连接失败

**原因**：容器内无法访问内网私服

**解决方案A**：使用Jenkins节点的网络
```groovy
sh "docker build --network=host ..."
```

**解决方案B**：配置Docker DNS
```bash
# /etc/docker/daemon.json
{
  "dns": ["10.0.0.1", "8.8.8.8"]
}
```

**解决方案C**：暂时回退到宿主机构建
```groovy
k8sDeploy(
    buildMode: 'host',  // 临时方案
)
```

### 4. 本地能构建，Jenkins失败

**可能原因**：
1. Jenkins节点Docker版本低
2. Jenkins节点未开启BuildKit
3. Jenkins节点网络无法访问私服

**排查**：
```bash
# SSH到Jenkins节点
ssh jenkins-node

# 检查环境
docker --version
env | grep DOCKER_BUILDKIT
docker build --help | grep mount

# 测试网络
ping nexus-test.sinozo.internal
```

---

## 🔄 从宿主机构建迁移

如果现有Java项目使用宿主机构建，迁移步骤：

```bash
# 1. 备份现有Dockerfile
cp Dockerfile Dockerfile.old

# 2. 使用新模板
cp shared-library/templates/Dockerfile.java17-multistage Dockerfile

# 3. 创建settings.xml
cp shared-library/templates/settings.xml.example settings.xml
# 编辑settings.xml，配置私服地址

# 4. 本地测试
DOCKER_BUILDKIT=1 docker build -t my-service:test .
docker run -p 8080:8080 my-service:test

# 5. 更新Jenkinsfile
k8sDeploy(
    buildMode: 'docker',  # host → docker
    # 删除 jdkTool 和 mavenGoals
)

# 6. 测试环境验证
git add Dockerfile settings.xml Jenkinsfile
git commit -m "chore: 迁移到Docker内构建"
git push

# 7. 观察test Job构建日志
# 首次会慢（下载依赖），后续会快（缓存命中）
```

---

## 📚 参考资料

- [Docker BuildKit文档](https://docs.docker.com/build/buildkit/)
- [Maven Settings参考](https://maven.apache.org/settings.html)
- [多阶段构建最佳实践](https://docs.docker.com/build/building/multi-stage/)
- 平台文档：`docs/08-构建模式选择指南.md`

---

**文档版本**: v1.0  
**最后更新**: 2026-08-27  
**维护者**: DevOps Team
