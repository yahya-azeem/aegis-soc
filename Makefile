SHELL := /bin/bash
SBT   ?= sbt
SBT_RUN = printf '$(1)\nexit\n' | $(SBT)

.PHONY: init verilog test clean clean-all bsp idea help compile

init:
	git submodule update --init --recursive

compile:
	@$(call SBT_RUN,compile)

verilog:
	@$(call SBT_RUN,runMain aegis.elaborate.TopElaborate)

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
	@echo "  test       - Run sbt tests"
	@echo "  clean      - Remove build artifacts"
	@echo "  clean-all  - Remove build artifacts and submodule caches"
	@echo "  bsp        - Generate BSP config for IDEs"
	@echo "  idea       - Compile (IntelliJ-friendly)"