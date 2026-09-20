#!/usr/bin/env python3
"""Add port directions to nmos/pmos cells in a Yosys JSON netlist.

Yosys omits `port_directions` for the bidirectional transistor primitives, so
netlistsvg cannot tell drain from gate. This annotates every nmos/pmos cell as
    $1 = drain  (output)
    $2 = source (input)
    $3 = gate   (input)
so netlistsvg wires the transistor-level schematic correctly.

Usage: python3 scripts/cmos_json.py in.json [out.json]
"""

import json
import sys


def annotate(path, out=None):
    out = out or path
    d = json.load(open(path))
    n = 0
    for mod in d.get("modules", {}).values():
        for cell in mod.get("cells", {}).values():
            if cell.get("type") in ("nmos", "pmos"):
                cell["port_directions"] = {"$1": "output", "$2": "input", "$3": "input"}
                n += 1
    json.dump(d, open(out, "w"))
    return n


def main():
    if len(sys.argv) < 2:
        print(__doc__)
        sys.exit(1)
    src = sys.argv[1]
    out = sys.argv[2] if len(sys.argv) > 2 else src
    print(f"annotated {annotate(src, out)} transistor cells -> {out}")


if __name__ == "__main__":
    main()
