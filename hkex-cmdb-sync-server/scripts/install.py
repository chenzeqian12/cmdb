#!/usr/bin/env python
# -*- coding: utf-8 -*-
import os

from omp_common import (app_files, chmod_executable, chown_tree, ensure_run_user,
                        get_parameters, make_dirs, run_main, write_nacos_params,
                        write_runtime_env)


def install(args):
    instance, values = get_parameters(args)
    base_dir = values["base_dir"]
    app_files(base_dir)
    account = ensure_run_user(values["run_user"])
    state_dirs = [values["log_dir"], os.path.join(base_dir, "pid")]
    make_dirs(state_dirs)
    chmod_executable(os.path.join(base_dir, "bin", "hkexCmdbSync"))
    chmod_executable(os.path.join(base_dir, "bin", "publishCmdbConfig"))
    env_path = write_runtime_env(base_dir, values)
    nacos_path = write_nacos_params(base_dir, values)
    if os.geteuid() == 0:
        chown_tree(base_dir, account.pw_uid, account.pw_gid)
        for path in state_dirs:
            chown_tree(path, account.pw_uid, account.pw_gid)
        os.chown(env_path, account.pw_uid, account.pw_gid)
        os.chown(nacos_path, account.pw_uid, account.pw_gid)
    elif os.geteuid() != account.pw_uid:
        raise ValueError("run install.py as root or as the configured run_user")
    print("hkexCmdbSync install prepared at {} for {}".format(
        base_dir, instance.get("ip")))


if __name__ == "__main__":
    run_main(install)
