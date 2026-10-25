"""Compact binary wire encoding, compression, and MTU fragmentation for The Chain.

Features:
  1. Compact binary packing with binary signatures (halves signature wire size).
  2. Sub-32-byte compact Hello beacons for LoRa PHY.
  3. zlib payload compression for large event batches and claims.
  4. Automatic packet fragmentation and reassembly ensuring every physical packet
     transmitted over radio is strictly <= 255 bytes (LoRa SX1262/SX1276 MTU).
"""
from __future__ import annotations

import json
import struct
import zlib
from typing import Any, Dict, List, Optional, Tuple

MAGIC_BYTE = 0xC1
VERSION = 1

TYPE_HELLO = 1
TYPE_DATA = 2
TYPE_AE_SUM = 3
TYPE_AE_EV = 4
TYPE_ACK = 5
TYPE_FRAG = 6
TYPE_FALLBACK_JSON = 7

MAX_LORA_MTU = 255


class WireCodec:
    """Encodes and decodes frames to/from compact binary format."""

    @staticmethod
    def encode(frame: dict, signer: Optional[Any] = None) -> bytes:
        """Encode frame into compact binary representation."""
        t_str = frame.get("t")
        t_code = {
            "hello": TYPE_HELLO,
            "data": TYPE_DATA,
            "ae.sum": TYPE_AE_SUM,
            "ae.ev": TYPE_AE_EV,
            "ack": TYPE_ACK,
        }.get(t_str, TYPE_FALLBACK_JSON)

        sender = str(frame.get("snd") or frame.get("id") or "")
        sender_bytes = sender.encode("utf-8")
        seq = int(frame.get("seq", 0))

        sig_hex = frame.get("sig", "")
        sig_bytes = b""
        if sig_hex:
            try:
                sig_bytes = bytes.fromhex(sig_hex)
            except ValueError:
                sig_bytes = sig_hex.encode("utf-8")

        # Hello beacon compact packing
        if t_code == TYPE_HELLO:
            # Format: MAGIC(1), VER(1), TYPE(1), SENDER_LEN(1), SENDER, SEQ(2), BAT(1),
            #         CELL_LEN(1), CELL, BF_M(2), BF_K(1), BF_HEX_LEN(2), BF_HEX, SIG_LEN(1), SIG_BYTES
            bat_byte = int(round(max(0.0, min(1.0, float(frame.get("bat", 1.0)))) * 255))
            cell = (frame.get("cell") or "").encode("utf-8")

            bf = frame.get("bf", [128, 3, "0"])
            bf_m = int(bf[0]) if isinstance(bf, (list, tuple)) and len(bf) >= 1 else 128
            bf_k = int(bf[1]) if isinstance(bf, (list, tuple)) and len(bf) >= 2 else 3
            bf_hex = str(bf[2]).encode("utf-8") if isinstance(bf, (list, tuple)) and len(bf) >= 3 else b"0"

            hdr = struct.pack("!BBB B", MAGIC_BYTE, VERSION, TYPE_HELLO, len(sender_bytes)) + sender_bytes
            body = struct.pack("!H B B", seq, bat_byte, len(cell)) + cell + \
                   struct.pack("!H B H", bf_m, bf_k, len(bf_hex)) + bf_hex + \
                   struct.pack("!B", len(sig_bytes)) + sig_bytes
            return hdr + body

        # General frame compact packing (data, ae.sum, ae.ev, etc.)
        body_dict = {k: v for k, v in frame.items() if k not in ("snd", "seq", "sig")}
        body_json = json.dumps(body_dict, sort_keys=True, separators=(",", ":")).encode("utf-8")
        compressed_body = zlib.compress(body_json, level=9)

        hdr = struct.pack("!BBB B H B",
                          MAGIC_BYTE, VERSION, t_code,
                          len(sender_bytes), seq, len(sig_bytes))
        payload = hdr + sender_bytes + sig_bytes + struct.pack("!H", len(compressed_body)) + compressed_body
        return payload

    @staticmethod
    def decode(raw: bytes, signer: Optional[Any] = None) -> Optional[dict]:
        """Decode raw wire bytes (binary or fallback JSON) to dict."""
        if not raw:
            return None

        # Fallback to standard JSON if not starting with MAGIC_BYTE
        if raw[0] != MAGIC_BYTE:
            try:
                return json.loads(raw.decode("utf-8"))
            except Exception:
                return None

        if len(raw) < 4:
            return None

        magic, ver, t_code = raw[0], raw[1], raw[2]
        if magic != MAGIC_BYTE or ver != VERSION:
            return None

        if t_code == TYPE_HELLO:
            sender_len = raw[3]
            offset = 4
            sender = raw[offset:offset + sender_len].decode("utf-8")
            offset += sender_len

            seq, bat_byte, cell_len = struct.unpack("!H B B", raw[offset:offset + 4])
            offset += 4
            bat = round(bat_byte / 255.0, 3)
            cell = raw[offset:offset + cell_len].decode("utf-8") or None
            offset += cell_len

            bf_m, bf_k, bf_hex_len = struct.unpack("!H B H", raw[offset:offset + 5])
            offset += 5
            bf_hex = raw[offset:offset + bf_hex_len].decode("utf-8")
            offset += bf_hex_len

            sig_len = raw[offset]
            offset += 1
            sig_bytes = raw[offset:offset + sig_len]
            sig = sig_bytes.hex() if sig_len == 32 else sig_bytes.decode("utf-8", errors="ignore")

            return {
                "t": "hello",
                "id": sender,
                "snd": sender,
                "seq": seq,
                "bat": bat,
                "cell": cell,
                "bf": [bf_m, bf_k, bf_hex],
                "sig": sig,
            }

        # Decode general frame
        hdr_size = struct.calcsize("!BBB B H B")
        if len(raw) < hdr_size:
            return None
        _, _, _, sender_len, seq, sig_len = struct.unpack("!BBB B H B", raw[:hdr_size])
        offset = hdr_size
        sender = raw[offset:offset + sender_len].decode("utf-8")
        offset += sender_len

        sig_bytes = raw[offset:offset + sig_len]
        offset += sig_len
        sig = sig_bytes.hex() if sig_len == 32 else sig_bytes.decode("utf-8", errors="ignore")

        body_len = struct.unpack("!H", raw[offset:offset + 2])[0]
        offset += 2
        compressed_body = raw[offset:offset + body_len]

        body_dict = json.loads(zlib.decompress(compressed_body).decode("utf-8"))
        type_str = {
            TYPE_DATA: "data",
            TYPE_AE_SUM: "ae.sum",
            TYPE_AE_EV: "ae.ev",
            TYPE_ACK: "ack",
        }.get(t_code, "unknown")

        body_dict["t"] = type_str
        body_dict["snd"] = sender
        body_dict["seq"] = seq
        if sig:
            body_dict["sig"] = sig
        return body_dict


