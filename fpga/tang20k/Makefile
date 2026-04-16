TOP       := top_tang20k
BOARD     := tangnano20k
BUILD_DIR := impl
TCL_FILE  := build.tcl
BITSTREAM := $(BUILD_DIR)/pnr/project.fs
REPO_ROOT := ../..
SBT       := cd $(REPO_ROOT) && sbt
LOADER    := openFPGALoader
LOADER_ARGS ?=

ifdef GOWIN_HOME
GW_SH := $(GOWIN_HOME)/IDE/bin/gw_sh
else
GW_SH := gw_sh
endif
GW_ENV ?= QT_QPA_PLATFORM=minimal QT_OPENGL=software LIBGL_ALWAYS_SOFTWARE=1

.PHONY: all gen prog flash clean

all: $(BITSTREAM)

SCENARIO ?=
ifeq ($(SCENARIO),)
GEN_TARGET := spinalhdlvdp.TopTang20kHdmiVerilog
else
GEN_TARGET := spinalhdlvdp.TopTang20kHdmiScenario$(SCENARIO)Verilog
endif

gen:
	$(SBT) "runMain $(GEN_TARGET)"

$(BITSTREAM): gen $(TCL_FILE) tang20k_hdmi.cst tang20k_hdmi.sdc
	$(GW_ENV) $(GW_SH) $(TCL_FILE)

prog: $(BITSTREAM)
	$(LOADER) --board $(BOARD) $(LOADER_ARGS) --bitstream $(BITSTREAM)

flash: $(BITSTREAM)
	$(LOADER) --board $(BOARD) $(LOADER_ARGS) -f --verify $(BITSTREAM)

clean:
	rm -rf $(BUILD_DIR) ../../simWorkspace ../../target ../../project/target
