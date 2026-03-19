import struct

data = b'\x07\xe1\x03\x18\x07\x03-\x00H\xd8B\r\n'

# Format string:
# !HBBBBB => 2 bytes (short), 5 bytes (5 characters), 4 bytes (4 characters)
unpacked_values = struct.unpack_from('!HBBBBB', data)

print(unpacked_values)
