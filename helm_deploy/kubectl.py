"""kubectl命令封装模块"""
import subprocess
from pathlib import Path


class KubectlClient:
    """kubectl命令客户端"""
    
    def __init__(self, kubeconfig: Path, namespace: str):
        self.kubeconfig = kubeconfig
        self.namespace = namespace
    
    def run(self, args: list, check: bool = True) -> subprocess.CompletedProcess:
        """执行kubectl命令"""
        cmd = ['sudo', 'kubectl', '--kubeconfig', str(self.kubeconfig), '-n', self.namespace] + args
        return subprocess.run(cmd, stdout=subprocess.PIPE, stderr=subprocess.PIPE, 
                             universal_newlines=True, check=check)
    
    def get_deployment_image(self, service: str) -> str:
        """获取Deployment的镜像"""
        result = self.run(['get', 'deployment', service, 
                          '-o', 'jsonpath={.spec.template.spec.containers[0].image}'], check=False)
        return result.stdout.strip() if result.returncode == 0 else ''
    
    def get_deployment_status(self, service: str) -> tuple:
        """获取Deployment状态 (available, ready, total)"""
        result = self.run(['get', 'deployment', service,
                          '-o', 'jsonpath={.status.availableReplicas},{.status.readyReplicas},{.status.replicas}'], 
                         check=False)
        if result.returncode == 0:
            parts = result.stdout.strip().split(',')
            if len(parts) == 3:
                return (int(parts[0]) if parts[0] else 0,
                        int(parts[1]) if parts[1] else 0,
                        int(parts[2]) if parts[2] else 0)
        return (0, 0, 0)
    
    def get_pods_json(self, service: str) -> dict:
        """获取Pod列表JSON"""
        result = self.run(['get', 'pods', '-l', f'app={service}', '-o', 'json'], check=False)
        if result.returncode == 0:
            import json
            return json.loads(result.stdout)
        return {}
    
    def get_pods(self, service: str) -> str:
        """获取Pod列表"""
        result = self.run(['get', 'pods', '-l', f'app={service}', '-o', 'wide'], check=False)
        return result.stdout if result.returncode == 0 else ''
    
    def get_events(self, limit: int = 10) -> str:
        """获取警告事件"""
        result = self.run(['get', 'events', '--sort-by=.lastTimestamp', 
                          '--field-selector', 'type=Warning'], check=False)
        if result.returncode == 0 and result.stdout.strip():
            lines = result.stdout.strip().split('\n')
            return '\n'.join(lines[-limit:])
        return ''
    
    def get_pod_logs(self, pod_name: str, tail: int = 30) -> str:
        """获取Pod日志"""
        result = self.run(['logs', pod_name, f'--tail={tail}'], check=False)
        return result.stdout if result.returncode == 0 else ''
    
    def get_last_pod_name(self, service: str) -> str:
        """获取最后一个Pod名称"""
        result = self.run(['get', 'pods', '-l', f'app={service}',
                          '-o', 'jsonpath={.items[-1].metadata.name}'], check=False)
        return result.stdout.strip() if result.returncode == 0 else ''
    
    def rollout_restart(self, service: str) -> bool:
        """重启Deployment"""
        result = self.run(['rollout', 'restart', f'deployment/{service}'], check=False)
        return result.returncode == 0
    
    def rollout_status(self, service: str) -> bool:
        """等待rollout完成"""
        result = self.run(['rollout', 'status', f'deployment/{service}'], check=False)
        return result.returncode == 0
    
    def label_resource(self, resource_type: str, resource_name: str, labels: list) -> bool:
        """添加资源标签"""
        cmd = ['label', resource_type, resource_name] + labels + ['--overwrite']
        result = self.run(cmd, check=False)
        return result.returncode == 0
    
    def annotate_resource(self, resource_type: str, resource_name: str, annotations: list) -> bool:
        """添加资源注解"""
        cmd = ['annotate', resource_type, resource_name] + annotations + ['--overwrite']
        result = self.run(cmd, check=False)
        return result.returncode == 0
    
    def get_resource(self, resource_type: str, resource_name: str, output: str = 'name') -> str:
        """获取资源"""
        result = self.run(['get', resource_type, resource_name, '-o', output], check=False)
        return result.stdout.strip() if result.returncode == 0 else ''
    
    def delete_resource(self, resource_type: str, resource_name: str) -> bool:
        """删除资源"""
        result = self.run(['delete', resource_type, resource_name], check=False)
        return result.returncode == 0
    
    def resource_exists(self, resource_type: str, resource_name: str) -> bool:
        """检查资源是否存在"""
        result = self.run(['get', resource_type, resource_name, '-o', 'name'], check=False)
        return result.returncode == 0
    
    def apply_file(self, file_path: Path) -> bool:
        """应用YAML文件"""
        result = subprocess.run(['sudo', 'kubectl', '--kubeconfig', str(self.kubeconfig),
                                '-n', self.namespace, 'apply', '-f', str(file_path)],
                               stdout=subprocess.PIPE, stderr=subprocess.PIPE, universal_newlines=True)
        return result.returncode == 0