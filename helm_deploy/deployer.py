"""核心部署类"""
import sys
import json
import subprocess
import logging
from pathlib import Path

from .kubectl import KubectlClient
from .health_check import HealthChecker
from .backup import BackupManager
from .config import ConfigManager

logger = logging.getLogger(__name__)


class HelmDeployer:
    """Helm部署器"""

    def __init__(self, base_dir: Path):
        self.base_dir = base_dir
        self.charts_dir = base_dir / 'charts' / 'generic-service'
        self.projects_dir = base_dir / 'projects'
        self.config = ConfigManager(self.projects_dir)

    # ──────────────────────────────────────────────
    # 公开入口
    # ──────────────────────────────────────────────

    def deploy(self, args):
        """执行部署"""
        project_config = self.config.load_project_config(args.project)
        clusters = project_config.get('clusters', {})
        cluster_config = clusters.get(args.environment, {})
        namespace = args.namespace or cluster_config.get('namespace', f"{args.project}-{args.environment}")

        try:
            kubeconfig = self.config.get_kubeconfig_path(args.project, args.environment, args.kubeconfig)
        except (FileNotFoundError, ValueError) as e:
            logger.error(str(e))
            sys.exit(1)

        kubectl  = KubectlClient(kubeconfig, namespace)
        backup_dir = self.config.get_backup_dir(args.project, args.environment, args.service)
        backup   = BackupManager(backup_dir)
        health   = HealthChecker(kubectl)

        saved_values = backup.load_values()
        is_new_service = saved_values is None

        if is_new_service and not args.init:
            logger.error("❌ 新服务需要使用 --init 参数进行初始化部署")
            logger.error("   示例: helm_deploy.py deploy --init --project xxx --service xxx --image-name xxx ...")
            sys.exit(1)

        if is_new_service:
            self._validate_new_service_args(args)
            logger.info("🆕 初始化新服务")
        else:
            logger.info("📦 更新模式 - 合并已保存配置")
            args = self._merge_args(args, saved_values)

        self._print_deploy_info(args, namespace, kubeconfig)

        new_values = self._build_values(args, project_config, namespace)
        if saved_values:
            values = ConfigManager.deep_merge(saved_values, new_values)
            logger.info("✅ 配置已合并")
        else:
            values = new_values

        service_dir = self.config.get_service_dir(args.project, args.environment, args.service)
        backup.backup_values()
        backup.backup_configmap(service_dir)
        backup.save_values(values)
        logger.info("✅ 生成values文件")

        helm_cmd = ['sudo', '/usr/local/bin/helm', '--kubeconfig', str(kubeconfig)]

        if args.dry_run:
            self._dry_run(helm_cmd, args.service, namespace, backup_dir)
        else:
            self._real_deploy(helm_cmd, kubectl, backup, health, args, namespace, project_config)

    # ──────────────────────────────────────────────
    # 私有：参数校验与合并
    # ──────────────────────────────────────────────

    def _validate_new_service_args(self, args):
        """首次部署必填参数校验"""
        required = ['image_name', 'image_tag', 'port', 'replicas']
        for field in required:
            if not getattr(args, field, None):
                logger.error(f"❌ 新服务必须指定 --{field.replace('_', '-')}")
                sys.exit(1)

    def _merge_args(self, args, saved_values):
        """将命令行参数与已保存配置合并（命令行优先）"""
        if not args.image_name:
            args.image_name = saved_values.get('image', {}).get('name')
        if not args.image_tag:
            args.image_tag = saved_values.get('image', {}).get('tag')
        if not args.port:
            args.port = saved_values.get('service', {}).get('port')
        if not args.replicas:
            args.replicas = saved_values.get('service', {}).get('replicas')
        if not getattr(args, 'service_type', None):
            args.service_type = saved_values.get('service', {}).get('type', 'ClusterIP')
        return args

    # ──────────────────────────────────────────────
    # 私有：构建 values
    # ──────────────────────────────────────────────

    def _build_values(self, args, project_config: dict, namespace: str) -> dict:
        """根据命令行参数构建 Helm values 字典"""
        docker_config = project_config.get('docker', {})

        # ── 基础配置（deployment + service 默认生成）──
        values = {
            'project': args.project,
            'environment': args.environment,
            'service': {
                'name': args.service,
                'namespace': namespace,
                'port': args.port,
                'replicas': args.replicas,
                'type': getattr(args, 'service_type', 'ClusterIP')
            },
            'image': {
                'registry': getattr(args, 'docker_registry', None) or docker_config.get('registry', ''),
                'name': args.image_name,
                'tag': args.image_tag,
                'pullSecret': getattr(args, 'image_pull_secret', 'regcred') or docker_config.get('pull_secret', 'regcred'),
                'pullSecretData': docker_config.get('pull_secret_data', '')
            }
        }

        # ── Java JVM 参数 ──
        if getattr(args, 'java_opts', None):
            values['java'] = {'enabled': True, 'opts': args.java_opts}

        # ── 环境变量 ──
        env_list = self._build_env_list(args)
        if env_list:
            values['env'] = env_list

        # ── 探针（可选，需 --enable-probes）──
        if getattr(args, 'enable_probes', False):
            values['probes'] = {
                'enabled': True,
                'path': getattr(args, 'probes_path', '/health'),
                'port': getattr(args, 'probes_port', None) or args.port
            }

        # ── 监控 Annotations（可选，需 --enable-monitoring）──
        if getattr(args, 'enable_monitoring', False):
            values['monitoring'] = {
                'enabled': True,
                'path': getattr(args, 'monitoring_path', '/metrics'),
                'port': getattr(args, 'monitoring_port', None) or args.port
            }

        # ── 资源限制（可选，需 --enable-resources）──
        if getattr(args, 'enable_resources', False):
            values['resources'] = {
                'enabled': True,
                'limits': {
                    'cpu': getattr(args, 'cpu_limit', '1'),
                    'memory': getattr(args, 'memory_limit', '2Gi')
                },
                'requests': {
                    'cpu': getattr(args, 'cpu_requests', '256m'),
                    'memory': getattr(args, 'memory_requests', '512Mi')
                }
            }

        # ── Ingress（可选，需 --enable-ingress --ingress-host）──
        if getattr(args, 'enable_ingress', False) and getattr(args, 'ingress_host', None):
            values['ingress'] = {
                'enabled': True,
                'className': 'nginx',
                'hosts': [{'host': args.ingress_host, 'paths': [{'path': '/', 'port': args.port}]}],
                'tls': {'enabled': getattr(args, 'ingress_tls', False)}
            }

        # ── HPA 自动伸缩（可选，需 --enable-hpa）──
        if getattr(args, 'enable_hpa', False):
            values['hpa'] = {
                'enabled': True,
                'minReplicas': args.replicas,
                'maxReplicas': getattr(args, 'hpa_max_replicas', 5),
                'cpuTarget': getattr(args, 'hpa_cpu_target', 70)
            }

        # ── PVC 持久化存储（可选，需 --enable-pvc）──
        if getattr(args, 'enable_pvc', False):
            values['pvc'] = {
                'enabled': True,
                'storage': getattr(args, 'pvc_storage', '1Gi'),
                'storageClass': getattr(args, 'pvc_storage_class', None),
                'accessModes': [
                    'ReadWriteMany' if getattr(args, 'pvc_access_mode', 'RWO') == 'RWX'
                    else 'ReadWriteOnce'
                ]
            }
            # heapdump 挂载（依赖 PVC）
            if getattr(args, 'enable_heapdump', False):
                heapdump_path = getattr(args, 'heapdump_path', '/heapdump')
                values['volumeMounts'] = [{'name': 'heapdump', 'mountPath': heapdump_path}]
                values['volumes'] = [{'name': 'heapdump', 'persistentVolumeClaim': {'claimName': f'{args.service}-pvc'}}]

        # ── ConfigMap（可选，需 --enable-configmap 或目录有文件）──
        configmap_enabled = getattr(args, 'enable_configmap', False)
        if not configmap_enabled:
            service_dir = self.config.get_service_dir(args.project, args.environment, args.service)
            cm_dir = service_dir / 'configmap'
            if cm_dir.exists() and (
                list(cm_dir.glob('*.yaml')) +
                list(cm_dir.glob('*.yml')) +
                list(cm_dir.glob('*.properties'))
            ):
                configmap_enabled = True
                logger.info("📁 检测到 configmap 目录有文件，自动启用 ConfigMap")

        if configmap_enabled:
            service_dir = self.config.get_service_dir(args.project, args.environment, args.service)
            cm_data = self.config.get_configmap_data(service_dir)
            values['configmap'] = {
                'enabled': True,
                'mountPath': getattr(args, 'configmap_mount_path', '/config')
            }
            if cm_data:
                values['configmap']['data'] = cm_data
                values['configmap']['checksum'] = self.config.calculate_configmap_checksum(cm_data)
                logger.info(f"📁 ConfigMap 文件: {len(cm_data)} 个, checksum: {values['configmap']['checksum']}")

        return values

    def _build_env_list(self, args) -> list:
        """构建环境变量列表"""
        env_list = []

        # --env KEY=VALUE
        for item in getattr(args, 'env', None) or []:
            if '=' in item:
                key, value = item.split('=', 1)
                env_list.append({'name': key, 'value': value})

        # --env-from-secret SECRET_NAME:KEY:ENV_NAME
        for item in getattr(args, 'env_from_secret', None) or []:
            parts = item.split(':')
            if len(parts) == 3:
                secret_name, secret_key, env_name = parts
                env_list.append({
                    'name': env_name,
                    'valueFrom': {'secretKeyRef': {'name': secret_name, 'key': secret_key}}
                })

        # --env-from-configmap CM_NAME:KEY:ENV_NAME
        for item in getattr(args, 'env_from_configmap', None) or []:
            parts = item.split(':')
            if len(parts) == 3:
                cm_name, cm_key, env_name = parts
                env_list.append({
                    'name': env_name,
                    'valueFrom': {'configMapKeyRef': {'name': cm_name, 'key': cm_key}}
                })

        return env_list

    # ──────────────────────────────────────────────
    # 私有：dry-run
    # ──────────────────────────────────────────────

    def _dry_run(self, helm_cmd: list, service: str, namespace: str, backup_dir: Path):
        """dry-run 渲染模板并输出"""
        logger.info("📄 渲染模板 (dry-run)...")
        import tempfile, yaml
        values_file = backup_dir / 'values.yaml'

        cmd = helm_cmd + [
            'template', service,
            str(self.charts_dir),
            '-f', str(values_file),
            '-n', namespace
        ]
        result = subprocess.run(cmd, stdout=subprocess.PIPE, stderr=subprocess.PIPE, universal_newlines=True)
        if result.returncode == 0:
            # 同时保存到 all.yaml
            all_yaml = backup_dir / 'all.yaml'
            all_yaml.write_text(result.stdout, encoding='utf-8')
            logger.info(f"✅ 模板已保存: {all_yaml}")
            print(result.stdout)
        else:
            logger.error(f"❌ 模板渲染失败:\n{result.stderr}")
            sys.exit(1)

    # ──────────────────────────────────────────────
    # 私有：真实部署
    # ──────────────────────────────────────────────

    def _real_deploy(self, helm_cmd: list, kubectl: KubectlClient,
                     backup: BackupManager, health: HealthChecker,
                     args, namespace: str, project_config: dict):
        """执行真正的 helm upgrade --install"""
        service    = args.service
        backup_dir = backup.backup_dir
        values_file = backup_dir / 'values.yaml'

        # 1. 确保 regcred 存在
        self._ensure_regcred(kubectl, namespace, project_config,
                             getattr(args, 'update_regcred', False))

        # 2. 接管已存在的非 Helm 资源
        self._adopt_resources(kubectl, service)

        # 3. 处理用户指定的 Secret
        if getattr(args, 'create_secret', None):
            self._create_user_secrets(kubectl, args.create_secret)

        # 4. 导入 secret/ 目录
        if getattr(args, 'enable_secret_dir', False):
            service_dir = self.config.get_service_dir(args.project, args.environment, service)
            self._apply_dir(kubectl, service_dir / 'secret')

        # 5. helm upgrade --install
        cmd = helm_cmd + [
            'upgrade', '--install', service,
            str(self.charts_dir),
            '-f', str(values_file),
            '-n', namespace,
            '--create-namespace',
            '--atomic',
            '--timeout', '5m'
        ]
        logger.info(f"🚀 执行: {' '.join(cmd)}")
        result = subprocess.run(cmd, stdout=subprocess.PIPE, stderr=subprocess.PIPE, universal_newlines=True)

        if result.returncode != 0:
            logger.error(f"❌ 部署失败:\n{result.stderr}")
            backup.log_history('deploy', args.image_tag, 'failed')
            sys.exit(1)

        logger.info("✅ Helm 部署成功")
        if result.stdout:
            logger.info(result.stdout)

        # 6. 保存渲染后的 YAML（用于审计）
        tpl_cmd = helm_cmd + [
            'template', service, str(self.charts_dir),
            '-f', str(values_file), '-n', namespace
        ]
        tpl = subprocess.run(tpl_cmd, stdout=subprocess.PIPE, stderr=subprocess.PIPE, universal_newlines=True)
        if tpl.returncode == 0:
            backup.save_current_yaml(tpl.stdout)

        # 7. 健康检查
        skip = getattr(args, 'skip_health_check', False)
        timeout = getattr(args, 'health_check_timeout', 300)
        level   = getattr(args, 'health_check_level', 'standard')
        if skip:
            level = 'none'

        ok = health.check(service, timeout=timeout, level=level)
        if not ok:
            health.diagnose(service)
            backup.log_history('deploy', args.image_tag, 'health_check_failed')
            sys.exit(1)

        # 8. 记录历史
        backup.log_history('deploy', args.image_tag, 'success')
        logger.info("=" * 60)
        logger.info(f"🎉 {service} 部署完成！镜像: {args.image_name}:{args.image_tag}")
        logger.info("=" * 60)

    # ──────────────────────────────────────────────
    # 私有：辅助方法
    # ──────────────────────────────────────────────

    def _print_deploy_info(self, args, namespace, kubeconfig):
        logger.info("=" * 60)
        logger.info("Helm 通用部署 v2.0")
        logger.info("=" * 60)
        logger.info(f"项目: {args.project}")
        logger.info(f"环境: {args.environment}")
        logger.info(f"服务: {args.service}")
        logger.info(f"命名空间: {namespace}")
        logger.info(f"kubeconfig: {kubeconfig}")
        logger.info(f"镜像: {args.image_name}:{args.image_tag}")
        logger.info(f"备份目录: {self.config.get_backup_dir(args.project, args.environment, args.service)}")
        logger.info("=" * 60)

    def _ensure_regcred(self, kubectl: KubectlClient, namespace: str, project_config: dict, force: bool):
        """确保 regcred Secret 存在"""
        docker_config = project_config.get('docker', {})
        pull_secret_data = docker_config.get('pull_secret_data', '')
        if not pull_secret_data:
            logger.warning("⚠️ 未配置 pull_secret_data，跳过 regcred 管理")
            return

        exists = kubectl.resource_exists('secret', 'regcred')
        if exists and not force:
            logger.info("✅ 共享 regcred 已存在，跳过创建")
            return

        from datetime import datetime
        action = "更新" if exists else "创建"
        logger.info(f"🔑 {action}共享 docker-registry-secret...")
        secret_yaml = (
            f"apiVersion: v1\nkind: Secret\nmetadata:\n"
            f"  name: regcred\n  namespace: {namespace}\n"
            f"  annotations:\n    created-by: helm-deploy-shared\n"
            f"    created-at: \"{datetime.now().strftime('%Y-%m-%d %H:%M:%S')}\"\n"
            f"type: kubernetes.io/dockerconfigjson\n"
            f"data:\n  .dockerconfigjson: {pull_secret_data}\n"
        )
        tmp = Path('/tmp/regcred_temp.yaml')
        tmp.write_text(secret_yaml)
        ok = kubectl.apply_file(tmp)
        tmp.unlink(missing_ok=True)
        if ok:
            logger.info(f"✅ regcred {action}成功")
        else:
            logger.warning(f"⚠️ regcred {action}失败，继续部署...")

    def _adopt_resources(self, kubectl: KubectlClient, service: str):
        """接管未被 Helm 管理的已存在资源"""
        targets = [
            ('secret',    f'{service}-secret'),
            ('configmap', f'{service}-config'),
            ('service',   f'{service}-service'),
            ('pvc',       f'{service}-pvc'),
        ]
        adopted = 0
        for rtype, rname in targets:
            if not kubectl.resource_exists(rtype, rname):
                continue
            # 检查是否已被 Helm 管理
            managed = kubectl.get_resource(rtype, rname,
                output='jsonpath={.metadata.labels.app\\.kubernetes\\.io/managed-by}')
            if managed == 'Helm':
                continue
            logger.info(f"🔍 接管 {rtype}/{rname}...")
            kubectl.label_resource(rtype, rname, ['app.kubernetes.io/managed-by=Helm'])
            kubectl.annotate_resource(rtype, rname, [
                f'meta.helm.sh/release-name={service}',
                f'meta.helm.sh/release-namespace={kubectl.namespace}'
            ])
            adopted += 1
        if adopted:
            logger.info(f"✅ 共接管 {adopted} 个资源")

    def _create_user_secrets(self, kubectl: KubectlClient, secret_defs: list):
        """创建命令行指定的 Secret（SECRET_NAME:KEY1=VAL1,KEY2=VAL2）"""
        for secret_def in secret_defs:
            parts = secret_def.split(':', 1)
            if len(parts) != 2:
                logger.warning(f"⚠️ Secret 格式错误: {secret_def}")
                continue
            name, kv_str = parts
            if kubectl.resource_exists('secret', name):
                kubectl.delete_resource('secret', name)
            cmd = ['kubectl', '--kubeconfig', str(kubectl.kubeconfig),
                   '-n', kubectl.namespace, 'create', 'secret', 'generic', name]
            for kv in kv_str.split(','):
                if '=' in kv:
                    cmd.append(f'--from-literal={kv}')
            result = subprocess.run(['sudo'] + cmd, stdout=subprocess.PIPE,
                                    stderr=subprocess.PIPE, universal_newlines=True)
            if result.returncode == 0:
                logger.info(f"✅ Secret '{name}' 创建成功")
            else:
                logger.warning(f"⚠️ Secret '{name}' 创建失败: {result.stderr}")

    def _apply_dir(self, kubectl: KubectlClient, directory: Path):
        """apply 目录下所有 yaml 文件"""
        if not directory.exists():
            return
        for f in list(directory.glob('*.yaml')) + list(directory.glob('*.yml')):
            ok = kubectl.apply_file(f)
            if ok:
                logger.info(f"✅ 已 apply: {f.name}")
            else:
                logger.warning(f"⚠️ apply 失败: {f.name}")

    # ──────────────────────────────────────────────
    # 公开：init / rollback / restart / status
    # ──────────────────────────────────────────────

    def init_project(self, args):
        """初始化新项目目录结构和 project.yaml"""
        import yaml
        project_dir = self.projects_dir / args.project
        dirs = [project_dir / 'kubeconfig']
        for env in args.environments:
            env_dir = project_dir / env
            dirs.append(env_dir)
            if getattr(args, 'example_service', False):
                svc = f'{args.project}-app'
                dirs += [env_dir / svc / 'configmap', env_dir / svc / 'secret']
        for d in dirs:
            d.mkdir(parents=True, exist_ok=True)

        docker_config = {'registry': getattr(args, 'registry', ''), 'pull_secret': 'regcred', 'pull_secret_data': ''}

        # 从已有项目复用 docker 配置
        if getattr(args, 'copy_docker_from', ''):
            src = self.projects_dir / args.copy_docker_from / 'project.yaml'
            if src.exists():
                with open(src, 'r') as f:
                    src_cfg = yaml.safe_load(f) or {}
                if 'docker' in src_cfg:
                    docker_config = src_cfg['docker'].copy()
                    logger.info(f"✅ 复用 '{args.copy_docker_from}' Docker 配置")

        # 从 comm 目录读取
        if not docker_config.get('pull_secret_data'):
            first_env = args.environments[0]
            comm = self.config.read_docker_config_from_comm(self.base_dir, first_env)
            if comm:
                docker_config = comm
                logger.info(f"✅ 从 comm/{first_env}/docker-registry-secret.yaml 读取 Docker 配置")

        # 写入 kubeconfig 内容
        for env_content in getattr(args, 'kubeconfig_content', None) or []:
            if ':' in env_content:
                env, content = env_content.split(':', 1)
            else:
                env, content = 'test', env_content
            if env in args.environments:
                kc_file = project_dir / 'kubeconfig' / f'{env}.config'
                kc_file.write_text(content)
                logger.info(f"✅ 写入 kubeconfig: {kc_file}")

        project_config = {
            'project': args.project,
            'docker': docker_config,
            'clusters': {},
            'defaults': {}
        }
        for env in args.environments:
            project_config['clusters'][env] = {
                'namespace': f'{args.project}-{env}',
                'kubeconfig': f'kubeconfig/{env}.config'
            }
            project_config['defaults'][env] = {
                'replicas': 1,
                'resources': {'limits': {'cpu': '500m', 'memory': '512Mi'}}
            }

        config_file = project_dir / 'project.yaml'
        with open(config_file, 'w', encoding='utf-8') as f:
            yaml.dump(project_config, f, default_flow_style=False, allow_unicode=True)
        (project_dir / 'kubeconfig' / '.gitkeep').touch()

        logger.info("=" * 60)
        logger.info(f"✅ 项目 '{args.project}' 初始化完成: {project_dir}")
        logger.info(f"   Docker 仓库: {docker_config.get('registry', '未配置')}")
        missing = [env for env in args.environments
                   if not (project_dir / 'kubeconfig' / f'{env}.config').exists()]
        if missing:
            logger.info(f"   ⚠️  还需放置 kubeconfig: {', '.join(missing)}")
        logger.info("=" * 60)

    def rollback(self, args):
        """回滚到历史版本"""
        project_config = self.config.load_project_config(args.project)
        cluster_config = project_config.get('clusters', {}).get(args.environment, {})
        namespace = args.namespace or cluster_config.get('namespace', f"{args.project}-{args.environment}")
        try:
            kubeconfig = self.config.get_kubeconfig_path(args.project, args.environment, args.kubeconfig)
        except (FileNotFoundError, ValueError) as e:
            logger.error(str(e)); sys.exit(1)

        backup_dir = self.config.get_backup_dir(args.project, args.environment, args.service)
        helm_cmd = ['sudo', '/usr/local/bin/helm', '--kubeconfig', str(kubeconfig)]

        logger.info("=" * 60)
        logger.info(f"🔄 回滚: {args.project}/{args.environment}/{args.service} -> ns:{namespace}")

        if getattr(args, 'list_history', False):
            result = subprocess.run(
                helm_cmd + ['history', args.service, '-n', namespace],
                stdout=subprocess.PIPE, stderr=subprocess.PIPE, universal_newlines=True
            )
            print(result.stdout if result.returncode == 0 else result.stderr)
            return

        cmd = helm_cmd + ['rollback', args.service]
        if getattr(args, 'revision', 0):
            cmd.append(str(args.revision))
        cmd += ['-n', namespace]

        result = subprocess.run(cmd, stdout=subprocess.PIPE, stderr=subprocess.PIPE, universal_newlines=True)
        if result.returncode == 0:
            logger.info("✅ 回滚成功")
            BackupManager(backup_dir).log_history('rollback', f'revision-{getattr(args, "revision", "prev")}', 'success')
        else:
            logger.error(f"❌ 回滚失败: {result.stderr}")
            BackupManager(backup_dir).log_history('rollback', '', 'failed')
            sys.exit(1)

    def restart(self, args):
        """滚动重启服务"""
        project_config = self.config.load_project_config(args.project)
        cluster_config = project_config.get('clusters', {}).get(args.environment, {})
        namespace = args.namespace or cluster_config.get('namespace', f"{args.project}-{args.environment}")
        try:
            kubeconfig = self.config.get_kubeconfig_path(args.project, args.environment, args.kubeconfig)
        except (FileNotFoundError, ValueError) as e:
            logger.error(str(e)); sys.exit(1)

        kubectl = KubectlClient(kubeconfig, namespace)
        backup_dir = self.config.get_backup_dir(args.project, args.environment, args.service)

        logger.info(f"🔄 重启: {args.service} (namespace: {namespace})")
        ok = kubectl.rollout_restart(args.service)
        if not ok:
            logger.error("❌ 重启失败"); sys.exit(1)

        logger.info("✅ 重启命令已发送")
        BackupManager(backup_dir).log_history('restart', 'N/A', 'success')

        if getattr(args, 'wait', False):
            logger.info("⏳ 等待重启完成...")
            kubectl.rollout_status(args.service)
            logger.info("✅ 重启完成")

    def status(self, args):
        """查看服务状态"""
        project_config = self.config.load_project_config(args.project)
        cluster_config = project_config.get('clusters', {}).get(args.environment, {})
        namespace = args.namespace or cluster_config.get('namespace', f"{args.project}-{args.environment}")
        try:
            kubeconfig = self.config.get_kubeconfig_path(args.project, args.environment, args.kubeconfig)
        except (FileNotFoundError, ValueError) as e:
            logger.error(str(e)); sys.exit(1)

        kubectl = KubectlClient(kubeconfig, namespace)
        HealthChecker(kubectl).diagnose(args.service)
