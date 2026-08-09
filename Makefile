SHELL := /bin/bash
SBT   ?= sbt
SBT_RUN = printf '$(1)\nexit\n' | $(SBT)

.PHONY: init verilog verilog-vortex test clean clean-all bsp idea help compile

init:
	git submodule update --init --recursive

compile:
	@$(call SBT_RUN,compile)

verilog:
	@$(call SBT_RUN,runMain aegis.elaborate.TopElaborate)

# Elaborate the same SoC with the real Vortex RTL black-boxed on the acc port.
# The emitted SV is co-simulated with the out-of-tree vortex RTL in test/vortex.
verilog-vortex:
	@$(call SBT_RUN,runMain aegis.elaborate.TopVortexElaborate)

test:
	@$(call SBT_RUN,test)

clean:
	rm -rf build/ target/ project/target project/project

clean-all: clean
	rm -rf XiangShan/ vortex/ ~/.ivy2/cache/org.chipsalliance

bsp:
	@$(call SBT_RUN,bspConfig)

idea:
	@$(call SBT_RUN,compile)

help:
	@echo "Targets:"
	@echo "  init       - Initialize git submodules"
	@echo "  compile    - Compile Scala/Chisel sources"
	@echo "  verilog    - Elaborate Top and emit SystemVerilog to build/rtl/"
	@echo "  verilog-vortex - Elaborate Top with real Vortex RTL on acc port"
	@echo "  test       - Run sbt tests"
	@echo "  clean      - Remove build artifacts"
	@echo "  clean-all  - Remove build artifacts and submodule caches"
	@echo "  bsp        - Generate BSP config for IDEs"
	@echo "  idea       - Compile (IntelliJ-friendly)"