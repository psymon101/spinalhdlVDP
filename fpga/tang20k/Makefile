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

# ---------------------------------------------------------------------------
# 720p output-shell proof builds (proof / bridge / mode0 / linebuf / planar)
# were archived 2026-07-30 (lane 720p-proof-build-script-cleanup). Their build
# scripts + CST/SDC now live under archive/720p_proofs/; they are superseded by
# the native 640x480 production build (above) and the `diagnostic` target
# (below). The Scala generators (spinalhdlvdp.Hdmi720p*ProofTopVerilog) remain
# in hw/spinal/spinalhdlvdp/ and can still be generated manually if needed.
# ---------------------------------------------------------------------------

# ---------------------------------------------------------------------------
# standalone-diagnostic-build lane — no-host / no-QSPI / no-SDRAM native 640x480
# 1x test-pattern build. Separate top (top_tang20k_diagnostic) and impl dir.
# Reuses the production tang20k_hdmi.cst / .sdc (same native 640x480 pinout).
# NOTE: does NOT `rm -rf impl` (unlike the archived 720p proof builds) so
# preserved authority bitstreams under impl/pnr/project_*.fs are not deleted.
# ---------------------------------------------------------------------------
DIAG_BUILD_DIR := impl_diagnostic
DIAG_TCL       := build_diagnostic.tcl
DIAG_BITSTREAM := $(DIAG_BUILD_DIR)/pnr/project.fs
DIAG_GEN       := spinalhdlvdp.TopTang20kHdmiDiagnosticVerilog

.PHONY: gen-diagnostic diagnostic prog-diagnostic flash-diagnostic clean-diagnostic

gen-diagnostic:
	$(SBT) "runMain $(DIAG_GEN)"

$(DIAG_BITSTREAM): gen-diagnostic $(DIAG_TCL) tang20k_hdmi.cst tang20k_hdmi.sdc
	$(GW_ENV) $(GW_SH) $(DIAG_TCL)
	mkdir -p $(DIAG_BUILD_DIR)/pnr
	cp $(BUILD_DIR)/pnr/project.fs $(DIAG_BITSTREAM)
	cp $(BUILD_DIR)/pnr/project.rpt.txt $(DIAG_BUILD_DIR)/pnr/ 2>/dev/null || true
	cp $(BUILD_DIR)/pnr/project_tr_content.html $(DIAG_BUILD_DIR)/pnr/ 2>/dev/null || true

diagnostic: $(DIAG_BITSTREAM)

prog-diagnostic: $(DIAG_BITSTREAM)
	$(LOADER) --board $(BOARD) $(LOADER_ARGS) --bitstream $(DIAG_BITSTREAM)

flash-diagnostic: $(DIAG_BITSTREAM)
	$(LOADER) --board $(BOARD) $(LOADER_ARGS) -f --verify $(DIAG_BITSTREAM)

clean-diagnostic:
	rm -rf $(DIAG_BUILD_DIR)
