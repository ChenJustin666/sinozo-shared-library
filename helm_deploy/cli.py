"""命令行参数解析模块"""
import argparse


def build_parser() -> argparse.ArgumentParser:
    """构建完整的命令行解析器"""
    parser = argparse.ArgumentParser(
        prog='helm_deploy.py',
        description='Helm 通用服务部署工具 v2.0',
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog="""
示例:
  # 初始化项目
  python3 helm_deploy.py init --project adv --environments test prod

  # 首次部署
  python3 helm_deploy.py deploy --init \\
    --project adv --environment test --service ad-puller \\
    --image-name ad-puller --image-tag v1.0.0 --port 8080 --replicas 2

  # 更新镜像
  python3 helm_deploy.py deploy \\
    --project adv --environment test --service ad-puller --image-tag v1.0.1

  # 开启监控 annotations
  python3 helm_deploy.py deploy \\
    --project adv --environment prod --service ad-puller --image-tag v2.0.0 \\
    --enable-monitoring --monitoring-path /actuator/prometheus

  # 开启 HPA + Ingress + PVC
  python3 helm_deploy.py deploy \\
    --project adv --environment prod --service ad-puller --image-tag v2.0.0 \\
    --enable-hpa --hpa-max-replicas 10 \\
    --enable-ingress --ingress-host api.example.com \\
    --enable-pvc --pvc-storage 10Gi

  # 回滚
  python3 helm_deploy.py rollback --project adv --environment prod --service ad-puller

  # 重启
  python3 helm_deploy.py restart --project adv --environment prod --service ad-puller
"""
    )

    subparsers = parser.add_subparsers(dest='command', help='子命令')

    # ──────────────────────────────────────────────
    # init 子命令
    # ──────────────────────────────────────────────
    p_init = subparsers.add_parser('init', help='初始化新项目')
    p_init.add_argument('--project', required=True, help='项目名称')
    p_init.add_argument('--environments', nargs='+', default=['test', 'prod'],
                        help='环境列表 (默认: test prod)')
    p_init.add_argument('--registry', default='', help='Docker 镜像仓库地址')
    p_init.add_argument('--copy-docker-from', dest='copy_docker_from', default='',
                        help='从已有项目复用 Docker 配置')
    p_init.add_argument('--kubeconfig-content', dest='kubeconfig_content', nargs='+',
                        help='kubeconfig 内容（格式: env:content）')
    p_init.add_argument('--example-service', dest='example_service', action='store_true',
                        help='创建示例服务目录')

    # ──────────────────────────────────────────────
    # deploy 子命令
    # ──────────────────────────────────────────────
    p_deploy = subparsers.add_parser('deploy', help='部署/更新服务')

    # 必选：项目/环境/服务
    grp_base = p_deploy.add_argument_group('基础配置（必选）')
    grp_base.add_argument('--project',     required=True, help='项目名称')
    grp_base.add_argument('--environment', required=True, choices=['test', 'prod', 'staging'],
                          help='部署环境')
    grp_base.add_argument('--service',     required=True, help='服务名称')

    # 镜像
    grp_img = p_deploy.add_argument_group('镜像配置')
    grp_img.add_argument('--image-name',      dest='image_name',      default='',
                         help='镜像名称（首次部署必填）')
    grp_img.add_argument('--image-tag',       dest='image_tag',       default='',
                         help='镜像 Tag（首次部署必填）')
    grp_img.add_argument('--docker-registry', dest='docker_registry', default='',
                         help='镜像仓库（覆盖 project.yaml）')
    grp_img.add_argument('--image-pull-secret', dest='image_pull_secret', default='regcred',
                         help='imagePullSecret 名称（默认: regcred）')
    grp_img.add_argument('--create-pull-secret', dest='create_pull_secret', action='store_true',
                         help='是否由 Helm 管理 regcred（独立管理时不需要）')
    grp_img.add_argument('--update-regcred', dest='update_regcred', action='store_true',
                         help='强制更新 regcred')

    # 服务
    grp_svc = p_deploy.add_argument_group('服务配置')
    grp_svc.add_argument('--port',         type=int, default=0,
                         help='容器端口（首次部署必填）')
    grp_svc.add_argument('--replicas',     type=int, default=0,
                         help='副本数（首次部署必填）')
    grp_svc.add_argument('--service-type', dest='service_type', default='ClusterIP',
                         choices=['ClusterIP', 'NodePort', 'LoadBalancer'],
                         help='Service 类型（默认: ClusterIP）')
    grp_svc.add_argument('--namespace',    default='',
                         help='命名空间（默认从 project.yaml 读取）')
    grp_svc.add_argument('--kubeconfig',   default='',
                         help='kubeconfig 路径（覆盖 project.yaml）')

    # 环境变量
    grp_env = p_deploy.add_argument_group('环境变量')
    grp_env.add_argument('--env', nargs='+', metavar='KEY=VALUE',
                         help='环境变量，如 APP_ENV=prod')
    grp_env.add_argument('--env-from-secret', dest='env_from_secret', nargs='+',
                         metavar='SECRET:KEY:ENV',
                         help='从 Secret 引用环境变量，如 my-secret:password:DB_PASS')
    grp_env.add_argument('--env-from-configmap', dest='env_from_configmap', nargs='+',
                         metavar='CM:KEY:ENV',
                         help='从 ConfigMap 引用环境变量，如 my-cm:config.yaml:CONFIG_PATH')

    # Java
    grp_java = p_deploy.add_argument_group('Java 配置')
    grp_java.add_argument('--java-opts', dest='java_opts', default='',
                          help='JVM 参数，如 -Xms512m -Xmx2g')

    # 探针（可选）
    grp_probe = p_deploy.add_argument_group('健康探针（可选，默认不开启）')
    grp_probe.add_argument('--enable-probes', dest='enable_probes', action='store_true',
                           help='开启 liveness/readiness/startup 探针')
    grp_probe.add_argument('--probes-path', dest='probes_path', default='/health',
                           help='探针 HTTP 路径（默认: /health）')
    grp_probe.add_argument('--probes-port', dest='probes_port', type=int, default=0,
                           help='探针端口（默认与 --port 相同）')

    # 监控（可选）
    grp_mon = p_deploy.add_argument_group('监控 Annotations（可选，默认不开启）')
    grp_mon.add_argument('--enable-monitoring', dest='enable_monitoring', action='store_true',
                         help='开启 Prometheus 监控 annotations（在 Deployment 的 annotations 中注入）')
    grp_mon.add_argument('--monitoring-path', dest='monitoring_path', default='/metrics',
                         help='Prometheus metrics 路径（默认: /metrics）')
    grp_mon.add_argument('--monitoring-port', dest='monitoring_port', type=int, default=0,
                         help='Prometheus scrape 端口（默认与 --port 相同）')

    # 资源（可选）
    grp_res = p_deploy.add_argument_group('资源限制（可选，默认不开启）')
    grp_res.add_argument('--enable-resources', dest='enable_resources', action='store_true',
                         help='开启 resources limits/requests')
    grp_res.add_argument('--cpu-limit',       dest='cpu_limit',       default='1',
                         help='CPU limit（默认: 1）')
    grp_res.add_argument('--memory-limit',    dest='memory_limit',    default='2Gi',
                         help='内存 limit（默认: 2Gi）')
    grp_res.add_argument('--cpu-requests',    dest='cpu_requests',    default='256m',
                         help='CPU requests（默认: 256m）')
    grp_res.add_argument('--memory-requests', dest='memory_requests', default='512Mi',
                         help='内存 requests（默认: 512Mi）')

    # Ingress（可选）
    grp_ing = p_deploy.add_argument_group('Ingress（可选，默认不开启）')
    grp_ing.add_argument('--enable-ingress', dest='enable_ingress', action='store_true',
                         help='开启 Ingress（需同时指定 --ingress-host）')
    grp_ing.add_argument('--ingress-host',   dest='ingress_host',   default='',
                         help='Ingress 域名，如 api.example.com')
    grp_ing.add_argument('--ingress-tls',    dest='ingress_tls',    action='store_true',
                         help='开启 Ingress TLS')

    # HPA（可选）
    grp_hpa = p_deploy.add_argument_group('HPA 自动伸缩（可选，默认不开启）')
    grp_hpa.add_argument('--enable-hpa',        dest='enable_hpa',        action='store_true',
                         help='开启 HorizontalPodAutoscaler')
    grp_hpa.add_argument('--hpa-max-replicas',  dest='hpa_max_replicas',  type=int, default=5,
                         help='HPA 最大副本数（默认: 5）')
    grp_hpa.add_argument('--hpa-cpu-target',    dest='hpa_cpu_target',    type=int, default=70,
                         help='HPA CPU 目标使用率 %%(默认: 70)')

    # PVC（可选）
    grp_pvc = p_deploy.add_argument_group('PVC 持久化存储（可选，默认不开启）')
    grp_pvc.add_argument('--enable-pvc',         dest='enable_pvc',         action='store_true',
                         help='开启 PersistentVolumeClaim')
    grp_pvc.add_argument('--pvc-storage',        dest='pvc_storage',        default='1Gi',
                         help='PVC 存储大小（默认: 1Gi）')
    grp_pvc.add_argument('--pvc-storage-class',  dest='pvc_storage_class',  default='',
                         help='StorageClass 名称（留空使用集群默认）')
    grp_pvc.add_argument('--pvc-access-mode',    dest='pvc_access_mode',    default='RWO',
                         choices=['RWO', 'RWX'], help='访问模式 RWO|RWX（默认: RWO）')
    grp_pvc.add_argument('--enable-heapdump',    dest='enable_heapdump',    action='store_true',
                         help='开启 heapdump 目录挂载（依赖 --enable-pvc）')
    grp_pvc.add_argument('--heapdump-path',      dest='heapdump_path',      default='/heapdump',
                         help='heapdump 挂载路径（默认: /heapdump）')

    # Secret（可选）
    grp_sec = p_deploy.add_argument_group('Secret（可选，默认不开启）')
    grp_sec.add_argument('--create-secret', dest='create_secret', nargs='+',
                         metavar='NAME:K1=V1,K2=V2',
                         help='创建 Secret，格式: NAME:KEY1=VAL1,KEY2=VAL2')
    grp_sec.add_argument('--enable-secret-dir', dest='enable_secret_dir', action='store_true',
                         help='自动 apply projects/{project}/{env}/{service}/secret/ 目录')

    # ConfigMap（可选）
    grp_cm = p_deploy.add_argument_group('ConfigMap（可选，有文件时自动启用）')
    grp_cm.add_argument('--enable-configmap',    dest='enable_configmap',    action='store_true',
                        help='强制开启 ConfigMap（有 configmap/ 目录时自动检测）')
    grp_cm.add_argument('--configmap-mount-path', dest='configmap_mount_path', default='/config',
                        help='ConfigMap 挂载路径（默认: /config）')

    # 部署控制
    grp_ctrl = p_deploy.add_argument_group('部署控制')
    grp_ctrl.add_argument('--init',           action='store_true',
                          help='首次部署（新服务必须加此参数）')
    grp_ctrl.add_argument('--dry-run',        dest='dry_run',        action='store_true',
                          help='只渲染模板，不实际部署')
    grp_ctrl.add_argument('--skip-health-check', dest='skip_health_check', action='store_true',
                          help='跳过部署后健康检查')
    grp_ctrl.add_argument('--health-check-timeout', dest='health_check_timeout',
                          type=int, default=300,
                          help='健康检查超时秒数（默认: 300）')
    grp_ctrl.add_argument('--health-check-level', dest='health_check_level',
                          default='standard', choices=['none', 'basic', 'standard'],
                          help='健康检查级别（默认: standard）')

    # ──────────────────────────────────────────────
    # rollback 子命令
    # ──────────────────────────────────────────────
    p_rollback = subparsers.add_parser('rollback', help='回滚到历史版本')
    p_rollback.add_argument('--project',     required=True)
    p_rollback.add_argument('--environment', required=True)
    p_rollback.add_argument('--service',     required=True)
    p_rollback.add_argument('--namespace',   default='')
    p_rollback.add_argument('--kubeconfig',  default='')
    p_rollback.add_argument('--revision',    type=int, default=0,
                            help='回滚到指定版本（0 表示上一个版本）')
    p_rollback.add_argument('--list-history', dest='list_history', action='store_true',
                            help='查看历史版本列表')

    # ──────────────────────────────────────────────
    # restart 子命令
    # ──────────────────────────────────────────────
    p_restart = subparsers.add_parser('restart', help='滚动重启服务')
    p_restart.add_argument('--project',     required=True)
    p_restart.add_argument('--environment', required=True)
    p_restart.add_argument('--service',     required=True)
    p_restart.add_argument('--namespace',   default='')
    p_restart.add_argument('--kubeconfig',  default='')
    p_restart.add_argument('--wait',        action='store_true',
                           help='等待重启完成')

    # ──────────────────────────────────────────────
    # status 子命令
    # ──────────────────────────────────────────────
    p_status = subparsers.add_parser('status', help='查看服务状态')
    p_status.add_argument('--project',     required=True)
    p_status.add_argument('--environment', required=True)
    p_status.add_argument('--service',     required=True)
    p_status.add_argument('--namespace',   default='')
    p_status.add_argument('--kubeconfig',  default='')

    return parser
