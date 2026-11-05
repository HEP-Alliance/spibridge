# spitbridge

SPI → AXI → APB bridge on a Digilent Arty Z7-20 (PL only, open-source
openXC7 flow), driven from a PC through an FT232H.

```
FT232H ─SPI─► FrameDecoder ─► AxiBridgeManager ─AXI─► Axi4SharedToApb3Bridge ─APB─┬─► RAM   0x00000, 4 KiB
       ◄──── FrameEncoder ◄──┘                                                     ├─► regs  0x10000: ID, SCRATCH, LEDS
                                                                                   └─► else: SLVERR
```

Frames are 84 bits: 20-bit address, 32-bit data, CRC-32. The command is
encoded in the CRC residual, so CS is ignored. See `python/spitest.py`.

## Wiring

Pmod JA, top row; pin 1 is the square pad. Connect GND, but no power pin.

| JA | 1 | 2 | 3 | 4 | 5 |
|---|---|---|---|---|---|
| signal | SCLK | MOSI | MISO | CS | GND |
| FT232H | D0 | D1 | D2 | D3 | GND |

## Run

```sh
nix-shell         # first run builds the toolchain and chip database (~20 min)
make              # Verilog + bitstream in build/
make program      # load over JTAG
make test         # FREQ=15e6 for the maximum
```

LD4 blinks while the design runs, LD5 flashes on every SPI command, and
LD0–LD3 show the LEDS register. BTN0 resets.

The FT232H needs a udev rule for USB ID `0403:6014`, e.g. on NixOS:

```nix
services.udev.extraRules = ''
  SUBSYSTEM=="usb", ATTRS{idVendor}=="0403", ATTRS{idProduct}=="6014", MODE="0666"
'';
```

## Limits

The FPGA samples SCLK with its 125 MHz clock, so SCLK must stay below
clk/4. The FT232H makes 60 MHz / 2n: 15 MHz works and 30 MHz does not.

## Origin

`Framer.scala` and `AxiBridge.scala` come from cavaboris. `AxiBridge.scala`
was changed to return the AXI resp code in the response frame.
`Axi4SharedToApb3BridgeFixed.scala` is SpinalHDL's `Axi4SharedToApb3Bridge`
(MIT) with `PSLVERROR` latched; upstream reports every slave error as OKAY.

## License

MIT, see `LICENSE`. `Axi4SharedToApb3BridgeFixed.scala` keeps SpinalHDL's
own MIT notice.
