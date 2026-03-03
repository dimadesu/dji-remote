#!/usr/bin/env python3
"""Verify CRC8 and CRC16 implementations against the reference."""

def reverse_bits8(v):
    result = 0
    for _ in range(8):
        result = (result << 1) | (v & 1)
        v >>= 1
    return result

def reference_crc8(data):
    """Reference: explicit bit reversal, MSB-first, no masking"""
    crc = 0xEE
    for b in data:
        byte_val = reverse_bits8(b)
        crc = crc ^ byte_val
        for _ in range(8):
            if (crc & 0x80) != 0:
                crc = (crc << 1) ^ 0x31
            else:
                crc = crc << 1
    crc = reverse_bits8(crc)
    return crc & 0xFF

def reference_crc8_masked(data):
    """Reference but with proper 8-bit masking"""
    crc = 0xEE
    for b in data:
        byte_val = reverse_bits8(b)
        crc = crc ^ byte_val
        for _ in range(8):
            if (crc & 0x80) != 0:
                crc = ((crc << 1) ^ 0x31) & 0xFF
            else:
                crc = (crc << 1) & 0xFF
    crc = reverse_bits8(crc)
    return crc & 0xFF

def our_crc8(data):
    """Our implementation: reflected LSB-first, poly=0x31, init=0xEE"""
    crc = 0xEE
    for b in data:
        crc = crc ^ (b & 0xFF)
        for _ in range(8):
            if (crc & 0x01) != 0:
                crc = (crc >> 1) ^ 0x31
            else:
                crc = crc >> 1
    return crc & 0xFF

def proper_reflected_crc8(data):
    """Proper reflected: reflected init=0x77, reflected poly=0x8C"""
    crc = 0x77
    for b in data:
        crc = crc ^ (b & 0xFF)
        for _ in range(8):
            if (crc & 0x01) != 0:
                crc = (crc >> 1) ^ 0x8C
            else:
                crc = crc >> 1
    return crc & 0xFF

def reference_crc16(data):
    """Reference CRC16: explicit bit reversal"""
    crc = 0x496C
    for b in data:
        byte_val = reverse_bits8(b)
        crc = crc ^ (byte_val << 8)
        for _ in range(8):
            if (crc & 0x8000) != 0:
                crc = (crc << 1) ^ 0x1021
            else:
                crc = crc << 1
    v = crc
    result = 0
    for _ in range(16):
        result = (result << 1) | (v & 1)
        v >>= 1
    return result & 0xFFFF

def our_crc16(data):
    """Our CRC16: reflected LSB-first, poly=0x1021, init=0x496C"""
    crc = 0x496C
    for b in data:
        crc = crc ^ (b & 0xFF)
        for _ in range(8):
            if (crc & 0x01) != 0:
                crc = (crc >> 1) ^ 0x1021
            else:
                crc = crc >> 1
    return crc & 0xFFFF

# Test with pair message header
header = bytes([0x55, 0x33, 0x04])
print(f"=== CRC8 of header {header.hex()} ===")
print(f"Reference (no mask):   0x{reference_crc8(header):02X}")
print(f"Reference (masked):    0x{reference_crc8_masked(header):02X}")
print(f"Our implementation:    0x{our_crc8(header):02X}")
print(f"Proper reflected:      0x{proper_reflected_crc8(header):02X}")
print(f"Expected (msg byte 3): 0x04")
print()

# Full pair message from logs
pair_msg = bytes([
    0x55, 0x33, 0x04, 0x04, 0x02, 0x07, 0x92, 0x80, 0x40, 0x07, 0x45,
    0x20, 0x32, 0x38, 0x34, 0x61, 0x65, 0x35, 0x62,
    0x38, 0x64, 0x37, 0x36, 0x62, 0x33, 0x33, 0x37,
    0x35, 0x61, 0x30, 0x34, 0x61, 0x36, 0x34, 0x31,
    0x37, 0x61, 0x64, 0x37, 0x31, 0x62, 0x65, 0x61,
    0x33, 0x04, 0x6D, 0x62, 0x6C, 0x6E, 0xE8, 0x1C
])

def proper_reflected_crc16(data):
    """Proper reflected CRC16: reflected init and poly"""
    # reflect16(0x496C) and reflect16(0x1021)
    def reflect16(v):
        result = 0
        for _ in range(16):
            result = (result << 1) | (v & 1)
            v >>= 1
        return result
    init_reflected = reflect16(0x496C)
    poly_reflected = reflect16(0x1021)
    print(f"  Reflected init=0x{init_reflected:04X}, poly=0x{poly_reflected:04X}")
    crc = init_reflected
    for b in data:
        crc = crc ^ (b & 0xFF)
        for _ in range(8):
            if (crc & 0x01) != 0:
                crc = (crc >> 1) ^ poly_reflected
            else:
                crc = crc >> 1
    return crc & 0xFFFF

crc16_body = pair_msg[:-2]
expected_crc16 = int.from_bytes(pair_msg[-2:], 'little')

print(f"=== CRC16 of message body ({len(crc16_body)} bytes) ===")
print(f"Reference CRC16:        0x{reference_crc16(crc16_body):04X}")
print(f"Our CRC16:              0x{our_crc16(crc16_body):04X}")
print(f"Proper reflected CRC16: 0x{proper_reflected_crc16(crc16_body):04X}")
print(f"Expected (message):     0x{expected_crc16:04X}")

# Show what the correct pair message should look like
print()
print("=== Correct pair message with reference CRCs ===")
header_for_crc8 = bytes([0x55, 0x33, 0x04])
correct_crc8 = reference_crc8_masked(header_for_crc8)
# Build message with correct CRC8
msg_body = bytearray([0x55, 0x33, 0x04, correct_crc8,
    0x02, 0x07, 0x92, 0x80, 0x40, 0x07, 0x45,
    0x20, 0x32, 0x38, 0x34, 0x61, 0x65, 0x35, 0x62,
    0x38, 0x64, 0x37, 0x36, 0x62, 0x33, 0x33, 0x37,
    0x35, 0x61, 0x30, 0x34, 0x61, 0x36, 0x34, 0x31,
    0x37, 0x61, 0x64, 0x37, 0x31, 0x62, 0x65, 0x61,
    0x33, 0x04, 0x6D, 0x62, 0x6C, 0x6E])
correct_crc16 = reference_crc16(bytes(msg_body))
msg_body.append(correct_crc16 & 0xFF)
msg_body.append((correct_crc16 >> 8) & 0xFF)
print(f"Correct message: {' '.join(f'{b:02X}' for b in msg_body)}")
print(f"Our message:     {' '.join(f'{b:02X}' for b in pair_msg)}")
