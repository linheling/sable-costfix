"""产物自检：jar 内容 / mods.toml / mixins.json / 不得含上游 class。

用法：python tools/check_jar.py [jar 路径]（缺省取 build/libs 下最新的 jar）
"""

from __future__ import annotations

import glob
import json
import pathlib
import sys
import tomllib
import zipfile

DEFAULT = str(pathlib.Path(__file__).resolve().parents[1] / "build" / "libs" / "*.jar")


def main() -> None:
    jar = sys.argv[1] if len(sys.argv) > 1 else sorted(glob.glob(DEFAULT))[-1]
    with zipfile.ZipFile(jar) as archive:
        names = archive.namelist()
        toml = tomllib.loads(archive.read("META-INF/neoforge.mods.toml").decode("utf-8"))
        mixins = json.loads(archive.read("sablecostfix.mixins.json").decode("utf-8"))

    print(f"jar: {jar}")
    print("entries:", len(names))
    for name in sorted(names):
        print("  ", name)
    print("mods.toml OK ->", toml["mods"][0]["modId"], toml["mods"][0]["version"],
          "| mixin configs:", toml["mixins"])
    print("dependencies ->", [(d["modId"], d["type"], d["versionRange"])
                              for d in toml["dependencies"]["sablecostfix"]])
    print("mixins.json ->", mixins["package"], mixins["mixins"], mixins["injectors"])
    assert not any(n.startswith("dev/ryanhcode/") for n in names), "upstream classes leaked into the jar"
    assert not any(n.startswith("net/minecraft/") for n in names), "minecraft classes leaked into the jar"
    print("no upstream / minecraft classes in jar: OK")


if __name__ == "__main__":
    main()
