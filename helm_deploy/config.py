"""配置处理模块"""
import yaml
import base64
import json
import hashlib
import logging
from pathlib import Path
from typing import Dict, Optional

logger = logging.getLogger(__name__)


class ConfigManager:
    """配置管理器"""
    
    def __init__(self, projects_dir: Path):
        self.projects_dir = projects_dir
    
    def load_project_config(self, project: str) -> Dict:
        """加载项目配置"""
        config_file = self.projects_dir / project / 'project.yaml'
        if not config_file.exists():
            logger.error(f"项目配置不存在: {config_file}")
            raise FileNotFoundError(f"项目配置不存在: {config_file}")
        with open(config_file, 'r', encoding='utf-8') as f:
            return yaml.safe_load(f) or {}
    
    def get_service_dir(self, project: str, environment: str, service: str) -> Path:
        """获取服务目录"""
        return self.projects_dir / project / environment / service
    
    def get_backup_dir(self, project: str, environment: str, service: str) -> Path:
        """获取备份目录"""
        backup_dir = self.projects_dir / project / environment / service / '.backup'
        backup_dir.mkdir(parents=True, exist_ok=True)
        return backup_dir
    
    def get_kubeconfig_path(self, project: str, environment: str, 
                            kubeconfig_override: Optional[str] = None) -> Path:
        """获取kubeconfig路径"""
        if kubeconfig_override:
            kc_path = Path(kubeconfig_override)
            if not kc_path.exists():
                raise FileNotFoundError(f"kubeconfig不存在: {kc_path}")
            return kc_path
        
        project_config = self.load_project_config(project)
        clusters = project_config.get('clusters', {})
        cluster_config = clusters.get(environment, {})
        
        kubeconfig_rel = cluster_config.get('kubeconfig')
        if not kubeconfig_rel:
            raise ValueError(f"项目{project}未配置{environment}环境的kubeconfig!")
        
        kc_path = self.projects_dir / project / kubeconfig_rel
        if not kc_path.exists():
            raise FileNotFoundError(f"kubeconfig不存在: {kc_path}")
        
        return kc_path
    
    def get_configmap_data(self, config_dir: Path) -> Optional[Dict[str, str]]:
        """读取ConfigMap目录下的所有文件内容"""
        cm_dir = config_dir / 'configmap'
        if not cm_dir.exists():
            return None
        
        config_files = list(cm_dir.glob('*.yml')) + list(cm_dir.glob('*.yaml')) + list(cm_dir.glob('*.properties'))
        if not config_files:
            return None
        
        data = {}
        for f in config_files:
            with open(f, 'r', encoding='utf-8') as fp:
                data[f.name] = fp.read()
        
        return data
    
    def calculate_configmap_checksum(self, data: Dict[str, str]) -> str:
        """计算ConfigMap数据的checksum"""
        content = ''.join(f"{k}={v}" for k, v in sorted(data.items()))
        return hashlib.sha256(content.encode()).hexdigest()[:16]
    
    def read_docker_config_from_comm(self, base_dir: Path, environment: str) -> Optional[Dict]:
        """从comm/{env}/docker-registry-secret.yaml读取Docker配置"""
        comm_file = base_dir / 'comm' / environment / 'docker-registry-secret.yaml'
        if not comm_file.exists():
            comm_file = base_dir / 'comm' / 'docker-registry-secret.yaml'
            if not comm_file.exists():
                return None
        
        try:
            with open(comm_file, 'r', encoding='utf-8') as f:
                secret_data = yaml.safe_load(f) or {}
            
            pull_secret_data = secret_data.get('data', {}).get('.dockerconfigjson', '')
            
            registry = ''
            if pull_secret_data:
                try:
                    decoded = base64.b64decode(pull_secret_data).decode('utf-8')
                    auth_config = json.loads(decoded)
                    registries = list(auth_config.get('auths', {}).keys())
                    if registries:
                        registry = registries[0]
                except:
                    pass
            
            return {
                'registry': registry,
                'pull_secret': 'regcred',
                'pull_secret_data': pull_secret_data
            }
        except Exception as e:
            logger.warning(f"⚠️ 读取comm配置失败: {e}")
            return None
    
    @staticmethod
    def deep_merge(base: Dict, override: Dict) -> Dict:
        """深度合并两个字典"""
        result = base.copy()
        for key, value in override.items():
            if key in result and isinstance(result[key], dict) and isinstance(value, dict):
                result[key] = ConfigManager.deep_merge(result[key], value)
            else:
                result[key] = value
        return result