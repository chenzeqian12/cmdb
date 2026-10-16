# -*- coding: utf-8 -*-
import json
import os
import sys
import tempfile
import unittest

try:
    from types import SimpleNamespace
except ImportError:
    class SimpleNamespace(object):
        def __init__(self, **kwargs):
            self.__dict__.update(kwargs)


PROJECT_DIR = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SCRIPTS_DIR = os.path.join(PROJECT_DIR, "hkex-cmdb-sync-server", "scripts")
sys.path.insert(0, SCRIPTS_DIR)

from omp_common import (assert_not_downgrade, get_parameters,
                        write_nacos_params, write_runtime_env)


NACOS_INSTANCE_NAME = "nacos-1"


def service_record(with_dependency=True, dependence=None):
    record = {
        "name": "hkexCmdbSync",
        "ip": "10.0.0.8",
        "ports": [{"key": "service_port", "default": 18098}],
        "install_args": [
            {"key": "base_dir", "default": "/data/app/hkexCmdbSync"},
            {"key": "log_dir", "default": "/data/logs/hkexCmdbSync"},
            {"key": "memory", "default": "768m"},
            {"key": "run_user", "default": "commonuser"},
        ],
    }
    if dependence is not None:
        record["dependence"] = [dependence]
    elif with_dependency:
        record["dependence"] = [{
            "name": "nacos", "instance_name": NACOS_INSTANCE_NAME, "cluster_name": None,
        }]
    return record


def nacos_record(ip="10.0.0.9", instance_name=NACOS_INSTANCE_NAME,
                 cluster_name=None, service_port=18117):
    return {
        "name": "nacos",
        "ip": ip,
        "instance_name": instance_name,
        "cluster_name": cluster_name,
        "ports": [{"key": "service_port", "default": service_port}],
        "install_args": [
            {"key": "username", "default": "nacos"},
            {"key": "password_enc", "default": "ciphertext-from-omp"},
            {"key": "namespace", "default": "PROD"},
        ],
    }


class OmpDataJsonTest(unittest.TestCase):

    def parameters(self, records):
        with tempfile.NamedTemporaryFile(mode="w", delete=False) as target:
            json.dump(records, target)
            path = target.name
        try:
            return get_parameters(SimpleNamespace(data_json=path, local_ip="10.0.0.8"))
        finally:
            os.unlink(path)

    def test_resolves_nacos_dependency_and_runtime_values(self):
        _, values = self.parameters([service_record(), nacos_record()])
        self.assertEqual("10.0.0.9:18117", values["CW_NACOS_SERVER"])
        self.assertEqual("nacos", values["CW_NACOS_USERNAME"])
        self.assertEqual("ciphertext-from-omp", values["CW_NACOS_PASSWORD"])
        self.assertEqual("PROD", values["CW_NACOS_NAMESPACE"])
        self.assertEqual("18098", str(values["service_port"]))
        self.assertEqual("768m", values["memory"])

    def test_missing_service_port_falls_back_to_package_default(self):
        # OMP 记录里没给 service_port 时用服务包声明的默认值，与 conf/hkexCmdbSync.yaml 保持一致。
        record = service_record()
        record["ports"] = []

        _, values = self.parameters([record, nacos_record()])

        self.assertEqual("18098", values["service_port"])

    def test_missing_nacos_dependency_fails_closed(self):
        with self.assertRaises(ValueError) as caught:
            self.parameters([service_record(False), nacos_record()])
        self.assertIn("nacos", str(caught.exception))

    def test_nacos_cluster_connection_includes_all_dependency_nodes(self):
        records = [
            service_record(dependence={"name": "nacos", "cluster_name": "nacos-cluster"}),
            nacos_record("10.0.0.1", "nacos-node-1", "nacos-cluster", 18117),
            nacos_record("10.0.0.2", "nacos-node-2", "nacos-cluster", 18118),
            nacos_record("10.0.0.3", "nacos-node-3", "nacos-cluster", 18119),
            nacos_record("10.0.1.1", "other-nacos-node", "other-cluster", 18120),
        ]

        _, values = self.parameters(records)

        self.assertEqual("10.0.0.1:18117,10.0.0.2:18118,10.0.0.3:18119",
                         values["CW_NACOS_SERVER"])
        self.assertEqual("nacos", values["CW_NACOS_USERNAME"])
        self.assertEqual("ciphertext-from-omp", values["CW_NACOS_PASSWORD"])
        self.assertEqual("PROD", values["CW_NACOS_NAMESPACE"])

    def test_nacos_instance_dependency_selects_only_named_node(self):
        records = [
            service_record(dependence={"name": "nacos", "instance_name": "nacos-node-2"}),
            nacos_record("10.0.0.1", "nacos-node-1", "nacos-cluster", 18117),
            nacos_record("10.0.0.2", "nacos-node-2", "nacos-cluster", 18118),
        ]

        _, values = self.parameters(records)

        self.assertEqual("10.0.0.2:18118", values["CW_NACOS_SERVER"])

    def test_nacos_dependency_rejects_missing_or_ambiguous_matches(self):
        dependencies = [
            # 未指定集群却有多实例：不猜，直接失败
            {"name": "nacos"},
            {"name": "nacos", "instance_name": "missing-node"},
            {"name": "nacos", "cluster_name": "missing-cluster"},
        ]
        for dependence in dependencies:
            with self.subTest(dependence=dependence):
                records = [
                    service_record(dependence=dependence),
                    nacos_record("10.0.0.1", "nacos-node-1", "nacos-cluster", 18117),
                    nacos_record("10.0.0.2", "nacos-node-2", "nacos-cluster", 18118),
                ]
                with self.assertRaisesRegex(ValueError, "nacos dependency instance"):
                    self.parameters(records)

    def test_nacos_cluster_rejects_node_without_address(self):
        for missing_field in ("ip", "ports"):
            with self.subTest(missing_field=missing_field):
                broken = nacos_record("10.0.0.2", "nacos-node-2", "nacos-cluster", 18118)
                broken[missing_field] = None if missing_field == "ip" else []
                records = [
                    service_record(dependence={"name": "nacos", "cluster_name": "nacos-cluster"}),
                    nacos_record("10.0.0.1", "nacos-node-1", "nacos-cluster", 18117),
                    broken,
                ]
                with self.assertRaisesRegex(ValueError, "CW_NACOS_SERVER"):
                    self.parameters(records)

    def test_generated_files_are_private_and_password_stays_encrypted(self):
        _, values = self.parameters([service_record(), nacos_record()])
        with tempfile.TemporaryDirectory() as base_dir:
            os.makedirs(os.path.join(base_dir, "conf"))
            env_path = write_runtime_env(base_dir, values)
            nacos_path = write_nacos_params(base_dir, values)
            self.assertEqual(0o600, os.stat(env_path).st_mode & 0o777)
            self.assertEqual(0o600, os.stat(nacos_path).st_mode & 0o777)
            with open(nacos_path) as source:
                content = source.read()
            self.assertIn("CW_NACOS_PASSWORD='ciphertext-from-omp'", content)

    def test_downgrade_is_rejected(self):
        with self.assertRaises(ValueError):
            assert_not_downgrade("1.0.5", "1.0.4")
        assert_not_downgrade("1.0.5", "1.0.5")
        assert_not_downgrade("1.0.5", "1.0.6")


if __name__ == "__main__":
    unittest.main()
