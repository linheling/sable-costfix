"""生成一个冒烟测试用数据包：给 minecraft:redstone_wire 全枚举 1,296 个状态的质量。

用来在 dev 服务端里把 index 路径跑在"条目数 = 状态数"的真实矩阵上（1,296 × 1,296 = 1,679,616
次线性比较，约 0.5 s），这样 /sablecostfix verify 才有东西可比。

用法：python tools/make_smoke_datapack.py
"""

import itertools
import json
import pathlib

ROOT = pathlib.Path(__file__).resolve().parents[1] / "run" / "smoke" / "datapacks" / "smoke-physics"

SIDES = ("north", "south", "east", "west")
SIDE_VALUES = ("up", "side", "none")


def main() -> None:
    overrides = {}
    index = 0
    for power in range(16):
        for combo in itertools.product(SIDE_VALUES, repeat=4):
            conditions = [f"power={power}"] + [f"{side}={value}" for side, value in zip(SIDES, combo)]
            overrides[",".join(conditions)] = {"sable:mass": round(0.0001 * (index + 1), 6)}
            index += 1

    payload = {
        "priority": 1000,
        "selector": "minecraft:redstone_wire",
        "properties": {"sable:mass": 1.0},
        "overrides": overrides,
    }

    target = ROOT / "data" / "smoke" / "physics_block_properties" / "redstone_wire.json"
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(json.dumps(payload, indent=2), encoding="utf-8")

    (ROOT / "pack.mcmeta").write_text(
        json.dumps({"pack": {"pack_format": 48, "description": "sable-costfix smoke test"}}, indent=2),
        encoding="utf-8",
    )
    print(f"wrote {target} ({index} overrides, {target.stat().st_size:,} bytes)")
    print(f"naive comparisons per applyAll = {index * index:,}")


if __name__ == "__main__":
    main()
