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

# ---------------------------------------------------------------------------
# BronzeGate #8486 Slice C — 720p centered-640×480 bridge proof.
# ---------------------------------------------------------------------------
BRIDGE_BUILD_DIR := impl_720p_bridge
BRIDGE_TCL       := build_hdmi720p_bridge.tcl
BRIDGE_BITSTREAM := $(BRIDGE_BUILD_DIR)/pnr/project.fs
BRIDGE_GEN       := spinalhdlvdp.Hdmi720pBridgeProofTopVerilog

.PHONY: gen-720p-bridge prog-720p-bridge flash-720p-bridge clean-720p-bridge 720p-bridge

gen-720p-bridge:
	$(SBT) "runMain $(BRIDGE_GEN)"

$(BRIDGE_BITSTREAM): gen-720p-bridge $(BRIDGE_TCL) tang20k_hdmi720p_bridge.cst tang20k_hdmi720p_bridge.sdc
	rm -rf $(BUILD_DIR) $(BRIDGE_BUILD_DIR)
	$(GW_ENV) $(GW_SH) $(BRIDGE_TCL)
	mv $(BUILD_DIR) $(BRIDGE_BUILD_DIR)

720p-bridge: $(BRIDGE_BITSTREAM)

prog-720p-bridge: $(BRIDGE_BITSTREAM)
	$(LOADER) --board $(BOARD) $(LOADER_ARGS) --bitstream $(BRIDGE_BITSTREAM)

flash-720p-bridge: $(BRIDGE_BITSTREAM)
	$(LOADER) --board $(BOARD) $(LOADER_ARGS) -f --verify $(BRIDGE_BITSTREAM)

clean-720p-bridge:
	rm -rf $(BRIDGE_BUILD_DIR)

# ---------------------------------------------------------------------------
# BronzeGate #8496 Slice D-A — VdpTop test-pattern under 720p shell.
# ---------------------------------------------------------------------------
MODE0_BUILD_DIR := impl_720p_mode0
MODE0_TCL       := build_hdmi720p_mode0.tcl
MODE0_BITSTREAM := $(MODE0_BUILD_DIR)/pnr/project.fs
MODE0_GEN       := spinalhdlvdp.Hdmi720pMode0ProofTopVerilog

.PHONY: gen-720p-mode0 prog-720p-mode0 flash-720p-mode0 clean-720p-mode0 720p-mode0

gen-720p-mode0:
	$(SBT) "runMain $(MODE0_GEN)"

$(MODE0_BITSTREAM): gen-720p-mode0 $(MODE0_TCL) tang20k_hdmi720p_mode0.cst tang20k_hdmi720p_mode0.sdc
	rm -rf $(BUILD_DIR) $(MODE0_BUILD_DIR)
	$(GW_ENV) $(GW_SH) $(MODE0_TCL)
	mv $(BUILD_DIR) $(MODE0_BUILD_DIR)

720p-mode0: $(MODE0_BITSTREAM)

prog-720p-mode0: $(MODE0_BITSTREAM)
	$(LOADER) --board $(BOARD) $(LOADER_ARGS) --bitstream $(MODE0_BITSTREAM)

flash-720p-mode0: $(MODE0_BITSTREAM)
	$(LOADER) --board $(BOARD) $(LOADER_ARGS) -f --verify $(MODE0_BITSTREAM)

clean-720p-mode0:
	rm -rf $(MODE0_BUILD_DIR)

# ---------------------------------------------------------------------------
# BronzeGate #8505 Slice D-B1-L — dual-clock line-buffer CDC proof.
# ---------------------------------------------------------------------------
LINEBUF_BUILD_DIR := impl_720p_linebuf
LINEBUF_TCL       := build_hdmi720p_linebuf.tcl
LINEBUF_BITSTREAM := $(LINEBUF_BUILD_DIR)/pnr/project.fs
LINEBUF_GEN       := spinalhdlvdp.Hdmi720pLineBufferProofTopVerilog

.PHONY: gen-720p-linebuf prog-720p-linebuf flash-720p-linebuf clean-720p-linebuf 720p-linebuf

gen-720p-linebuf:
	$(SBT) "runMain $(LINEBUF_GEN)"

$(LINEBUF_BITSTREAM): gen-720p-linebuf $(LINEBUF_TCL) tang20k_hdmi720p_linebuf.cst tang20k_hdmi720p_linebuf.sdc
	rm -rf $(BUILD_DIR) $(LINEBUF_BUILD_DIR)
	$(GW_ENV) $(GW_SH) $(LINEBUF_TCL)
	mv $(BUILD_DIR) $(LINEBUF_BUILD_DIR)

720p-linebuf: $(LINEBUF_BITSTREAM)

prog-720p-linebuf: $(LINEBUF_BITSTREAM)
	$(LOADER) --board $(BOARD) $(LOADER_ARGS) --bitstream $(LINEBUF_BITSTREAM)

flash-720p-linebuf: $(LINEBUF_BITSTREAM)
	$(LOADER) --board $(BOARD) $(LOADER_ARGS) -f --verify $(LINEBUF_BITSTREAM)

clean-720p-linebuf:
	rm -rf $(LINEBUF_BUILD_DIR)

# ---------------------------------------------------------------------------
# Mode0 Fetch Envelope Hardening — planar fetch primitives HW proof.
# ---------------------------------------------------------------------------
PLANAR_BUILD_DIR := impl_720p_planar
PLANAR_TCL       := build_hdmi720p_planar.tcl
PLANAR_BITSTREAM := $(PLANAR_BUILD_DIR)/pnr/project.fs
PLANAR_GEN       := spinalhdlvdp.Hdmi720pPlanarProofTopVerilog

.PHONY: gen-720p-planar prog-720p-planar flash-720p-planar clean-720p-planar 720p-planar

gen-720p-planar:
	$(SBT) "runMain $(PLANAR_GEN)"

$(PLANAR_BITSTREAM): gen-720p-planar $(PLANAR_TCL) tang20k_hdmi720p_planar.cst tang20k_hdmi720p_planar.sdc
	rm -rf $(BUILD_DIR) $(PLANAR_BUILD_DIR)
	$(GW_ENV) $(GW_SH) $(PLANAR_TCL)
	mv $(BUILD_DIR) $(PLANAR_BUILD_DIR)

720p-planar: $(PLANAR_BITSTREAM)

prog-720p-planar: $(PLANAR_BITSTREAM)
	$(LOADER) --board $(BOARD) $(LOADER_ARGS) --bitstream $(PLANAR_BITSTREAM)

flash-720p-planar: $(PLANAR_BITSTREAM)
	$(LOADER) --board $(BOARD) $(LOADER_ARGS) -f --verify $(PLANAR_BITSTREAM)

clean-720p-planar:
	rm -rf $(PLANAR_BUILD_DIR)

# ---------------------------------------------------------------------------
# standalone-diagnostic-build lane — no-host / no-QSPI / no-SDRAM native 640x480
# 1x test-pattern build. Separate top (top_tang20k_diagnostic) and impl dir.
# Reuses the production tang20k_hdmi.cst / .sdc (same native 640x480 pinout).
# NOTE: does NOT `rm -rf impl` (unlike the 720p-proof target) so preserved
# authority bitstreams under impl/pnr/project_*.fs are not deleted.
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
