"""在 Sable jar 里找 ClientboundPhysicsPropertyPacket 的发送方。"""

import zipfile

JAR = r"D:\mc\PCL2\.minecraft\versions\1.21.1-NeoForge_21.1.265\mods\sable-neoforge-1.21.1-2.0.6.jar"
NEEDLE = b"ClientboundPhysicsPropertyPacket"


def main() -> None:
    with zipfile.ZipFile(JAR) as archive:
        for name in archive.namelist():
            if not name.endswith(".class"):
                continue
            if NEEDLE in archive.read(name):
                print(name)


if __name__ == "__main__":
    main()
