"""Talk to the SPI -> AXI -> APB bridge on the Arty through an FT232H and
check that it works.

Wire format (must match src/main/scala/top/SpiAxiTop.scala): 84-bit frames,
20-bit address ++ 32-bit data ++ 32-bit CRC (poly 0x104C11DB7), MSB first.
The command is not a field. It is the CRC residual (tag) the frame's check
bits were chosen to produce, and the FPGA finds frames by sliding a CRC over
the bit stream. So neither CS nor byte alignment matters, and idle zero bits
between frames are harmless.

A response frame has the same layout: the AXI resp code (0 = OKAY,
2 = SLVERR) in the low bits of the address half, and read data in the data
half.
"""
import argparse
import random
import sys
import time

POLY, DEG = 0x104C11DB7, 32
ADDR_BITS, DATA_BITS = 20, 32
PAYLOAD_BITS = ADDR_BITS + DATA_BITS
FRAME_BITS = PAYLOAD_BITS + DEG
TAG_WRITE, TAG_READ, TAG_RESP = 0xA5A5A5A5, 0x5A5A5A5A, 0x0F0F0F0F
OKAY, SLVERR = 0, 2

RAM_BASE, RAM_BYTES = 0x00000, 4096
INFO_ID, INFO_SCRATCH, INFO_LEDS = 0x10000, 0x10004, 0x10008
ID_VALUE = 0x5B1A9B01  # periph.Apb3InfoRegs.id


# ---- CRC framing ----

def _step(reg, bit):
    reg = (reg << 1) | bit
    return reg ^ POLY if reg >> DEG else reg


def _x_pow(e):
    """x^e mod POLY."""
    reg = 1
    for _ in range(e):
        reg = _step(reg, 0)
    return reg


SHIFT_OUT = _x_pow(FRAME_BITS)  # cancels the bit leaving the scan window


def to_bits(value, width):
    return [(value >> (width - 1 - i)) & 1 for i in range(width)]


def encode(tag, addr, data):
    payload = to_bits((addr << DATA_BITS) | data, PAYLOAD_BITS)
    reg = 0
    for b in payload + [0] * DEG:
        reg = _step(reg, b)
    return payload + to_bits(reg ^ tag, DEG)


def find_frame(stream, tag):
    """First FRAME_BITS window of stream whose CRC residual is tag; returns
    its payload, or None. Rolling residual, same as the RTL FrameDecoder."""
    win = reg = 0
    for i, b in enumerate(stream):
        out = (win >> (FRAME_BITS - 1)) & 1
        win = ((win << 1) | b) & ((1 << FRAME_BITS) - 1)
        reg = _step(reg, b) ^ (SHIFT_OUT if out else 0)
        if i >= FRAME_BITS - 1 and reg == tag:
            return win >> DEG
    return None


# ---- FT232H transport ----

