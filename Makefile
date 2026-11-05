# Arty Z7-20 (xc7z020clg400-1), PL only. Run inside nix-shell.
#   make            Verilog + bitstream
#   make program    load it over JTAG
#   make test       run python/spitest.py through the FT232H
PART  = xc7z020clg400-1
TOP   = ArtyTop
B     = build
DB    = $(PRJXRAY_DB_DIR)/zynq7
FREQ ?= 10e6

all: $(B)/$(TOP).bit

$(B)/$(TOP).v: build.sbt $(shell find src -name '*.scala')
	sbt "runMain top.ArtyTopVerilog $(B)"

$(B)/$(TOP).json: $(B)/$(TOP).v
	yosys -q -l $(B)/yosys.log -p "read_verilog $<; synth_xilinx -flatten -abc9 -arch xc7 -top $(TOP); write_json $@"

$(B)/$(TOP).fasm: $(B)/$(TOP).json arty_z7_20.xdc
	nextpnr-xilinx --chipdb $(ZYNQ7_CHIPDB)/xc7z020clg400.bin --xdc arty_z7_20.xdc --json $< --fasm $@ \
	  > $(B)/nextpnr.log 2>&1 || (tail -30 $(B)/nextpnr.log; false)
	@grep "Max frequency" $(B)/nextpnr.log | tail -1

$(B)/$(TOP).bit: $(B)/$(TOP).fasm
	fasm2frames --part $(PART) --db-root $(DB) $< $(B)/$(TOP).frames
	xc7frames2bit --part_file $(DB)/$(PART)/part.yaml --part_name $(PART) \
	  --frm_file $(B)/$(TOP).frames --output_file $@

program: $(B)/$(TOP).bit
	openFPGALoader --board arty_z7_20 --bitstream $<

test:
	python3 python/spitest.py --freq $(FREQ)

clean:
	rm -rf $(B)

.PHONY: all program test clean
.DELETE_ON_ERROR:
