#!/usr/bin/env python3
"""在 jar 的常量池里找引用了某个符号的类（不需要反编译）。

用法：
    python tools/find_callers.py [jar] [--needle X] [--needle Y]

jar 的解析顺序：位置参数 → 环境变量 `SABLE_JAR` → 仓库内 `libs/sable.jar`。
默认 needle 是 Sable 的三个方法名：`applyToBlocks` / `applyAll` / `physics_block_properties`。

这个脚本就是 2026-11-19 定位"客户端第二条入口"用的武器：

    python tools/find_callers.py --needle ClientboundPhysicsPropertyPacket
    # -> dev/ryanhcode/sable/network/packets/tcp/ClientboundPhysicsPropertyPacket.class
    #    dev/ryanhcode/sable/SableCommonEvents.class   (发包方)
    #    dev/ryanhcode/sable/network/tcp/SableTCPPackets.class
"""

from __future__ import annotations

import argparse
import os
import sys
import zipfile
from pathlib import Path

ENV_VAR = "SABLE_JAR"
DEFAULT_JAR = Path(__file__).resolve().parents[1] / "libs" / "sable.jar"
DEFAULT_NEEDLES = ("applyToBlocks", "applyAll", "physics_block_properties")


def resolve_jar(value: str | None) -> Path:
    """位置参数 → $SABLE_JAR → libs/sable.jar。显式给出的路径不存在时直接报错，不静默回落。"""
    if value:
        if not Path(value).is_file():
            sys.exit(f"jar 不存在：{value}")
        return Path(value)
    env = os.environ.get(ENV_VAR)
    if env:
        if not Path(env).is_file():
            sys.exit(f"${ENV_VAR} 指向的文件不存在：{env}")
        return Path(env)
    if DEFAULT_JAR.is_file():
        return DEFAULT_JAR
    sys.exit(
        "找不到 jar。三种给法任选：\n"
        "  python tools/find_callers.py <jar>\n"
        f"  set {ENV_VAR}=<jar>        # PowerShell: $env:{ENV_VAR}='<jar>'\n"
        f"  把 jar 放到 {DEFAULT_JAR}"
    )


def main() -> None:
    parser = argparse.ArgumentParser(description="在 jar 里找引用了某符号的 class")
    parser.add_argument("jar", nargs="?", help=f"jar 路径（默认 ${ENV_VAR} 或 {DEFAULT_JAR}）")
    parser.add_argument(
        "--needle",
        action="append",
        default=None,
        metavar="SYMBOL",
        help="要搜索的符号，可重复；省略时用内置的三个（applyToBlocks / applyAll / physics_block_properties）",
    )
    args = parser.parse_args()

    jar = resolve_jar(args.jar)
    needles = tuple(n.encode("utf-8") for n in (args.needle or DEFAULT_NEEDLES))

    print(f"jar:     {jar}")
    print(f"needles: {', '.join(n.decode() for n in needles)}")
    hits_total = 0
    with zipfile.ZipFile(jar) as archive:
        names = [n for n in archive.namelist() if n.endswith(".class")]
        for name in names:
            data = archive.read(name)
            hits = [needle.decode() for needle in needles if needle in data]
            if hits:
                hits_total += 1
                print(f"{name}  <- {', '.join(hits)}")
    print(f"scanned {len(names)} classes, {hits_total} hit(s)")


if __name__ == "__main__":
    main()
