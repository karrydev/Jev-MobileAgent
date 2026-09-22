"""Deterministic, valid image fixtures for the live probe."""

from __future__ import annotations

import base64
import binascii
import hashlib
import struct
import zlib
from typing import Any


def _chunk(kind: bytes, payload: bytes) -> bytes:
    return struct.pack(">I", len(payload)) + kind + payload + struct.pack(">I", binascii.crc32(kind + payload) & 0xFFFFFFFF)


def solid_png(width: int, height: int, rgb: tuple[int, int, int]) -> bytes:
    """Return a small non-interlaced RGB PNG without optional dependencies."""

    if width <= 0 or height <= 0:
        raise ValueError("PNG dimensions must be positive")
    if any(channel < 0 or channel > 255 for channel in rgb):
        raise ValueError("PNG channels must be between 0 and 255")
    row = b"\x00" + bytes(rgb) * width
    raw = row * height
    return b"\x89PNG\r\n\x1a\n" + _chunk(b"IHDR", struct.pack(">IIBBBBB", width, height, 8, 2, 0, 0, 0)) + _chunk(b"IDAT", zlib.compress(raw, 9)) + _chunk(b"IEND", b"")


def _ui_png(*, completed: bool) -> bytes:
    """Make a tiny white phone scene with a visible central state marker."""

    width = height = 64
    pixels = [[(255, 255, 255) for _ in range(width)] for _ in range(height)]

    def fill(x0: int, y0: int, x1: int, y1: int, color: tuple[int, int, int]) -> None:
        for y in range(max(0, y0), min(height, y1)):
            for x in range(max(0, x0), min(width, x1)):
                pixels[y][x] = color

    if completed:
        # A blue completed panel and a green check make the after frame
        # visibly different while remaining easy to recognize at 64x64.
        fill(16, 24, 48, 40, (35, 95, 210))
        fill(21, 31, 25, 35, (255, 255, 255))
        fill(24, 34, 29, 38, (255, 255, 255))
        fill(28, 31, 37, 35, (255, 255, 255))
    else:
        # This is the target used by the probe's coordinate mapping: a red
        # Start button from x=16..48 and y=24..40 on a white screen.
        fill(15, 23, 49, 41, (110, 20, 20))
        fill(16, 24, 48, 40, (220, 30, 30))
        fill(19, 27, 45, 37, (235, 55, 55))

    raw = b"".join(b"\x00" + bytes(channel for pixel in row for channel in pixel) for row in pixels)
    return b"\x89PNG\r\n\x1a\n" + _chunk(b"IHDR", struct.pack(">IIBBBBB", width, height, 8, 2, 0, 0, 0)) + _chunk(b"IDAT", zlib.compress(raw, 9)) + _chunk(b"IEND", b"")


def synthetic_probe_images() -> list[dict[str, Any]]:
    """Return stable red/blue 64x64 PNGs as model image objects.

    The old simulated probe used strings such as ``fixture-image-1`` as if
    they were base64.  These bytes are real PNGs so the same probe request can
    be sent to a provider without relying on a local fixture convention.
    """

    fixtures = (
        ("probe-red-64", _ui_png(completed=False), "before: central red Start button"),
        ("probe-blue-64", _ui_png(completed=True), "after: blue completion panel with check"),
    )
    result: list[dict[str, Any]] = []
    for image_id, raw, scene in fixtures:
        result.append(
            {
                "image_id": image_id,
                "media_type": "image/png",
                "data": base64.b64encode(raw).decode("ascii"),
                "sha256": hashlib.sha256(raw).hexdigest(),
                "width": 64,
                "height": 64,
                "scene": scene,
            }
        )
    return result
