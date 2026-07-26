SHELL := /bin/bash
MILL  ?= mill

.PHONY: init init-submodules verilog clean clean-all test help

init:
	git submodule update --init --recursive

init-submodules:
	git submodule update --init --recursive

mill:
	curl -L https://github.com/com-lihaoyi/mill/releases/download/0.12.11/0.12.11 -o mill
	chmod +x mill

verilog: mill
	$(MILL) aegis.runMain aegis.Top

clean:
	rm -rf build/ out/

clean-all: clean
	rm -rf XiangShan/ vortex/

test:
	$(MILL) aegis.test

bsp:
	$(MILL) mill.bsp.BSP/install

idea:
	$(MILL) mill.idea.GenIdea/idea

help:
	@echo "Targets:"
	@echo "  init           - Initialize git submodules"
	@echo "  mill           - Download Mill build tool"
	@echo "  verilog        - Generate Verilog for Aegis SoC"
	@echo "  clean          - Remove build artifacts"
	@echo "  clean-all      - Remove build artifacts and submodules"
	@echo "  test           - Run tests"
	@echo "  bsp            - Generate BSP config for IDEs"
	@echo "  idea           - Generate IntelliJ IDEA project"
