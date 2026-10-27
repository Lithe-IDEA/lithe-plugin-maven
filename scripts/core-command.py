#!/usr/bin/env python3
"""Test-only C ABI client. The caller owns its process deadline and library identity."""
import ctypes
import json
import sys

core = ctypes.CDLL(sys.argv[1])
core.lithe_core_execute_json.argtypes = [ctypes.c_char_p]
core.lithe_core_execute_json.restype = ctypes.c_void_p
core.lithe_core_free_string.argtypes = [ctypes.c_void_p]
request = sys.stdin.buffer.read(32 * 1024 * 1024 + 1)
if len(request) > 32 * 1024 * 1024:
    raise ValueError("Core test request exceeds 32 MiB")
result = core.lithe_core_execute_json(request)
if not result:
    raise RuntimeError("Core returned no response")
try:
    document = json.loads(ctypes.string_at(result))
finally:
    core.lithe_core_free_string(result)
print(json.dumps(document))
