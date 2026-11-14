#!/usr/bin/env python3
"""极简 DNS 服务器：对任意 TXT 查询返回固定内容，用于本地联调。"""
import socket, struct, sys, json, os

CFG = "/workspace/stunroute/test/txt.json"


def load():
    with open(CFG) as f:
        return json.load(f)


def parse_qname(data, off):
    labels = []
    while data[off] != 0:
        n = data[off]
        labels.append(data[off + 1:off + 1 + n].decode())
        off += 1 + n
    return ".".join(labels), off + 1


def main():
    port = int(sys.argv[1]) if len(sys.argv) > 1 else 5353
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    s.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    s.bind(("127.0.0.1", port))
    print("dns on 127.0.0.1:%d" % port, flush=True)
    while True:
        data, addr = s.recvfrom(2048)
        tid = data[:2]
        qname, off = parse_qname(data, 12)
        qtype = struct.unpack("!H", data[off:off + 2])[0]
        question = data[12:off + 4]
        cfg = load()
        value = cfg.get(qname)
        answers = b""
        ancount = 0
        if value is not None and qtype == 16:
            txt = value.encode()
            rdata = bytes([len(txt)]) + txt
            answers += b"\xc0\x0c" + struct.pack("!HHIH", 16, 1, 30, len(rdata)) + rdata
            ancount = 1
        flags = 0x8180
        hdr = tid + struct.pack("!HHHHH", flags, 1, ancount, 0, 0)
        s.sendto(hdr + question + answers, addr)


if __name__ == "__main__":
    main()
