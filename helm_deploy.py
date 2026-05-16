#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
helm_deploy.py - 入口脚本
==========================
所有逻辑均在 helm_deploy/ 包中实现，本脚本只做：
  1. 解析命令行
  2. 初始化日志
  3. 分发到对应子命令处理器
"""

import sys
from pathlib import Path

# 确保项目根目录在 sys.path 中
sys.path.insert(0, str(Path(__file__).resolve().parent))

from helm_deploy import (
    HelmDeployer,
    build_parser,
    setup_logging,
    get_base_dir,
)


def main():
    parser = build_parser()
    args = parser.parse_args()

    if not args.command:
        parser.print_help()
        sys.exit(0)

    setup_logging()

    base_dir = get_base_dir()
    deployer = HelmDeployer(base_dir)

    if args.command == 'init':
        deployer.init_project(args)

    elif args.command == 'deploy':
        deployer.deploy(args)

    elif args.command == 'rollback':
        deployer.rollback(args)

    elif args.command == 'restart':
        deployer.restart(args)

    elif args.command == 'status':
        deployer.status(args)

    else:
        parser.print_help()
        sys.exit(1)


if __name__ == '__main__':
    main()
