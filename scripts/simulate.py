#!/usr/bin/env python3
"""Aegis SoC simulation helper.

Usage:
  python3 scripts/simulate.py --verilog             # elaborate to build/rtl/
  python3 scripts/simulate.py --simulate            # raw-Verilator smoke harness
  python3 scripts/simulate.py --verilog --simulate
"""

import os
import subprocess
import argparse

PROJECT_ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))


def run_cmd(cmd, cwd=None):
    print(f"+ {' '.join(cmd)}")
    subprocess.check_call(cmd, cwd=cwd or PROJECT_ROOT)


def build_verilog():
    print("=== Elaborating Aegis to SystemVerilog ===")
    run_cmd(["sbt", "runMain", "aegis.elaborate.TopElaborate"])


def run_simulation():
    print("=== Raw-Verilator smoke harness ===")
    run_cmd(["make", "-C", "test", "-f", "Makefile.test", "test"])


def main():
    parser = argparse.ArgumentParser(description="Aegis SoC simulation")
    parser.add_argument("--verilog", action="store_true", help="Elaborate to build/rtl/")
    parser.add_argument("--simulate", action="store_true", help="Run raw-Verilator smoke harness")
    args = parser.parse_args()

    if args.verilog:
        build_verilog()
    if args.simulate:
        run_simulation()
    if not args.verilog and not args.simulate:
        parser.print_help()


if __name__ == "__main__":
    main()
