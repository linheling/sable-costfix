# sable-costfix —— Sable `physics_block_properties` 应用阶段的索引化补丁

[![License: MIT](https://img.shields.io/badge/license-MIT-blue.svg)](LICENSE)

> **English TL;DR** — Sable Cost Fix replaces both of Sable 2.0.x's `physics_block_properties` apply paths
> (per-dimension `applyAll()` on the server, per-packet `applyToBlocks()` on the client) with a hash index,
> and skips re-applying an unchanged data set. With copyfoxies 2.2.0, world load drops from ~18.9 s per
> dimension plus ~12.9 s on join to **88 ms plus a few tens of milliseconds**, with byte-identical physics
> values. MC 1.21.1 / NeoForge 21.1, requires Sable 2.0.6–2.0.x, install on **both** client and server.
> Full write-up: [`docs/REPORT-zh.md`](docs/REPORT-zh.md) (Chinese) and the upstream issue draft
> [`docs/ISSUE-sable-en.md`](docs/ISSUE-sable-en.md) (English).

**一句话**：把 Sable 2.0.6 的两条应用路径（服务端每维度一次 `applyAll()`、客户端收包后逐条
`applyToBlocks()`）都从「状态数 × 覆盖条目数」的线性扫描换成哈希索引，并短路掉重复应用。

> **1.0.1 的重要修订**：1.0.0 只注入了 `applyAll()`。真机日志显示服务端从 18.8 s/维度降到 0.74 s，
> 但**客户端进档仍有一段 ~12.9 s 的 Render thread 空窗**——Sable 的 `ClientboundPhysicsPropertyPacket`
> 每条 definition 一个包，客户端收到后逐条调 `applyToBlocks()`，完全绕过 `applyAll()`。
> 1.0.1 补上第二个注入点。

数据侧现场（copyfoxies 2.2.0，`data/copyfoxies/physics_block_properties/`，15,325 条覆盖全枚举），
同一实例改动前后对比：

| 指标 | 上游 2.0.6 | 本补丁 1.0.1 |
|---|---|---|
| 服务端单维度 `applyAll()` | 18.9 s（日志实测 10.9 + 8.0 s 两段） | **88 ms**（200 definitions / 376,073 状态 / 15,958 条目 / ~4.08 亿次线性比较被避免） |
| 服务端 4 个维度 → `Preparing start region` | ~72 s | **743 ms** |
| 第 2 个维度起 | 再跑一遍 | 内容相等短路，0 ms |
| 客户端收包同步（Render thread 空窗） | 12.5 s | 待真机复测（1.0.0 时实测 12.9 s，未覆盖该路径） |

依据与根因见 [`docs/REPORT-zh.md`](docs/REPORT-zh.md) §6。

---

## 1. 它到底改了什么

一个 mixin 类、两个注入点，全部落在 `PhysicsBlockPropertiesDefinitionLoader`：

1. **`applyAll()`（服务端，每维度一次）** —— `@Inject(HEAD, cancellable)` → `FastApply.applyCached()`
   - 整批按索引应用；数据集与上次逐字段相等则整次空转（对应 REPORT §7 A2 + A1）。
2. **`applyToBlocks(definition)`（客户端 packet 路径 + 上游 fallback 的语义基准）**
   —— `@Inject(HEAD, cancellable)` → `FastApply.applyDefinition(definition)`
   - 单条 definition 走同一套索引；返回 false（键形状不一致）时不 cancel，放行上游。
3. **索引本体（对应 REPORT §7 A2）** —— `FastApply`
   - 对一个 definition 的全部 override 键取属性名序列（signature）；
   - 每个 `StateDefinition` 只解析一次期望值，装进 `Map<值元组, List<覆盖属性集>>`；
   - 遍历状态时算一次 `getValue` 元组 + 一次哈希探测，命中几条应用几条。
4. **异常兜底**：我们的实现抛任何 `Throwable` → 记 error 且**不 cancel**，上游原方法照常跑完
   （结果仍正确，只是慢）。**不会静默失效**。

`BlockState` 上的属性数组不直接写：`FastApply.applyTo()` 走 Sable 自己的
`BlockStateExtension.sable$loadProperties()`，只是喂一个 `overrides = Optional.empty()` 的合成
definition（record 的 canonical 构造器是 public），于是它只执行 `sable$applyPropertySet(properties)`。
这样连"数组惰性分配"这种细节都和上游一致，`/sablecostfix verify` 才能逐槽位比对。

## 2. 语义等价的五条依据

1. **definition 顺序**照抄 `definitions` 列表（上游 `apply()` 已按 priority 升序稳定排序），后续覆盖先前。
2. **同一 definition 内部**：先 `properties()` 基线，再按 `overrides()` 的**迭代顺序**应用命中的覆盖；
   桶内是 `ArrayList` 插入序，与上游 `for (Map.Entry … : overrides.entrySet())` 的顺序一致。
3. **部分键语义**：条件键形状（属性名序列）全一致时才建索引（本 case 11/11 都是单一形状）；
   一旦一个 definition 里混着多种形状，整条退回上游逐状态语义——宁可慢也不猜。
4. **不可解析的值**（`property.getValue(String)` 返回空）与**方块缺少该属性**两种情况，
   上游 `matches()` 都是 `false`，索引侧等价处理为"入桶前丢弃 / 整块无覆盖"。
5. **基线永远调一次**（哪怕 `properties()` 为空）：上游靠这次调用惰性分配 `Object[]`；少调一次会让
   "该状态从未被命中"与"被命中但写进去的全是默认值"在数组层面出现 `null` vs 全 `null` 的差异。

`tools/equiv_model.py` 用真实数据跑了两条路径并逐状态逐槽位比对（`python tools/equiv_model.py`）：

```
[real] 11 definition(s), 11 block(s), 15,325 override entr(y/ies), 15,325 state(s)
[real] naive comparisons  = 59,426,133  (REPORT says 59,426,133)
[real] indexed path stats = {'definitions': 11, 'indexed': 11, 'fallback': 0, 'states': 15325, ...}
[real] mismatching states = 0
[real] prism corner=0,positive_side=false,thickness=1,u_point=0,v_point=0 -> 0.125 (expect 0.125)
[real] prism corner=0,positive_side=false,thickness=8,u_point=0,v_point=0 -> 1.0 (expect 1.0)
[real] single key shape per definition: ...=3, ...=2, ...=5, ...=4
[real] indexed per-dimension work = 153,561 ops vs 59,426,133 linear comparisons -> 387x fewer operations
[fuzz] 3000 random data sets: naive == indexed  (seed=20261119); indexed definitions=2676,
       fallback definitions=2401 (in 1747 trials), states compared=178,931
```

模型里 naive 的比较次数与 REPORT §6.3 的 59,426,133 **逐位一致**——既复核了报告，也说明模型
没有偷换语义。模糊测试覆盖：部分键 / 空条件键 / 非法值 / 方块缺属性 / 一个 definition 多种键形状 /
多方块选择子 / priority 冲突。

## 3. 构建

```powershell
# 1) 自备上游 jar（不随仓库分发，Sable 是 PolyForm Shield；见 libs/README.md）
copy "<你的实例>\mods\sable-neoforge-1.21.1-2.0.6.jar" "libs\sable.jar"

# 2) 构建（首次会拉 NeoForge 21.1.249 / NeoForm，几分钟）
.\gradlew.bat build --console=plain
# 产物 build\libs\sablecostfix-1.0.1.jar（约 24 KB）
```

- 上游 Sable 是 `compileOnly`（`libs\sable.jar`，硬链接到实例里的
  `sable-neoforge-1.21.1-2.0.6.jar`）。**jar 内不含任何 Sable class**（`tools/check_jar.py` 会断言）。
- `neo_version=21.1.249`（本机已缓存的 NeoForm）；产物对实例的 21.1.265 运行时兼容。
- **坑**：`neoforge.mods.toml` / `gradle.properties` 一旦带上 UTF-8 BOM，FML 会报
  `...\build\classes\java\main is not a valid mod file` 并在启动阶段直接崩。用 `write` 之类无 BOM
  的方式改这两个文件（2026-11-19 踩过一次）。

## 4. 安装 / 回滚

**直接下载**：本仓库 [Releases](https://github.com/linheling/sable-costfix/releases) 有构建好的
`sablecostfix-1.0.1.jar`，扔进 mods 即可；自己构建的也可以用下面这行。

```powershell
copy build\libs\sablecostfix-1.0.1.jar "<你的实例>\mods\"
# 回滚：删掉那个 jar（若留了旧版 jar 记得一并删，同 modId 两份会撞）
```

客户端与服务端都要装（`applyAll` 在服务端线程跑，`applyToBlocks` 在客户端收包后跑）。

## 5. 依赖与失败模式

`neoforge.mods.toml` 声明 `sable` **required**、区间 `[2.0.6,2.1)`。上限锁死是刻意的：

- **注入失败即崩**（`injectors.defaultRequire: 1`）。Sable 改了 `applyAll` / `applyToBlocks` 的
  方法名或描述符时，游戏会在启动阶段直接报 mixin 注入失败，而不是"补丁悄悄失效、你还以为是别的原因"。
- **运行期数据异常不崩**：两个注入点都 catch `Throwable` → 记 error → 放行上游原实现。

## 6. 验收

### 6.1 dev 专用服冒烟测试（**已实测**，2026-11-19，1.0.1）

`./gradlew runServer` + `libs/sable.jar` 当 runtimeOnly 加载（Sable 2.0.6 + Veil 4.3.2 + companion
全在 dev 里跑起来了）：

```
# Sable 自带数据 + 两个冒烟数据包（redstone_wire 5 属性 1,296 条全枚举；oak_stairs 混形状）
sable-costfix: 21 definition(s) (18 indexed, 1 fell back), 21190 block state(s),
               1308 override entr(y/ies), ~1698907 linear comparison(s) avoided, 14.49 ms
Done (0.904s)!
```

RCON（`tools/rcon.py`，端口 25575 / 密码 `smoketest`）：

```
> sablecostfix status
sable-costfix: 21 definition(s), 12 with overrides (1311 entr(y/ies)); cache warm
  (next applyAll is a no-op), 2 applyAll call(s) skipped as unchanged; last: ...14.49 ms

> sablecostfix verify
sable-costfix: verify PASS — 11544 state(s) compared; original 0.10 s vs applyAll 0.007 s /
  packet 0.008 s; mismatches applyAll=0 packet=0
```

`verify` 的三次跑法：① 绕过注入的**上游原实现**；② `applyAll` 路径；③ 逐条 `applyToBlocks`
（packet 路径，命中注入点）。②③ 各自与 ① 逐槽位比对 → 两条路径都等价。

### 6.2 实例真机日志（1.0.0 会话，2026-11-19 19:11）

```
[Server thread/INFO] [dev.shiratori.sablecostfix.SableCostFix/]: sable-costfix: 200 definition(s)
  (198 indexed, 0 fell back), 376073 block state(s), 15958 override entr(y/ies),
  ~407857015 linear comparison(s) avoided, 88.29 ms
```

- `Creating physics pipeline (overworld)` 19:12:39.467 → `Preparing start region` 19:12:40.210
  = **743 ms**（同一实例未装补丁的会话：每维 10.8 + 8.0 s，4 维共 ~72 s）；
- the_end / the_nether / rift_corridor 三个维度的相邻间隔 620 / 54 / 27 ms，且**没有第二次**输出
  → 内容相等短路生效；
- `Failed to apply tag physics properties. Unknown block: copycats:copycat_catwalk` 从 6 条降为 1 条
  （日志来源变成 `dev.shiratori.sablecostfix.SableCostFix`，可与上游那条区分）；
- **未覆盖项（1.0.0 时）**：Render thread 在 19:12:44.226 → 19:12:57.100 有 **12.87 s** 静默，
  结束于 Sable 自己那条 catwalk 报错——即客户端 packet 路径的 `applyToBlocks` 线性扫描。
  同期未装补丁的会话是 12.47 s，一模一样 ⇒ 与补丁无关、也未被覆盖。1.0.1 的第二个注入点就是为它加的。

### 6.3 下次进档要看的

1. `sable-costfix: <N> definition(s) ... ~<C> linear comparison(s) avoided` 只出现 1 次，
   `<C>` ≥ 59,426,133，`Creating physics pipeline → Preparing start region` < 2 s；
2. **Render thread 上不再出现 ~13 s 静默**（进档后从"Sable と 繋がりました"之类到 JEI 启动之间，
   只剩 JEI 自己那段 ~9.6 s，这是本就有的）；
3. `/sablecostfix verify` → `PASS … original ~18 s vs applyAll ~0.1 s / packet ~0.2 s; mismatches 0/0`。

## 7. 已知边界

| 边界 | 说明 |
|---|---|
| 一个 definition 内多种条件键形状 | 该 definition 退回上游逐状态语义（正确但慢），日志里计入 `fell back`。已在 dev 冒烟测试里用混形状数据包实测该分支。 |
| 客户端 packet 路径不去重 | 每次进档都按包重跑一遍（索引路径，实测全量 ~几十 ms）。与上游一致：上游也是每包重跑。 |
| 只有维度初始化时才应用 | `/reload` 后已有维度的方块状态不会刷新，直到新建维度触发下一次 `applyAll()`（与上游一致）。缓存按内容比较，那一次会正确重跑。 |
| 数据侧无压缩空间 | 已由 REPORT §6.6 证明：5,184 条含 269 个不同质量值，5 个属性缺一不可。责任在消费方。 |

## 8. 代码地图

| 文件 | 作用 |
|---|---|
| `src/main/java/dev/shiratori/sablecostfix/FastApply.java` | 索引实现 + 内容相等缓存 + 单条应用 + 选择子解析 |
| `src/main/java/dev/shiratori/sablecostfix/mixin/PhysicsLoaderMixin.java` | `applyAll` / `applyToBlocks` 两个 HEAD 注入 |
| `src/main/java/dev/shiratori/sablecostfix/CostFixCommand.java` | `/sablecostfix status\|verify`（三路比对） |
| `src/main/java/dev/shiratori/sablecostfix/SableCostFix.java` | `@Mod` 入口，只注册命令 |
| `src/main/resources/sablecostfix.mixins.json` | `defaultRequire: 1`，required |
| `src/main/resources/META-INF/neoforge.mods.toml` | 依赖 sable `[2.0.6,2.1)`、`[[mixins]]` |
| `tools/equiv_model.py` | naive vs indexed 等价性模型 + 真实数据复算 + 模糊测试 |
| `tools/make_smoke_datapack.py` | 生成 dev 冒烟数据包（redstone_wire 全枚举 1,296 条；混形状样例在 `tools/fixtures/oak_stairs_mixed.json`） |
| `tools/rcon.py` | 最小 RCON 客户端，dev 冒烟测试里发 `/sablecostfix verify` |
| `tools/check_jar.py` | 产物自检：jar 内容 / mods.toml / mixins.json / 不得含上游 class |
| `tools/find_callers.py` | 在 Sable jar 里找 `applyToBlocks` / `applyAll` 的引用方（就是靠它定位客户端 packet 路径） |
| `run/smoke/datapacks/smoke-physics` | 冒烟数据包（redstone_wire 全枚举 + oak_stairs 混形状） |
