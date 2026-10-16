# -*- coding: utf-8 -*-
"""OMP 生命周期脚本共用函数，兼容 Python 2/3。"""

import argparse
import json
import os
import pwd
import re
import subprocess
import sys


SERVICE_NAME = "hkexCmdbSync"
DEFAULT_RUN_USER = "commonuser"
SERVICE_PROPERTIES = "service.properties"
NACOS_SERVICE_NAME = "nacos"
NACOS_PARAMS_FILE = "nacos.params"
NACOS_PARAM_KEYS = ("CW_NACOS_SERVER", "CW_NACOS_USERNAME",
                    "CW_NACOS_PASSWORD", "CW_NACOS_NAMESPACE")


def parse_args():
    parser = argparse.ArgumentParser()
    parser.add_argument("--data_json", required=True)
    parser.add_argument("--local_ip", required=True)
    parser.add_argument("--version")
    parser.add_argument("--backup_path")
    return parser.parse_args()


def load_records(args):
    with open(args.data_json, "r") as source:
        records = json.load(source)
    if isinstance(records, dict):
        records = [records]
    if not isinstance(records, list):
        raise ValueError("data_json must contain a list of service instances")
    return records


def find_instance(records, name, local_ip):
    matches = [record for record in records
               if record.get("name") == name and record.get("ip") == local_ip]
    if len(matches) != 1:
        raise ValueError("expected one {} instance for local_ip {}, found {}".format(
            name, local_ip, len(matches)))
    return matches[0]


def indexed_values(items):
    if isinstance(items, dict):
        return dict(items)
    values = {}
    if not isinstance(items, list):
        return values
    for item in items:
        if isinstance(item, dict) and item.get("key"):
            values[item["key"]] = item.get("default", "")
    return values


def dependency_instances(instance, records, service_name):
    """按 OMP 的 dependence 声明定位被依赖服务的实例列表。

    对齐 hkexConfigSyncServer/scripts/Core.py 的解析方式：先按 instance_name 精确匹配，
    没有再按 cluster_name 匹配；只有明确指定集群时才允许多个实例。
    """
    candidates = [record for record in records if record.get("name") == service_name]
    for dependency in instance.get("dependence") or []:
        if dependency.get("name") != service_name:
            continue
        instance_name = dependency.get("instance_name")
        cluster_name = dependency.get("cluster_name")
        if instance_name:
            matched = [record for record in candidates
                       if record.get("instance_name") == instance_name]
        elif cluster_name:
            matched = [record for record in candidates
                       if record.get("cluster_name") == cluster_name]
        else:
            matched = candidates
        if not matched or (len(matched) != 1 and (instance_name or not cluster_name)):
            raise ValueError(
                "expected one {} dependency instance, found {}; check the OMP dependence record"
                .format(service_name, len(matched)))
        return matched
    raise ValueError(
        "OMP data_json 未提供 {} 依赖实例；conf/hkexCmdbSync.yaml 的 dependencies 必须声明该服务"
        .format(service_name))


def require_absolute(value, name):
    if not value or not os.path.isabs(value):
        raise ValueError("{} must be an absolute path".format(name))
    if "\n" in value or "\r" in value or "\x00" in value:
        raise ValueError("{} contains an invalid control character".format(name))
    return value


def nacos_connection(instance, records):
    """从 OMP 依赖通道取 Nacos 连接参数。

    Nacos 以集群部署时 dependence 声明 cluster_name，这里把所有节点拼成逗号分隔的地址串
    （Nacos 客户端的 serverAddr 本身就是这个格式），单实例时退化成原来的单地址。
    其余参数取集群首个节点：同一集群内 username / password_enc / namespace 一致。
    """
    nacos_instances = dependency_instances(instance, records, NACOS_SERVICE_NAME)
    addresses = []
    for nacos in nacos_instances:
        host = nacos.get("ip")
        port = indexed_values(nacos.get("ports")).get("service_port")
        if not host or not port:
            raise ValueError("Nacos 依赖缺少安装参数: CW_NACOS_SERVER")
        addresses.append("{}:{}".format(host, port))
    install_args = indexed_values(nacos_instances[0].get("install_args"))
    values = {
        "CW_NACOS_SERVER": ",".join(addresses),
        "CW_NACOS_USERNAME": install_args.get("username", ""),
        # password_enc 原样落盘；明文只在 JVM 内解开。
        "CW_NACOS_PASSWORD": install_args.get("password_enc", ""),
        "CW_NACOS_NAMESPACE": install_args.get("namespace", ""),
    }
    missing = [key for key in NACOS_PARAM_KEYS if not values[key]]
    if missing:
        raise ValueError("Nacos 依赖缺少安装参数: {}".format(", ".join(missing)))
    return values


