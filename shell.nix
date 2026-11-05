# Everything for `make`, `make program` and `make test`: sbt (SpinalHDL ->
# Verilog), the openXC7 toolchain (yosys, nextpnr-xilinx, prjxray,
# openFPGALoader) with the Zynq-7 chip database, and Python with pyftdi.
let
  pkgs = import <nixpkgs> { };
  flake = builtins.getFlake "github:openXC7/toolchain-nix/22327ebfb31d5fc93fd3b366c05657cb4d0d024d";
  sys = builtins.currentSystem;
  chipdb = flake.packages.${sys}.nextpnr-xilinx-chipdb.zynq7;
in
(flake.devShell.${sys}).overrideAttrs (prev: {
  buildInputs = [ chipdb pkgs.sbt (pkgs.python3.withPackages (ps: [ ps.pyftdi ])) ]
    ++ (prev.buildInputs or [ ]);
  shellHook = (prev.shellHook or "") + ''
    export ZYNQ7_CHIPDB=${chipdb}
  '';
})
