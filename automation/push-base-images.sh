#!/bin/bash
# ============================================================
# 推送Java基础镜像到阿里云私有仓库
# 用途：为Docker内构建准备基础镜像，加速拉取
# 执行：bash automation/push-base-images.sh
# ============================================================

set -e

# 阿里云私有仓库地址
REGISTRY="sinozo-registry.ap-southeast-1.cr.aliyuncs.com"
PROJECT="platform"

echo "========================================"
echo "推送Java基础镜像到私有仓库"
echo "目标仓库: ${REGISTRY}/${PROJECT}"
echo "========================================"
echo ""

# 检查是否已登录
if ! docker info | grep -q "Username"; then
  echo "⚠️  请先登录阿里云镜像仓库:"
  echo "   docker login ${REGISTRY}"
  exit 1
fi

# 推送单个镜像的函数
push_image() {
  local source=$1
  local target=$2
  
  echo "📥 拉取: ${source}"
  docker pull "${source}"
  
  echo "🏷️  标记: ${target}"
  docker tag "${source}" "${target}"
  
  echo "📤 推送: ${target}"
  docker push "${target}"
  
  echo "🧹 清理本地标签"
  docker rmi "${target}" 2>/dev/null || true
  
  echo "✅ 完成: ${target}"
  echo ""
}

# Java 8 基础镜像
echo "========== Java 8 =========="
push_image "maven:3.6-jdk-8-alpine" \
           "${REGISTRY}/${PROJECT}/maven:3.6-jdk-8-alpine"

push_image "eclipse-temurin:8-jre-alpine" \
           "${REGISTRY}/${PROJECT}/eclipse-temurin:8-jre-alpine"

# Java 17 基础镜像
echo "========== Java 17 =========="
push_image "maven:3.9-eclipse-temurin-17-alpine" \
           "${REGISTRY}/${PROJECT}/maven:3.9-eclipse-temurin-17-alpine"

push_image "eclipse-temurin:17-jre-alpine" \
           "${REGISTRY}/${PROJECT}/eclipse-temurin:17-jre-alpine"

# Node.js 基础镜像（如果你们的Node.js也想用私有仓库）
echo "========== Node.js 20 =========="
push_image "node:20-alpine" \
           "${REGISTRY}/${PROJECT}/node:20-alpine"

echo ""
echo "╔═══════════════════════════════════════════════╗"
echo "║  ✅ 所有基础镜像推送完成                      ║"
echo "╠═══════════════════════════════════════════════╣"
echo "║  现在可以在Dockerfile中使用:                  ║"
echo "║  FROM ${REGISTRY}/${PROJECT}/...              ║"
echo "╚═══════════════════════════════════════════════╝"
echo ""
echo "验证镜像列表:"
docker images | grep "${REGISTRY}/${PROJECT}" || echo "(本地标签已清理)"
