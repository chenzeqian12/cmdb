#!/usr/bin/env python
# -*- coding: utf-8 -*-
import os
import subprocess

from omp_common import (app_files, assert_not_downgrade, get_parameters,
                        read_service_version, run_main, write_nacos_params,
                        write_runtime_env)


def upgrade(args):
    instance, values = get_parameters(args)
    base_dir = values["base_dir"]
    app_files(base_dir)
    if not args.version or not args.backup_path or not os.path.isdir(args.backup_path):
        raise ValueError("upgrade requires the previous --version and an existing --backup_path")
    assert_not_downgrade(args.version, read_service_version(base_dir))
    subprocess.check_call([os.path.join(base_dir, "bin", "hkexCmdbSync"), "stop"])
    write_runtime_env(base_dir, values)
    write_nacos_params(base_dir, values)
    print("hkexCmdbSync {} upgraded for {}".format(args.version, instance.get("ip")))


if __name__ == "__main__":
    run_main(upgrade)