class Fragmenter:
    """Splits frames exceeding MTU into fragments <= 255 bytes and reassembles them."""

    def __init__(self, max_packet_size: int = MAX_LORA_MTU):
        self.max_packet_size = max_packet_size
        self._msg_seq = 0
        self._rx_buffers: Dict[Tuple[str, int], Dict[str, Any]] = {}  # (sender, frag_id) -> {chunks, total, expiry}

    def fragment(self, raw_bytes: bytes, sender: str = "") -> List[bytes]:
        """If raw_bytes fits in MTU, returns [raw_bytes]. Otherwise splits into fragments."""
        if len(raw_bytes) <= self.max_packet_size:
            return [raw_bytes]

        self._msg_seq = (self._msg_seq + 1) & 0xFFFF
        frag_id = self._msg_seq
        sender_bytes = sender.encode("utf-8")

        # Header: MAGIC(1), VER(1), TYPE_FRAG(1), SENDER_LEN(1), SENDER, FRAG_ID(2), IDX(1), TOTAL(1)
        hdr_prefix_len = 3 + 1 + len(sender_bytes) + 2 + 1 + 1
        chunk_size = self.max_packet_size - hdr_prefix_len
        if chunk_size <= 0:
            chunk_size = 128

        chunks = [raw_bytes[i:i + chunk_size] for i in range(0, len(raw_bytes), chunk_size)]
        total = len(chunks)
        packets = []

        for idx, chunk in enumerate(chunks):
            hdr = struct.pack("!BBB B", MAGIC_BYTE, VERSION, TYPE_FRAG, len(sender_bytes)) + \
                  sender_bytes + struct.pack("!H B B", frag_id, idx, total)
            pkt = hdr + chunk
            assert len(pkt) <= self.max_packet_size, f"Fragment {len(pkt)} exceeded {self.max_packet_size}"
            packets.append(pkt)

        return packets

    def reassemble(self, pkt: bytes, now: float) -> Optional[bytes]:
        """Ingest packet. If it's a non-fragment, returns it. If it's a fragment,
        stores and reassembles when all chunks arrive."""
        expired = [k for k, buf in self._rx_buffers.items() if now > buf["expiry"]]
        for k in expired:
            del self._rx_buffers[k]

        if not pkt or pkt[0] != MAGIC_BYTE or len(pkt) < 4 or pkt[2] != TYPE_FRAG:
            return pkt  # Not a fragment, pass through

        try:
            sender_len = pkt[3]
            offset = 4
            sender = pkt[offset:offset + sender_len].decode("utf-8")
            offset += sender_len
            frag_id, idx, total = struct.unpack("!H B B", pkt[offset:offset + 4])
            offset += 4
            chunk_data = pkt[offset:]

            key = (sender, frag_id)
            if key not in self._rx_buffers:
                self._rx_buffers[key] = {
                    "chunks": {},
                    "total": total,
                    "expiry": now + 15.0,  # 15s reassembly window
                }

            buf = self._rx_buffers[key]
            buf["chunks"][idx] = chunk_data

            if len(buf["chunks"]) == buf["total"]:
                del self._rx_buffers[key]
                full_bytes = b"".join(buf["chunks"][i] for i in range(buf["total"]))
                return full_bytes
        except Exception:
            pass

        return None
