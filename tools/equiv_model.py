#!/usr/bin/env python3
"""sable-costfix 等价性模型：上游 naive 扫描 vs 本补丁的索引实现。

不依赖 Minecraft/Sable 运行时——把 BlockState 抽象成 {属性名: 值字符串}，
BlockStateConditionSet.matches 抽象成"逐项字符串相等 + 属性必须存在"，
BlockState.sable$properties 抽象成 {属性键: 值} 的 dict。

两部分：
  1. real：拿实例里 copyfoxies 2.2.0 的真实 11 个 physics_block_properties 文件跑一遍，
     对比 naive / indexed 的最终属性，并复算报告里的比较次数（应为 59,426,133）。
  2. fuzz：随机生成 definition（含部分键、空键、非法值、属性缺失、多种键形状、
     多方块 tag 选择子、优先级冲突），断言两条路径逐状态逐槽位相同。

用法：python tools/equiv_model.py
"""

from __future__ import annotations

import itertools
import json
import random
import zipfile
from dataclasses import dataclass, field

JAR = r"D:\mc\PCL2\.minecraft\versions\1.21.1-NeoForge_21.1.265\mods\copyfoxies-2.2.0.jar"
PREFIX = "data/copyfoxies/physics_block_properties/"


# --------------------------------------------------------------------------- 数据模型


@dataclass
class Block:
    identity: str
    domains: dict[str, list[str]]

    def states(self) -> list[dict[str, str]]:
        if not self.domains:
            return [{}]
        names = list(self.domains)
        return [dict(zip(names, combo)) for combo in itertools.product(*(self.domains[n] for n in names))]


@dataclass
class Definition:
    selector: str
    priority: int
    base: dict[str, object]
    overrides: list[tuple[str, list[tuple[str, str]], dict[str, object]]] = field(default_factory=list)
    blocks: list[str] = field(default_factory=list)


def parse_key(key: str) -> list[tuple[str, str]]:
    if not key.strip():
        return []
    conditions = []
    for part in key.split(","):
        name, _, value = part.partition("=")
        conditions.append((name.strip(), value.strip()))
    return conditions


def load_real_definitions() -> list[Definition]:
    definitions: list[Definition] = []
    with zipfile.ZipFile(JAR) as archive:
        names = sorted(n for n in archive.namelist() if n.startswith(PREFIX) and n.endswith(".json"))
        for name in names:
            raw = json.loads(archive.read(name))
            overrides = []
            for key, props in (raw.get("overrides") or {}).items():
                overrides.append((key, parse_key(key), props))
            definitions.append(
                Definition(
                    selector=raw["selector"],
                    priority=raw.get("priority", 1000),
                    base=raw.get("properties") or {},
                    overrides=overrides,
                )
            )
    # 与上游一致：apply() 里按 priority 升序稳定排序
    definitions.sort(key=lambda d: d.priority)
    return definitions


def blocks_from_real(definitions: list[Definition]) -> dict[str, Block]:
    per_selector: dict[str, dict[str, list[str]]] = {}
    for definition in definitions:
        domains = per_selector.setdefault(definition.selector, {})
        for _, conditions, _ in definition.overrides:
            for name, value in conditions:
                values = domains.setdefault(name, [])
                if value not in values:
                    values.append(value)
    return {selector: Block(selector, domains) for selector, domains in per_selector.items()}


# --------------------------------------------------------------------------- 两条路径


def matches(conditions: list[tuple[str, str]], state: dict[str, str], domains: dict[str, list[str]]) -> bool:
    for name, value in conditions:
        if name not in state or value not in domains.get(name, ()):
            return False
        if state[name] != value:
            return False
    return True


def naive(definitions: list[Definition], blocks: dict[str, Block]) -> tuple[dict[tuple[str, str], dict], int]:
    out: dict[tuple[str, str], dict] = {}
    comparisons = 0
    for definition in definitions:
        for identity in definition.blocks:
            block = blocks[identity]
            for state in block.states():
                slot = out.setdefault((identity, tuple(sorted(state.items()))), {})
                slot.update(definition.base)
                for _, conditions, props in definition.overrides:
                    comparisons += 1
                    if matches(conditions, state, block.domains):
                        slot.update(props)
    return out, comparisons


