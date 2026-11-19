"""最小 RCON 客户端（Source RCON 协议），给 dev 冒烟测试发服务端命令。

用法：python tools/rcon.py "<command>" [--port 25575] [--password smoketest]
"""

from __future__ import annotations

import argparse
import socket
import struct

SERVERDATA_AUTH = 3
SERVERDATA_AUTH_RESPONSE = 2
SERVERDATA_EXECCOMMAND = 2


def _pack(request_id: int, request_type: int, body: str) -> bytes:
    payload = body.encode("utf-8") + b"\x00\x00"
    # 头 = length(4) + id(4) + type(4)，length 只算 id+type+payload
    return struct.pack("<iii", len(payload) + 8, request_id, request_type) + payload


def _read_packet(sock: socket.socket) -> tuple[int, int, str]:
    raw_length = sock.recv(4)
    if len(raw_length) < 4:
        raise ConnectionError("connection closed")
    (length,) = struct.unpack("<i", raw_length)
    data = b""
    while len(data) < length:
        chunk = sock.recv(length - len(data))
        if not chunk:
            raise ConnectionError("connection closed mid-packet")
        data += chunk
    request_id, request_type = struct.unpack("<ii", data[:8])
    return request_id, request_type, data[8:-2].decode("utf-8", errors="replace")


def run(command: str, host: str, port: int, password: str, timeout: float) -> str:
    with socket.create_connection((host, port), timeout=timeout) as sock:
        sock.sendall(_pack(1, SERVERDATA_AUTH, password))
        request_id, request_type, _ = _read_packet(sock)
        if request_type == SERVERDATA_AUTH_RESPONSE and request_id == -1:
            raise PermissionError("rcon authentication failed")
        if request_type == 0:  # 有些实现先回一个空 RESPONSE_VALUE
            _read_packet(sock)

        sock.sendall(_pack(2, SERVERDATA_EXECCOMMAND, command))
        sock.settimeout(2.0)
        parts: list[str] = []
        while True:
            try:
                _, request_type, body = _read_packet(sock)
            except (socket.timeout, TimeoutError):
                break
            if body == "":
                break
            parts.append(body)
        return "".join(parts)


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("command")
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=25575)
    parser.add_argument("--password", default="smoketest")
    parser.add_argument("--timeout", type=float, default=300.0)
    args = parser.parse_args()
    print(run(args.command, args.host, args.port, args.password, args.timeout))


if __name__ == "__main__":
    main()
