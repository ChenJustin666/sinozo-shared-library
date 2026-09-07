# 定制Maven基础镜像（含私服配置）

## 📋 概述

本目录用于构建和管理烘焙了企业私服配置的定制Maven基础镜像。

**核心优势**：
- ✅ 业务项目无需维护 `settings.xml`
- ✅ 私服配置统一管理，避免泄露
- ✅ 新项目开箱即用，零配置
- ✅ 支持 JDK 8 / 17 / 21

---

## 🏗️ 镜像列表

| JDK版本 | Maven版本 | 镜像Tag |
|---------|----------|---------|
| JDK 8 | Maven 3.6 | `sinozo-registry.ap-southeast-1.cr.aliyuncs.com/platform/maven:3.6-jdk-8-sinozo` |
| JDK 17 | Maven 3.9 | `sinozo-registry.ap-southeast-1.cr.aliyuncs.com/platform/maven:3.9-jdk-17-sinozo` |
| JDK 21 | Maven 3.9 | `sinozo-registry.ap-southeast-1.cr.aliyuncs.com/platform/maven:3.9-jdk-21-sinozo` |

---

## 🚀 快速开始

### 1. 配置私服信息（运维操作）

编辑 `settings.xml`，配置企业私服地址和认证：

```xml
<mirrors>
  <mirror>
    <id>sinozo-nexus</id>
    <mirrorOf>*</mirrorOf>
    <!-- 修改为实际的私服地址 -->
    <url>http://nexus.sinozo.internal/repository/maven-public/</url>
  </mirror>
</mirrors>

<servers>
  <server>
    <id>sinozo-nexus</id>
    <!-- 建议使用只读账号 -->
    <username>readonly</username>
    <password>your-password</password>
  </server>
</servers>
```

### 2. 构建并推送镜像

```bash
# 登录阿里云镜像仓库
docker login sinozo-registry.ap-southeast-1.cr.aliyuncs.com

# 构建并推送所有版本（JDK 8/17/21）
cd automation/base-images
bash build-and-push.sh
```

脚本会自动构建并推送：
- `maven:3.6-jdk-8-sinozo`
- `maven:3.9-jdk-17-sinozo`
- `maven:3.9-jdk-21-sinozo`

### 3. 业务项目使用

业务项目的 Dockerfile **无需 settings.xml**，直接使用定制镜像：

```dockerfile
# syntax=docker/dockerfile:1
FROM sinozo-registry.ap-southeast-1.cr.aliyuncs.com/platform/maven:3.9-jdk-17-sinozo AS builder

WORKDIR /app

COPY pom.xml .
RUN --mount=type=cache,target=/root/.m2/repository \
    mvn dependency:go-offline -B

COPY src ./src
RUN --mount=type=cache,target=/root/.m2/repository \
    mvn clean package -DskipTests -B

FROM eclipse-temurin:17-jre-alpine
COPY --from=builder /app/target/app.jar app.jar
ENTRYPOINT ["java", "-jar", "app.jar"]
```

---

## 📁 文件结构

```
automation/base-images/
├── settings.xml                  # 企业私服配置（敏感）
├── Dockerfile.maven-jdk8         # JDK 8 基础镜像定义
├── Dockerfile.maven-jdk17        # JDK 17 基础镜像定义
├── Dockerfile.maven-jdk21        # JDK 21 基础镜像定义
├── build-and-push.sh             # 构建推送脚本
└── README.md                     # 本文档
```

---

## 🔄 更新流程

当私服地址或账号密码变更时：

```bash
# 1. 更新配置
vim automation/base-images/settings.xml

# 2. 重新构建并推送镜像
cd automation/base-images
bash build-and-push.sh

# 3. 通知开发团队
# 业务项目下次构建时会自动拉取新镜像，无需修改代码
```

---

## 🎓 业务项目模板

### 方案对比

| 方案 | settings.xml | 业务仓库文件 | 适用场景 |
|------|--------------|-------------|---------|
| **简化版（推荐）** | 烘焙在基础镜像 | 只需 Dockerfile | 新项目、标准化场景 |
| 完整版 | 业务仓库维护 | Dockerfile + settings.xml | 特殊私服、多环境 |

### 简化版模板（推荐）

业务仓库文件结构：
```
my-service/
├── Dockerfile       # 从模板复制（无需 settings.xml）
├── pom.xml
├── src/
└── Jenkinsfile
```

复制模板：
```bash
# JDK 17
cp shared-library/templates/Dockerfile.java17-simple Dockerfile

# JDK 8
cp shared-library/templates/Dockerfile.java8-simple Dockerfile

# JDK 21
cp shared-library/templates/Dockerfile.java21-simple Dockerfile
```

---

## 🔐 安全注意事项

1. **settings.xml 不要提交到Git**
   - 已在 `.gitignore` 中排除
   - 只在运维环境维护

2. **使用只读账号**
   - 私服认证建议使用只读权限账号
   - 避免构建阶段误操作

3. **镜像访问控制**
   - 定制镜像只推送到企业私有仓库
   - 配置仓库访问权限控制

4. **定期轮换密码**
   - 私服账号密码定期更新
   - 更新后重新构建镜像

---

## 🐛 常见问题

### 1. 如何验证镜像中的 settings.xml？

```bash
# 启动临时容器
docker run --rm -it sinozo-registry.ap-southeast-1.cr.aliyuncs.com/platform/maven:3.9-jdk-17-sinozo sh

# 查看配置
cat /root/.m2/settings.xml
```

### 2. 业务项目构建报错：无法访问私服

**原因**：基础镜像中的 settings.xml 配置错误

**解决**：
```bash
# 1. 验证私服地址可达
ping nexus.sinozo.internal

# 2. 重新检查 settings.xml 配置
vim automation/base-images/settings.xml

# 3. 重新构建基础镜像
cd automation/base-images
bash build-and-push.sh
```

### 3. 如何支持多个私服环境？

如果测试和生产需要不同私服，可以构建多个版本：

```bash
# 构建测试版
docker build -f Dockerfile.maven-jdk17 -t maven:3.9-jdk-17-test .

# 构建生产版（使用不同的 settings-prod.xml）
docker build -f Dockerfile.maven-jdk17 --build-arg SETTINGS=settings-prod.xml -t maven:3.9-jdk-17-prod .
```

但**不推荐**这样做，因为违反"Build Once"原则。

---

## 📚 相关文档

- 业务项目使用指南：`docs/09-Java服务Docker内构建配置指南.md`
- 构建模式选择：`docs/08-构建模式选择指南.md`
- 模板文件：`shared-library/templates/Dockerfile.java*-simple`

---

**维护者**: DevOps Team  
**最后更新**: 2026-08-27