def indexed(definitions: list[Definition], blocks: dict[str, Block]) -> tuple[dict[tuple[str, str], dict], int, dict]:
    """本补丁 FastApply.apply() 的逐行模型。返回 (结果, 索引路径比较次数, 统计)。"""
    out: dict[tuple[str, str], dict] = {}
    comparisons = 0
    stats = {"definitions": 0, "indexed": 0, "fallback": 0, "states": 0, "entries": 0}
    for definition in definitions:
        relevant = [identity for identity in definition.blocks if identity in blocks]
        if not relevant:
            continue
        stats["definitions"] += 1
        stats["entries"] += len(definition.overrides)

        signature: list[str] | None = None
        fallback = False
        if definition.overrides:
            for _, conditions, _ in definition.overrides:
                names = [name for name, _ in conditions]
                if signature is None:
                    signature = names
                elif signature != names:
                    fallback = True  # 键形状不一致 -> 上游原实现
                    break
        if fallback:
            stats["fallback"] += 1
        elif definition.overrides:
            stats["indexed"] += 1

        for identity in relevant:
            block = blocks[identity]
            states = block.states()
            stats["states"] += len(states)

            if fallback or not definition.overrides:
                for state in states:
                    slot = out.setdefault((identity, tuple(sorted(state.items()))), {})
                    slot.update(definition.base)
                    for _, conditions, props in definition.overrides:
                        comparisons += 1
                        if matches(conditions, state, block.domains):
                            slot.update(props)
                continue

            comparisons += len(states) * len(definition.overrides)

            if not signature:  # 空条件键：对任何状态都成立
                entries = [props for _, _, props in definition.overrides if props]
                for state in states:
                    slot = out.setdefault((identity, tuple(sorted(state.items()))), {})
                    slot.update(definition.base)
                    for props in entries:
                        slot.update(props)
                continue

            if any(name not in block.domains for name in signature):
                # 属性缺失：matches() 对所有条目返回 false，只应用基线
                for state in states:
                    slot = out.setdefault((identity, tuple(sorted(state.items()))), {})
                    slot.update(definition.base)
                continue

            buckets: dict[tuple[str, ...], list[dict]] = {}
            for _, conditions, props in definition.overrides:
                if not props:
                    continue
                tuple_values = []
                resolvable = True
                for name, value in conditions:
                    if value not in block.domains.get(name, ()):
                        resolvable = False  # 非法值：永不命中
                        break
                    tuple_values.append(value)
                if resolvable:
                    buckets.setdefault(tuple(tuple_values), []).append(props)

            for state in states:
                slot = out.setdefault((identity, tuple(sorted(state.items()))), {})
                slot.update(definition.base)
                for props in buckets.get(tuple(state[name] for name in signature), ()):
                    slot.update(props)
    return out, comparisons, stats


# --------------------------------------------------------------------------- 真实数据


def check_real() -> None:
    definitions = load_real_definitions()
    blocks = blocks_from_real(definitions)
    total_entries = sum(len(d.overrides) for d in definitions)
    total_states = sum(len(b.states()) for b in blocks.values())
    print(f"[real] {len(definitions)} definition(s), {len(blocks)} block(s), "
          f"{total_entries:,} override entr(y/ies), {total_states:,} state(s)")

    for definition in definitions:
        block = blocks[definition.selector]
        states = len(block.states())
        if states != len(definition.overrides):
            print(f"[real] WARN {definition.selector}: states={states} entries={len(definition.overrides)}")

    for definition in definitions:
        definition.blocks = [definition.selector]

    reference, naive_comparisons = naive(definitions, blocks)
    fast, indexed_comparisons, stats = indexed(definitions, blocks)
    print(f"[real] naive comparisons  = {naive_comparisons:,}  (REPORT says 59,426,133)")
    print(f"[real] indexed path stats = {stats}")

    assert reference.keys() == fast.keys(), "state sets differ"
    mismatches = [key for key in reference if reference[key] != fast[key]]
    print(f"[real] mismatching states = {len(mismatches)}")
    assert not mismatches, mismatches[:5]

    # REPORT §8.2 的抽查点：prism 的 corner=0,positive_side=false,thickness=1,u_point=0,v_point=0 -> 0.125
    sample_tail = {"corner": "0", "positive_side": "false", "u_point": "0", "v_point": "0"}
    sample = dict(sample_tail, thickness="1")
    key = ("copyfoxies:copyfox_edge_cut_prism", tuple(sorted(sample.items())))
    print(f"[real] prism corner=0,positive_side=false,thickness=1,u_point=0,v_point=0 -> "
          f"{fast[key].get('sable:mass')} (expect 0.125)")
    assert fast[key].get("sable:mass") == 0.125

    thickness8 = [slot for k, slot in fast.items()
                  if k[0] == "copyfoxies:copyfox_edge_cut_prism"
                  and dict(k[1]).get("thickness") == "8"]
    masses = sorted({slot.get("sable:mass") for slot in thickness8})
    print(f"[real] prism thickness=8 group: {len(thickness8)} states, {len(masses)} distinct masses, "
          f"max={masses[-1]} (expect 1.0)")
    assert masses[-1] == 1.0

    # REPORT §8.2 的第二个抽查点：同坐标、thickness=8 -> 1.0
    sample8 = dict(sample_tail, thickness="8")
    key8 = ("copyfoxies:copyfox_edge_cut_prism", tuple(sorted(sample8.items())))
    print(f"[real] prism corner=0,positive_side=false,thickness=8,u_point=0,v_point=0 -> "
          f"{fast[key8].get('sable:mass')} (expect 1.0)")
    assert fast[key8].get("sable:mass") == 1.0

    distinct = len({slot.get("sable:mass") for slot in fast.values() if "sable:mass" in slot})
    print(f"[real] distinct sable:mass values across all blocks = {distinct}")

    # 索引路径的"逻辑工作量"：每状态 |signature| 次 getValue + 1 次哈希探测，加上建索引时每个条目
    # 一次值解析。跟 naive 的"每状态 × 每条目"对比，就是补丁省掉的量级。
    shape_report = []
    work = 0
    for definition in definitions:
        if not definition.overrides:
            continue
        signature = [name for name, _ in definition.overrides[0][1]]
        states = len(blocks[definition.selector].states())
        work += states * (len(signature) + 1) + sum(len(conditions) for _, conditions, _ in definition.overrides)
        shape_report.append(f"{definition.selector.split(':')[1]}={len(signature)}")
    print(f"[real] single key shape per definition: {', '.join(shape_report)}")
    print(f"[real] indexed per-dimension work = {work:,} ops "
          f"vs {naive_comparisons:,} linear comparisons -> {naive_comparisons / work:.0f}x fewer operations")
    print("[real] OK")


