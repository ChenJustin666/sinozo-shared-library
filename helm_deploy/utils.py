"""工具函数模块"""
import logging
import sys
from pathlib import Path


def setup_logging(level: str = "INFO") -> None:
    """初始化日志配置"""
    log_level = getattr(logging, level.upper(), logging.INFO)
    logging.basicConfig(
        level=log_level,
        format="%(asctime)s - %(levelname)s - %(message)s",
        datefmt="%Y-%m-%d %H:%M:%S",
    )


def get_base_dir() -> Path:
    """返回项目根目录（helm_deploy.py 所在目录）"""
    # 向上两级：helm_deploy/ -> 项目根
    return Path(__file__).resolve().parent.parent


def check_helm_installed() -> bool:
    """检查 helm 是否安装"""
    import subprocess
    result = subprocess.run(
        ["sudo", "/usr/local/bin/helm", "version", "--short"],
        stdout=subprocess.PIPE, stderr=subprocess.PIPE,
        universal_newlines=True
    )
    return result.returncode == 0


def check_kubectl_installed() -> bool:
    """检查 kubectl 是否安装"""
    import subprocess
    result = subprocess.run(
        ["kubectl", "version", "--client", "--short"],
        stdout=subprocess.PIPE, stderr=subprocess.PIPE,
        universal_newlines=True
    )
    return result.returncode == 0


def print_banner(version: str = "2.0.0") -> None:
    """打印启动横幅"""
    print(f"""
╔══════════════════════════════════════════╗
║     Helm 通用服务部署工具 v{version}        ║
║  deployment + service  默认生成           ║
║  ingress/hpa/pvc/secret 按需开启          ║
╚══════════════════════════════════════════╝
""")
