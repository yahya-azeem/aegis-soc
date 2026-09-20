SHELL := /bin/bash
SBT   ?= sbt
SBT_RUN = printf '$(1)\nexit\n' | $(SBT)

.PHONY: compile verilog verilog-vortex verilog-yosys test verilator raytrace chess transistors demo-build run-raytrace live demo-raytrace clean bsp idea help

compile:
	@$(call SBT_RUN,compile)

# Emit the Chisel SoC to build/rtl/Aegis.sv (with its split verification tree).
verilog:
	@$(call SBT_RUN,runMain aegis.elaborate.TopElaborate)

# Same SoC with the real Vortex RTL black-boxed on the acc port; the emitted SV
# is co-simulated out-of-tree with the vendored vortex/ RTL in test/vortex.
verilog-vortex:
	@$(call SBT_RUN,runMain aegis.elaborate.TopVortexElaborate)

# Yosys-friendly emission (no local vars / packed arrays) for gate-level analysis.
verilog-yosys:
	@$(call SBT_RUN,runMain aegis.elaborate.TopYosysElaborate)

# Full ScalaTest suite (ChiselSim unit/integration + emit tests).
# Verilator cannot build in a directory whose path contains spaces, so if the
# checkout lives in one we mirror it to a temp dir and run there.
test:
	@if [[ "$(CURDIR)" == *" "* ]]; then \
	  TMP="$$(mktemp -d)/aegis-soc"; mkdir -p "$$TMP"; \
	  echo "Path contains spaces (Verilator limitation) -> running in $$TMP"; \
	  cp -a "$(CURDIR)/." "$$TMP/"; rm -rf "$$TMP/target" "$$TMP/build" "$$TMP/.git"; \
	  ( cd "$$TMP" && $(SBT) test ); rc=$$?; rm -rf "$$(dirname "$$TMP")"; exit $$rc; \
	else \
	  $(call SBT_RUN,test); \
	fi

# Raw-Verilator smoke harness over the emitted Aegis.sv.
verilator: verilog
	$(MAKE) -C test -f Makefile.test test

# Render the host-side reference of the Vortex raytracer scene to a PNG.
# RT_OUT keeps the committed co-sim golden (rt_balls_golden.bin) untouched.
raytrace:
	RT_W=600 RT_H=600 RT_OUT=build/raytrace_golden.bin RT_PNG=docs/raytrace.png python3 test/vortex/golden.py

# Host-side high-resolution chess reference render (not the RTL kernel).
chess:
	CHESS_W=$${CHESS_W:-1000} CHESS_H=$${CHESS_H:-750} CHESS_SS=$${CHESS_SS:-2} python3 test/vortex/chess.py docs/raytrace_chess.png

# Transistor-level CMOS views: cell library figure, per-block counts, SPICE.
transistors:
	bash scripts/transistor_flow.sh

# One-command live demo: build once, then render the raytracer on the real RTL.
demo-build:
	bash scripts/build_demo.sh

# Run the pre-built binary only (no compilation) -- for live demos.
run-raytrace:
	bash scripts/run_raytrace.sh

# Live progressive render window + MP4, from the pre-built binary.
live:
	bash scripts/run_raytrace.sh --live --video --open

demo-raytrace:
	bash scripts/demo_raytrace.sh

clean:
	rm -rf build/ target/ project/target project/project out/

bsp:
	@$(call SBT_RUN,bspConfig)

idea:
	@$(call SBT_RUN,compile)

help:
	@echo "Targets:"
	@echo "  compile        - Compile Scala/Chisel sources"
	@echo "  verilog        - Elaborate Top and emit SystemVerilog to build/rtl/"
	@echo "  verilog-vortex - Elaborate Top with real Vortex RTL on the acc port"
	@echo "  verilog-yosys  - Elaborate a Yosys-friendly view for gate-level analysis"
	@echo "  test           - Run the sbt/ScalaTest suite"
	@echo "  verilator      - Run the raw-Verilator smoke harness"
	@echo "  raytrace       - Render the raytracer reference scene to docs/raytrace.png"
	@echo "  chess          - Host-side high-res chess reference render"
	@echo "  transistors    - Transistor-level CMOS views + counts + SPICE netlist"
	@echo "  demo-build     - One-time Verilator build of the co-simulation"
	@echo "  run-raytrace   - Run the pre-built raytracer binary (no compilation)"
	@echo "  live           - Live progressive render window + MP4 (pre-built)"
	@echo "  demo-raytrace  - Build if needed, then run the raytracer"
	@echo "  clean          - Remove build artifacts"
	@echo "  bsp            - Generate BSP config for IDEs"
