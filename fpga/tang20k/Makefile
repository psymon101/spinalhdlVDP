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
# BronzeGate #8482 Slice B — 720p output-shell proof (synthetic colour bars).
# Separate top, separate CST/SDC, separate impl directory so it cannot
# disturb the main HDMI build artefacts.
# ---------------------------------------------------------------------------
PROOF_BUILD_DIR := impl_720p_proof
PROOF_TCL       := build_hdmi720p_proof.tcl
PROOF_BITSTREAM := $(PROOF_BUILD_DIR)/pnr/project.fs
PROOF_GEN       := spinalhdlvdp.Hdmi720pProofTopVerilog

.PHONY: gen-720p-proof prog-720p-proof flash-720p-proof clean-720p-proof

gen-720p-proof:
	$(SBT) "runMain $(PROOF_GEN)"

$(PROOF_BITSTREAM): gen-720p-proof $(PROOF_TCL) tang20k_hdmi720p_proof.cst tang20k_hdmi720p_proof.sdc
	rm -rf $(BUILD_DIR) $(PROOF_BUILD_DIR)
	$(GW_ENV) $(GW_SH) $(PROOF_TCL)
	mv $(BUILD_DIR) $(PROOF_BUILD_DIR)

720p-proof: $(PROOF_BITSTREAM)

prog-720p-proof: $(PROOF_BITSTREAM)
	$(LOADER) --board $(BOARD) $(LOADER_ARGS) --bitstream $(PROOF_BITSTREAM)

flash-720p-proof: $(PROOF_BITSTREAM)
	$(LOADER) --board $(BOARD) $(LOADER_ARGS) -f --verify $(PROOF_BITSTREAM)

clean-720p-proof:
	rm -rf $(PROOF_BUILD_DIR)
