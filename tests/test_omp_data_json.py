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


def service_record(with_dependency=True):
    record = {
        "name": "hkexCmdbSync",
        "ip": "10.0.0.8",
        "ports": [{"key": "service_port", "default": 18081}],
        "install_args": [
            {"key": "base_dir", "default": "/data/app/hkexCmdbSync"},
            {"key": "log_dir", "default": "/data/logs/hkexCmdbSync"},
            {"key": "memory", "default": "768m"},
            {"key": "run_user", "default": "commonuser"},
        ],
    }
    if with_dependency:
        record["dependence"] = [{
            "name": "nacos", "instance_name": "nacos-1", "cluster_name": None,
        }]
    return record


def nacos_record():
    return {
        "name": "nacos",
        "ip": "10.0.0.9",
        "instance_name": "nacos-1",
        "ports": [{"key": "service_port", "default": 18117}],
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
        self.assertEqual("18081", str(values["service_port"]))
        self.assertEqual("768m", values["memory"])

    def test_missing_nacos_dependency_fails_closed(self):
        with self.assertRaises(ValueError) as caught:
            self.parameters([service_record(False), nacos_record()])
        self.assertIn("nacos", str(caught.exception))

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
