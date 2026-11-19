"""在 Sable jar 里找所有引用了 applyToBlocks / applyAll 的类（扫常量池 UTF-8 常量）。"""

import re
import sys
import zipfile

JAR = r"D:\mc\PCL2\.minecraft\versions\1.21.1-NeoForge_21.1.265\mods\sable-neoforge-1.21.1-2.0.6.jar"
NEEDLES = (b"applyToBlocks", b"applyAll", b"physics_block_properties")


def main() -> None:
    with zipfile.ZipFile(JAR) as archive:
        names = [n for n in archive.namelist() if n.endswith(".class")]
        for name in names:
            data = archive.read(name)
            hits = [needle.decode() for needle in NEEDLES if needle in data]
            if hits:
                print(f"{name}  <- {', '.join(hits)}")
    print(f"scanned {len(names)} classes")


if __name__ == "__main__":
    main()
