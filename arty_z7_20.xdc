# Arty Z7-20, PL only. Port names match top.ArtyTop (noIoPrefix).
# Pins from Digilent's Arty-Z7-20-Master.xdc.

# 125 MHz system clock
set_property -dict {PACKAGE_PIN H16 IOSTANDARD LVCMOS33} [get_ports clk]

# BTN0, active high: held = reset
set_property -dict {PACKAGE_PIN D19 IOSTANDARD LVCMOS33} [get_ports btn0]

# SPI from the FT232H on Pmod JA, top row (pin 1 = square pad)
#   JA1 sclk <- FT232H D0    JA2 mosi <- FT232H D1
#   JA3 miso -> FT232H D2    JA4 csn  <- FT232H D3    JA5 GND -- FT232H GND
set_property -dict {PACKAGE_PIN Y18 IOSTANDARD LVCMOS33} [get_ports spi_sclk]
set_property -dict {PACKAGE_PIN Y19 IOSTANDARD LVCMOS33} [get_ports spi_mosi]
set_property -dict {PACKAGE_PIN Y16 IOSTANDARD LVCMOS33} [get_ports spi_miso]
set_property -dict {PACKAGE_PIN Y17 IOSTANDARD LVCMOS33} [get_ports spi_csn]

# LD0..LD3 = APB LEDS register
set_property -dict {PACKAGE_PIN R14 IOSTANDARD LVCMOS33} [get_ports {led[0]}]
set_property -dict {PACKAGE_PIN P14 IOSTANDARD LVCMOS33} [get_ports {led[1]}]
set_property -dict {PACKAGE_PIN N16 IOSTANDARD LVCMOS33} [get_ports {led[2]}]
set_property -dict {PACKAGE_PIN M14 IOSTANDARD LVCMOS33} [get_ports {led[3]}]

# LD4 blue = heartbeat, LD5 green = SPI command decoded
set_property -dict {PACKAGE_PIN L15 IOSTANDARD LVCMOS33} [get_ports led4_b]
set_property -dict {PACKAGE_PIN L14 IOSTANDARD LVCMOS33} [get_ports led5_g]
