#!/usr/bin/env python3
"""Aegis SoC Simulation Script

Usage:
  python scripts/simulate.py [--verilog] [--build] [--simulate]
"""

import os
import sys
import subprocess
import argparse

PROJECT_ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
BUILD_DIR = os.path.join(PROJECT_ROOT, "build")
RTL_DIR = os.path.join(BUILD_DIR, "rtl")

def run_cmd(cmd, cwd=None):
    print(f"+ {' '.join(cmd)}")
    subprocess.check_call(cmd, cwd=cwd or PROJECT_ROOT)

def build_verilog():
    print("=== Generating Verilog ===")
    os.makedirs(RTL_DIR, exist_ok=True)
    run_cmd(["mill", "aegis.runMain", "aegis.Top"])

def run_simulation():
    print("=== Running Simulation ===")
    sv_files = [os.path.join(RTL_DIR, f) for f in os.listdir(RTL_DIR) if f.endswith(".sv")]
    tb_file = os.path.join(PROJECT_ROOT, "test", "test_bench.cpp")
    
    cmd = ["verilator", "--cc", "--top-module", "Top",
           "--Mdir", os.path.join(BUILD_DIR, "obj_dir")]
    cmd.extend(sv_files)
    cmd.extend(["--exe", tb_file])
    run_cmd(cmd)
    run_cmd(["make", "-C", os.path.join(BUILD_DIR, "obj_dir"), "-f", "VTop.mk"])
    run_cmd([os.path.join(BUILD_DIR, "obj_dir", "VTop")])

def main():
    parser = argparse.ArgumentParser(description="Aegis SoC Simulation")
    parser.add_argument("--verilog", action="store_true", help="Generate Verilog")
    parser.add_argument("--simulate", action="store_true", help="Run simulation")
    args = parser.parse_args()

    if args.verilog:
        build_verilog()
    if args.simulate:
        run_simulation()
    if not args.verilog and not args.simulate:
        parser.print_help()

if __name__ == "__main__":
    main()
