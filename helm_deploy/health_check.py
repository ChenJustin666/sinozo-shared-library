"""健康检查模块"""
import time
import logging

logger = logging.getLogger(__name__)


class HealthChecker:
    """健康检查器"""
    
    # 错误状态及建议
    ERROR_SUGGESTIONS = {
        'CrashLoopBackOff': '检查应用日志',
        'ImagePullBackOff': '检查镜像地址和regcred凭据',
        'ErrImagePull': '确认镜像tag是否正确',
        'CreateContainerConfigError': '检查ConfigMap/Secret配置'
    }
    
    def __init__(self, kubectl_client):
        self.kubectl = kubectl_client
    
    def check(self, service: str, timeout: int = 300, level: str = 'standard') -> bool:
        """执行健康检查
        
        Args:
            service: 服务名称
            timeout: 超时时间(秒)
            level: 检查级别 (none/basic/standard/deep)
        
        Returns:
            bool: 是否健康
        """
        if level == 'none':
            logger.info("⏭️ 跳过健康检查")
            return True
        
        if level == 'basic':
            return self._basic_check(service)
        
        return self._standard_check(service, timeout)
    
    def _basic_check(self, service: str) -> bool:
        """基础检查 - 只验证镜像"""
        logger.info("🏥 基础检查: 验证镜像更新...")
        image = self.kubectl.get_deployment_image(service)
        if image:
            logger.info(f"✅ 当前镜像: {image}")
            return True
        logger.error("❌ 获取镜像失败")
        return False
    
    def _standard_check(self, service: str, timeout: int) -> bool:
        """标准检查 - 等待Pod就绪"""
        logger.info(f"🏥 开始健康检查 (超时: {timeout}秒)...")
        
        start_time = time.time()
        
        while time.time() - start_time < timeout:
            # 检查Deployment状态
            available, ready, total = self.kubectl.get_deployment_status(service)
            
            if total > 0 and available == total and ready == total:
                logger.info(f"✅ 健康检查通过: {available}/{total} 副本就绪")
                return True
            
            if total > 0:
                logger.info(f"⏳ 等待Pod就绪: {ready}/{total} 就绪, {available} 可用")
            
            # 检查Pod异常状态
            error = self._check_pod_errors(service)
            if error:
                return False
            
            time.sleep(5)
        
        logger.error(f"❌ 健康检查超时 ({timeout}秒)")
        return False
    
    def _check_pod_errors(self, service: str) -> bool:
        """检查Pod错误状态"""
        pods_data = self.kubectl.get_pods_json(service)
        
        for pod in pods_data.get('items', []):
            pod_name = pod['metadata']['name']
            container_statuses = pod['status'].get('containerStatuses', [])
            
            for cs in container_statuses:
                state = cs.get('state', {})
                if 'waiting' in state:
                    reason = state['waiting'].get('reason', '')
                    if reason in self.ERROR_SUGGESTIONS:
                        logger.error(f"❌ Pod {pod_name} 状态异常: {reason}")
                        logger.error(f"   建议: {self.ERROR_SUGGESTIONS[reason]}")
                        return True
        return False
    
    def diagnose(self, service: str):
        """快速诊断 - 输出诊断信息"""
        logger.info("🔍 执行快速诊断...")
        
        # Pod状态
        pods = self.kubectl.get_pods(service)
        if pods:
            logger.info("Pod状态:")
            print(pods)
        
        # 警告事件
        events = self.kubectl.get_events(limit=10)
        if events:
            logger.info("最近警告事件:")
            print(events)
        
        # Pod日志
        pod_name = self.kubectl.get_last_pod_name(service)
        if pod_name:
            logger.info(f"Pod {pod_name} 最近日志:")
            logs = self.kubectl.get_pod_logs(pod_name, tail=30)
            print(logs)