class Link:
    CHUNK = 512  # bytes per exchange; must fit the FT232H's 1 KiB FIFOs

    def __init__(self, url, freq):
        from pyftdi.ftdi import Ftdi
        from pyftdi.spi import SpiController
        # A run killed mid-exchange can leave the MPSSE engine halfway
        # through a command; only a USB reset clears that.
        f = Ftdi()
        f.open_from_url(url)
        f.reset(usb_reset=True)
        f.close()
        self.ctrl = SpiController(cs_count=1)
        self.ctrl.configure(url)
        self.port = self.ctrl.get_port(cs=0, freq=freq, mode=0)
        self.ctrl.ftdi.purge_buffers()
        # The chip only makes 60 MHz / 2n; report what it really runs at.
        print(f"[ftdi] SPI mode 0 at {self.ctrl.ftdi.set_frequency(freq) / 1e6:.3f} MHz")

    def clock(self, mosi):
        """Clock the bits out on MOSI, padded with idle zeros to whole bytes,
        and return the MISO bits sampled at the same edges."""
        req = bytearray((len(mosi) + 7) // 8)
        for i, b in enumerate(mosi):
            req[i // 8] |= b << (7 - i % 8)
        resp = bytearray()
        for i in range(0, len(req), self.CHUNK):
            out = req[i:i + self.CHUNK]
            got = bytearray(self.port.exchange(out, duplex=True))
            # pyftdi can return short without an error; the rest is still
            # coming and must not leak into the next exchange.
            deadline = time.monotonic() + 2
            while len(got) < len(out) and time.monotonic() < deadline:
                got += self.ctrl.ftdi.read_data_bytes(len(out) - len(got), 4)
            if len(got) < len(out):
                raise IOError(f"FT232H returned {len(got)} of {len(out)} bytes")
            resp += got
        return [(resp[i // 8] >> (7 - i % 8)) & 1 for i in range(len(mosi))]

    def transact(self, tag, addr, data=0):
        """Send one command and return (resp, data) from its reply. The reply
        starts a few bits after the command ends, so clock one frame plus
        some slack of idle bits behind it."""
        miso = self.clock(encode(tag, addr, data) + [0] * (FRAME_BITS + 16))
        payload = find_frame(miso[FRAME_BITS:], TAG_RESP)
        if payload is None:
            raise IOError(f"no response to command at 0x{addr:05x}")
        return payload >> DATA_BITS, payload & 0xFFFFFFFF

    def write(self, addr, data):
        return self.transact(TAG_WRITE, addr, data)[0]

    def read(self, addr):
        return self.transact(TAG_READ, addr)

    def close(self):
        self.ctrl.terminate()


# ---- Test ----

def run(link, rng):
    def check(what, got, want):
        if got != want:
            raise AssertionError(f"{what}: got {got}, expected {want}")

    def write_read(addr, data):
        check(f"write 0x{addr:05x}", link.write(addr, data), OKAY)
        check(f"read 0x{addr:05x}", link.read(addr), (OKAY, data))

    words = [RAM_BASE + 4 * i for i in range(RAM_BYTES // 4)]

    print("[test] 1: walking ones / zeros")
    for bit in range(32):
        write_read(RAM_BASE + 8, 1 << bit)
        write_read(RAM_BASE + 8, ~(1 << bit) & 0xFFFFFFFF)

    print(f"[test] 2: address-in-address, {len(words)} words")
    for a in words:
        check(f"write 0x{a:05x}", link.write(a, a ^ 0xA5000000), OKAY)
    for a in words:
        check(f"read 0x{a:05x}", link.read(a), (OKAY, a ^ 0xA5000000))

    print("[test] 3: random data, shuffled readback")
    expect = {a: rng.getrandbits(32) for a in rng.sample(words, 64)}
    for a, d in expect.items():
        check(f"write 0x{a:05x}", link.write(a, d), OKAY)
    for a in rng.sample(list(expect), len(expect)):
        check(f"read 0x{a:05x}", link.read(a), (OKAY, expect[a]))

    print("[test] 4: info registers")
    check("read ID", link.read(INFO_ID), (OKAY, ID_VALUE))
    write_read(INFO_SCRATCH, 0x12345678)
    check("write LEDS", link.write(INFO_LEDS, 0xFFFFFFFF), OKAY)
    check("read LEDS", link.read(INFO_LEDS), (OKAY, 0xF))

    print("[test] 5: error responses")
    check("write ID", link.write(INFO_ID, 0), SLVERR)
    link.write(RAM_BASE, 0x600DF00D)
    for a in (RAM_BASE + RAM_BYTES, INFO_ID + 0xC, 0x20000, 0xFFFFC):
        check(f"write 0x{a:05x}", link.write(a, 0xBAD0BAD0), SLVERR)
        check(f"read 0x{a:05x}", link.read(a)[0], SLVERR)
    check("RAM after errors", link.read(RAM_BASE), (OKAY, 0x600DF00D))
    check("ID after errors", link.read(INFO_ID), (OKAY, ID_VALUE))

    link.write(INFO_LEDS, 0x5)  # LD0 + LD2 on: visible proof the run finished


def main():
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--url", default="ftdi://ftdi:232h/1", help="pyftdi URL of the FT232H")
    ap.add_argument("--freq", type=float, default=10e6, help="SPI clock in Hz (max 15e6)")
    ap.add_argument("--seed", type=int, default=1)
    args = ap.parse_args()

    link = Link(args.url, args.freq)
    try:
        run(link, random.Random(args.seed))
    except (AssertionError, IOError) as e:
        print(f"[test] FAIL: {e}")
        return 1
    finally:
        link.close()
    print("[test] PASS")
    return 0


if __name__ == "__main__":
    sys.exit(main())