def get_parameters(args):
    records = load_records(args)
    instance = find_instance(records, SERVICE_NAME, args.local_ip)
    values = indexed_values(instance.get("install_args"))
    ports = indexed_values(instance.get("ports"))
    values["service_port"] = ports.get("service_port", "18098")
    values["base_dir"] = require_absolute(values.get("base_dir"), "base_dir")
    values["log_dir"] = require_absolute(values.get("log_dir"), "log_dir")
    values["run_user"] = values.get("run_user") or DEFAULT_RUN_USER
    values["memory"] = values.get("memory") or "512m"
    values.update(nacos_connection(instance, records))
    return instance, values


def shell_quote(value):
    value = str(value)
    if "\n" in value or "\r" in value or "\x00" in value:
        raise ValueError("environment value contains an invalid control character")
    return "'{}'".format(value.replace("'", "'\"'\"'"))


def write_runtime_env(base_dir, values):
    path = os.path.join(base_dir, "conf", "omp.env")
    entries = {
        "OMP_APP_HOME": base_dir,
        "OMP_LOG_DIR": values["log_dir"],
        "OMP_RUN_USER": values["run_user"],
        "OMP_SERVICE_PORT": values["service_port"],
        "OMP_JVM_HEAP_SIZE": values["memory"],
    }
    with open(path, "w") as target:
        for key in sorted(entries):
            target.write("{}={}\n".format(key, shell_quote(entries[key])))
    os.chmod(path, 0o600)
    return path


def write_nacos_params(base_dir, values):
    path = os.path.join(base_dir, "conf", NACOS_PARAMS_FILE)
    with open(path, "w") as target:
        for key in NACOS_PARAM_KEYS:
            target.write("{}={}\n".format(key, shell_quote(values[key])))
    os.chmod(path, 0o600)
    return path


def app_files(base_dir):
    lib_dir = os.path.join(base_dir, "lib")
    jars = [name for name in os.listdir(lib_dir)
            if name.startswith("hkex-cmdb-sync-server-") and name.endswith(".jar")]
    if len(jars) != 1:
        raise ValueError("expected exactly one CMDB server jar under {}".format(lib_dir))
    for path in (os.path.join(base_dir, "conf", "application.properties"),
                 os.path.join(base_dir, "bin", SERVICE_NAME)):
        if not os.path.isfile(path):
            raise ValueError("required package file is missing: {}".format(path))
    return os.path.join(lib_dir, jars[0])


def ensure_run_user(run_user):
    try:
        return pwd.getpwnam(run_user)
    except KeyError:
        pass
    if os.geteuid() != 0:
        raise ValueError("run_user does not exist and cannot be created without root: {}"
                         .format(run_user))
    subprocess.check_call(["useradd", "-m", "-s", "/bin/bash", run_user])
    return pwd.getpwnam(run_user)


def make_dirs(paths):
    for path in paths:
        if not os.path.isdir(path):
            os.makedirs(path)


def chown_tree(path, uid, gid):
    if not os.path.exists(path):
        return
    os.chown(path, uid, gid)
    for root, dirs, files in os.walk(path):
        for name in dirs + files:
            os.chown(os.path.join(root, name), uid, gid)


def chmod_executable(path):
    if os.path.isfile(path):
        os.chmod(path, 0o755)


def version_tuple(value):
    parts = []
    for chunk in re.split(r"[.\-_+]", str(value or "")):
        if chunk.isdigit():
            parts.append((0, int(chunk), ""))
        else:
            parts.append((1, 0, chunk))
    return tuple(parts)


def read_service_version(base_dir):
    path = os.path.join(base_dir, SERVICE_PROPERTIES)
    if not os.path.isfile(path):
        return None
    with open(path, "r") as source:
        for line in source:
            if line.startswith("service-version="):
                return line.split("=", 1)[1].strip() or None
    return None


def assert_not_downgrade(installed_version, package_version):
    if installed_version and package_version \
            and version_tuple(package_version) < version_tuple(installed_version):
        raise ValueError("refusing downgrade: package version {} is older than installed {}"
                         .format(package_version, installed_version))


def fail(message):
    sys.stderr.write("OMP lifecycle failure: {}\n".format(message))
    sys.exit(1)


def run_main(callback):
    try:
        callback(parse_args())
    except Exception as exception:
        fail(str(exception))
