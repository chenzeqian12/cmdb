#!/usr/bin/env python
# -*- coding: utf-8 -*-
import os
import subprocess
import time

from omp_common import (app_files, get_parameters, run_main, write_nacos_params,
                        write_runtime_env)


def rollback(args):
    instance, values = get_parameters(args)
    base_dir = values["base_dir"]
    if not args.version or not args.backup_path or not os.path.isdir(args.backup_path):
        raise ValueError("rollback requires --version and an existing --backup_path")
    backup_path = os.path.realpath(args.backup_path)
    app_files(backup_path)
    if os.path.realpath(base_dir) == backup_path:
        raise ValueError("backup_path must differ from base_dir")
    current_dir = base_dir + ".omp-current-{}".format(int(time.time()))
    control = os.path.join(base_dir, "bin", "hkexCmdbSync")
    if os.path.isfile(control):
        subprocess.check_call([control, "stop"])
    os.rename(base_dir, current_dir)
    try:
        os.rename(backup_path, base_dir)
    except Exception:
        os.rename(current_dir, base_dir)
        raise
    write_runtime_env(base_dir, values)
    write_nacos_params(base_dir, values)
    print("hkexCmdbSync rolled back to {} for {}; previous files retained at {}".format(
        args.version, instance.get("ip"), current_dir))


if __name__ == "__main__":
    run_main(rollback)