# --------------------------------------------------------------------------- 模糊测试


def fuzz(trials: int = 3000, seed: int = 20261119) -> None:
    rng = random.Random(seed)
    total_indexed = 0
    total_fallback = 0
    trials_with_fallback = 0
    total_states = 0
    for trial in range(trials):
        prop_pool = [f"p{i}" for i in range(rng.randint(1, 5))]
        block_count = rng.randint(1, 3)
        blocks: dict[str, Block] = {}
        for b in range(block_count):
            names = [n for n in prop_pool if rng.random() < 0.75] or [prop_pool[0]]
            domains = {n: [str(v) for v in range(rng.randint(1, 4))] for n in names}
            blocks[f"block{b}"] = Block(f"block{b}", domains)

        definitions: list[Definition] = []
        for _ in range(rng.randint(1, 4)):
            targets = [identity for identity in blocks if rng.random() < 0.6] or [rng.choice(list(blocks))]
            base = {} if rng.random() < 0.35 else {"sable:mass": rng.choice([0.5, 1.0, 2.0])}
            all_names = sorted({n for identity in targets for n in blocks[identity].domains})
            shape = rng.sample(all_names, rng.randint(0, len(all_names))) if all_names else []
            overrides = []
            for _ in range(rng.choice([0, 0, 1, 2, 5, 11])):
                if shape and rng.random() < 0.85:
                    names = shape                       # 单一形状：走索引路径
                else:
                    names = rng.sample(all_names, rng.randint(0, len(all_names))) if all_names else []
                conditions = []
                for name in names:
                    pool = sorted({v for identity in targets for v in blocks[identity].domains.get(name, ())})
                    if not pool:
                        pool = ["0"]
                    value = rng.choice(pool) if rng.random() < 0.9 else "bogus"     # 偶发非法值
                    conditions.append((name, value))
                key = ",".join(f"{n}={v}" for n, v in conditions)
                props = {} if rng.random() < 0.2 else {"sable:mass": rng.choice([0.25, 0.75, 3.0])}
                overrides.append((key, conditions, props))
            definitions.append(
                Definition(
                    selector=",".join(targets),
                    priority=rng.choice([500, 1000, 1000, 1500]),
                    base=base,
                    overrides=overrides,
                    blocks=targets,
                )
            )
        definitions.sort(key=lambda d: d.priority)

        reference, _ = naive(definitions, blocks)
        fast, _, stats = indexed(definitions, blocks)
        total_indexed += stats["indexed"]
        total_fallback += stats["fallback"]
        total_states += stats["states"]
        if stats["fallback"]:
            trials_with_fallback += 1
        if reference.keys() != fast.keys():
            raise AssertionError(f"trial {trial}: state sets differ")
        bad = [k for k in reference if reference[k] != fast[k]]
        if bad:
            raise AssertionError(f"trial {trial}: {len(bad)} mismatching states, e.g. {bad[0]}")
    print(f"[fuzz] {trials} random data sets: naive == indexed  (seed={seed}); "
          f"indexed definitions={total_indexed}, fallback definitions={total_fallback} "
          f"(in {trials_with_fallback} trials), states compared={total_states:,}")


if __name__ == "__main__":
    check_real()
    fuzz()
