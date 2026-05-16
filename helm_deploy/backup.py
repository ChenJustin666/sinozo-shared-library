"""备份管理模块"""
import shutil
import logging
from pathlib import Path
from datetime import datetime

logger = logging.getLogger(__name__)


class BackupManager:
    """备份管理器"""
    
    def __init__(self, backup_dir: Path):
        self.backup_dir = backup_dir
        self.backup_dir.mkdir(parents=True, exist_ok=True)
    
    def backup_values(self):
        """备份values.yaml"""
        values_file = self.backup_dir / 'values.yaml'
        if values_file.exists():
            timestamp = datetime.now().strftime('%Y%m%d_%H%M%S')
            backup_file = self.backup_dir / f'values_{timestamp}.yaml'
            shutil.copy2(values_file, backup_file)
            logger.info(f"✅ 已备份values: {backup_file}")
    
    def backup_configmap(self, source_dir: Path):
        """备份ConfigMap源文件"""
        cm_dir = source_dir / 'configmap'
        if cm_dir.exists():
            timestamp = datetime.now().strftime('%Y%m%d_%H%M%S')
            backup_cm_dir = self.backup_dir / f'configmap_{timestamp}'
            shutil.copytree(cm_dir, backup_cm_dir)
            logger.info(f"✅ 已备份configmap: {backup_cm_dir}")
    
    def backup_current(self, service: str):
        """备份当前运行的配置"""
        current_file = self.backup_dir / 'current.yaml'
        if current_file.exists():
            timestamp = datetime.now().strftime('%Y%m%d_%H%M%S')
            backup_file = self.backup_dir / f'backup_{timestamp}.yaml'
            shutil.copy2(current_file, backup_file)
            logger.info(f"✅ 已备份current: {backup_file}")
    
    def log_history(self, action: str, image_tag: str, status: str):
        """记录部署历史"""
        history_file = self.backup_dir / 'history.log'
        timestamp = datetime.now().strftime('%Y-%m-%d %H:%M:%S')
        with open(history_file, 'a', encoding='utf-8') as f:
            f.write(f"{timestamp} | {action} | {image_tag} | {status}\n")
    
    def cleanup_old_backups(self, pattern: str = 'backup_*.yaml', keep: int = 10):
        """清理旧备份文件"""
        backup_files = sorted(self.backup_dir.glob(pattern))
        if len(backup_files) > keep:
            for old_file in backup_files[:-keep]:
                old_file.unlink()
                logger.info(f"🗑️ 清理旧备份: {old_file.name}")
    
    def save_values(self, values: dict):
        """保存values.yaml"""
        import yaml
        values_file = self.backup_dir / 'values.yaml'
        with open(values_file, 'w', encoding='utf-8') as f:
            yaml.dump(values, f, default_flow_style=False, allow_unicode=True)
        logger.info(f"✅ 保存values文件: {values_file}")
    
    def load_values(self) -> dict:
        """加载已保存的values"""
        import yaml
        values_file = self.backup_dir / 'values.yaml'
        if values_file.exists():
            with open(values_file, 'r', encoding='utf-8') as f:
                return yaml.safe_load(f) or {}
        return None
    
    def save_current_yaml(self, content: str):
        """保存当前渲染的YAML"""
        current_file = self.backup_dir / 'current.yaml'
        with open(current_file, 'w', encoding='utf-8') as f:
            f.write(content)