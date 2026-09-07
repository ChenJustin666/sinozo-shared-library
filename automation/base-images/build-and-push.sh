#!/bin/bash
# ============================================================
# 构建并推送带有 settings.xml 的定制Maven基础镜像
# 用途：将企业私服配置烘焙到基础镜像，业务项目无需维护
# 执行：bash automation/base-images/build-and-push.sh
# ============================================================

set -e

cd "$(dirname "$0")"

# 阿里云私有仓库配置
REGISTRY="sinozo-registry.ap-southeast-1.cr.aliyuncs.com"
PROJECT="platform"

echo "╔═══════════════════════════════════════════════════════════════╗"
echo "║  构建并推送定制Maven基础镜像（含私服配置）                    ║"
echo "╠═══════════════════════════════════════════════════════════════╣"
echo "║  目标仓库: ${REGISTRY}/${PROJECT}                             ║"
echo "╚═══════════════════════════════════════════════════════════════╝"
echo ""

# 检查是否已登录
if ! docker info | grep -q "Username"; then
  echo "⚠️  请先登录阿里云镜像仓库:"
  echo "   docker login ${REGISTRY}"
  exit 1
fi

# 检查 settings.xml 是否存在
if [ ! -f "settings.xml" ]; then
  echo "❌ 错误: settings.xml 不存在"
  echo "请先编辑 automation/base-images/settings.xml 配置私服地址和认证"
  exit 1
fi

echo "📝 当前使用的 settings.xml:"
echo "----------------------------------------"
cat settings.xml | grep -E "<url>|<username>" || true
echo "----------------------------------------"
echo ""

read -p "确认以上配置正确？(y/n) " -n 1 -r
echo
if [[ ! $REPLY =~ ^[Yy]$ ]]; then
    echo "已取消"
    exit 1
fi

# 构建并推送的函数
build_and_push() {
  local dockerfile=$1
  local image_tag=$2
  local jdk_version=$3
  
  echo ""
  echo "=========================================="
  echo "🔨 构建: ${jdk_version}"
  echo "=========================================="
  
  local full_image="${REGISTRY}/${PROJECT}/${image_tag}"
  
  echo "📦 构建镜像: ${full_image}"
  docker build -f "${dockerfile}" -t "${full_image}" .
  
  echo "📤 推送镜像: ${full_image}"
  docker push "${full_image}"
  
  echo "✅ 完成: ${full_image}"
}

# JDK 8
build_and_push "Dockerfile.maven-jdk8" \
               "maven:3.6-jdk-8-sinozo" \
               "JDK 8"

# JDK 17
build_and_push "Dockerfile.maven-jdk17" \
               "maven:3.9-jdk-17-sinozo" \
               "JDK 17"

# JDK 21
build_and_push "Dockerfile.maven-jdk21" \
               "maven:3.9-jdk-21-sinozo" \
               "JDK 21"

echo ""
echo "╔═══════════════════════════════════════════════════════════════╗"
echo "║  ✅ 所有定制Maven基础镜像构建完成                             ║"
echo "╠═══════════════════════════════════════════════════════════════╣"
echo "║  现在业务项目可以使用以下基础镜像（无需 settings.xml）：      ║"
echo "║                                                                 ║"
echo "║  FROM ${REGISTRY}/${PROJECT}/maven:3.6-jdk-8-sinozo            ║"
echo "║  FROM ${REGISTRY}/${PROJECT}/maven:3.9-jdk-17-sinozo           ║"
echo "║  FROM ${REGISTRY}/${PROJECT}/maven:3.9-jdk-21-sinozo           ║"
echo "╚═══════════════════════════════════════════════════════════════╝"
echo ""

echo "验证已推送的镜像:"
docker images | grep "${REGISTRY}/${PROJECT}/maven" | grep sinozo || echo "(本地标签已清理)"
