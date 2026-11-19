# copyfoxies 2.2.0 使存档加载 +109 s —— 根因与修复报告

| 字段 | 值 |
|---|---|
| 报告日期 | 2026-11-19（2026-11-19 19:30 补记客户端第二条入口） |
| 严重度 | 高（每次进档 / 每次开服固定损失 ~100 s 服务端 + ~13 s 客户端；服务端部分随维度数线性增长） |
| 责任方 | **Sable 2.0.6**（算法） × **copyfoxies 2.2.0**（数据体量），前者是必须修的一方 |
| 状态 | 根因已定位，经反编译 + 同实例 A/B 实测验证；上游未修复，本地补丁 1.0.1 已落地（§7 方案 B） |
| 结论可信度 | 高（对照实验 + 反编译源码 + 数据统计三方互证，无一项依赖猜测） |

---

## 1. TL;DR

`copyfoxies 2.2.0` 新增了 1.49 MB 的 `physics_block_properties` 逐状态质量表（15,325 条）。
Sable 2.0.6 在**每个维度**初始化时调用一次 `PhysicsBlockPropertiesDefinitionLoader.applyAll()`，该方法对每个方块做 `状态数 × 覆盖条目数` 的**双重线性扫描**：

- 单次 `applyAll()` 比较次数 = **59,426,133**
- 每次比较还要做 5 组 `getProperty(String)` 哈希查表 + 值字符串解析
- 每个存档有 6 个维度 ⇒ **~3.5 亿次**冗余比较

实测 **18.2 s / 维度 × 6 = ~109 s**。

> 关键点：`applyAll()` 写入的是 **`BlockState` 上的全局字段**，与维度无关。**第 2~6 次调用是 100% 冗余的纯浪费。**

> **第二个入口（2026-11-19 补记）**：客户端收到 `ClientboundPhysicsPropertyPacket`（一条 definition 一个包）
> 后逐条调用 `applyToBlocks(definition)`，**完全绕过 `applyAll()`**，所以"跑一次 + 缓存"对它无效。
> 同一实例实测：只修 `applyAll` 时服务端 4 个维度降到 743 ms，客户端仍有 **12.9 s** Render thread 空窗
> （未装补丁的对照组 12.5 s，一模一样）。⇒ 两条入口都要改，见 §5.4、§6.7、§7 A4。

---

## 2. 量级

| 指标 | 更新前（copyfoxies 2.0.0） | 更新后（2.2.0） | 倍数 |
|---|---|---|---|
| 建维度总耗时（前 4 维度） | **1.245 s** | **72.03 s** | **58×** |
| 单维度耗时 | 0.03 – 0.14 s | ~18.2 s | ~130× |
| 含 2 个测试维度的总计 | ~1.3 s | **~109 s** | ~84× |

服务端（专用服）同样命中：该调用在 `Server thread` 上、按维度执行，与客户端无关。

补充（2026-11-19 实测）：上表只算服务端。客户端另有一份**独立**开销——进档时 `OnDatapackSyncEvent`
把每条 definition 单独打成一个包发下来，客户端逐条 `applyToBlocks()`，实测 Render thread 空窗
**12.5 s**（未装补丁）/ **12.9 s**（只修了 `applyAll` 的本地补丁），与维度数无关。见 §5.4、§6.7。

---

## 3. 环境

| 项 | 值 |
|---|---|
| Minecraft | 1.21.1 |
| NeoForge | 21.1.265（FML 4.0.45） |
| JVM | Microsoft OpenJDK 25.0.4.1+1-LTS，`-Xmx8G`（G1） |
| 实例 | `D:\mc\PCL2\.minecraft\versions\1.21.1-NeoForge_21.1.265` |
| 存档 | `saves\新的世界`（6 维度：overworld / the_nether / the_end / `aerowarptics:rift_corridor` / `server_faucet_test_level` / `server_projectile_test_level`） |
| Sable | `sable-neoforge-1.21.1-2.0.6.jar` + `sable64bit-1.0.1.jar` |
| copyfoxies | `copyfoxies-2.2.0.jar`（更新自 2.0.0） |
| Copycats+ | `copycats-3.0.11+mc.1.21.1-neoforge` |

---

## 4. 复现

1. 存档 `新的世界`，装 copyfoxies **2.0.0** → 进档，记录 `logs\latest.log` 中首个 `Creating physics pipeline ... minecraft:overworld` 到 `Preparing start region for dimension minecraft:overworld` 的时间差 ⇒ **≈1.2 s**。
2. 换 copyfoxies **2.2.0**，其余一切不动 → 同一时间差 ⇒ **≈72 s**。
3. 无需改配置、无需重新生成地图、无需清缓存，**每次进档都复现**。

---

## 5. 证据

### 5.1 逐维度时间轴（`logs\latest.log`，2026-11-19 18:09 会话）

```
维度              管线创建      tag 告警      密度编译      小计
overworld        18:09:47.342  57.429 (+10.09)  18:10:05.371 (+7.94)  18.03 s
the_nether       18:10:05.745  15.308 (+9.56)   18:10:23.352 (+8.04)  17.61 s
the_end          18:10:23.401  33.436 (+10.04)  18:10:41.503 (+8.07)  18.10 s
rift_corridor    18:10:41.540  51.612 (+10.07)  18:10:59.339 (+7.73)  17.80 s
── Preparing start region 18:10:59.368 ──
faucet_test      18:11:00.015  09.867 (+9.85)   18:11:18.417 (+8.55)  18.40 s
projectile_test  18:11:18.417  29.220 (+10.80)  18:11:37.766 (+8.55)  19.35 s
```

`Creating physics pipeline` 由 Sable 打印，`Failed to apply tag physics properties` 由
`applyAll()` 内部打印。工作量**恰好被这条告警一分为二**：告警之前 ~10 s（该无效定义之前的
所有 definition），告警之后 ~8 s（其余 definition）。这与"按 definition 顺序跑完整个列表"
的模型完全一致。

### 5.2 对照：同一段时间差在旧版本

`logs\2026-11-19-7.log.gz`（16:38 会话，copyfoxies 2.0.0）：

```
16:38:12.842  Creating physics pipeline (overworld)
16:38:12.965  Failed to apply tag physics properties (+0.123 s)
16:38:13.111  Compiled density column              (+0.146 s)
16:38:14.087  Preparing start region              ← 4 个维度共 1.245 s
```

### 5.3 排除项

| 嫌疑 | 结论 | 依据 |
|---|---|---|
| `copycats:copycat_catwalk` 报错本身 | **无关（既有噪音）** | 旧日志同样 7 条，一字不差 |
| GC 停顿 | **无关** | 全程最长暂停 51 ms（`logs\gc.log`，148 次） |
| 维度数量增加 | **无关** | 新旧都是 6 维度，集合相同 |
| 磁盘 I/O / 网络超时 | **无关** | 10 s 窗口内 `debug.log` **零行输出**，纯 CPU 空转 |
| 存档膨胀 / 区块损坏 | **无关** | 同一存档前后对比，只有 copyfoxies 版本变化 |
| copyfoxies 版本号不一致告警 | **无关但可解释** | `The following mods have version differences ... copyfoxies (2.0.0 -> 2.2.0)`，是更新 mod 后的正常提示 |

### 5.4 本地补丁 A/B（2026-11-19，同一实例，两次会话）

为验证"哪一条入口在付费"，装了本地索引补丁后重跑，并与**同一实例、同一存档、未装补丁**的会话对照。
未装补丁那次的 `latest.log` 里留了一行 `sablecostfix (version 1.0.0 -> MISSING)`，可据此确认它是干净的对照组。

| 观测点 | 19:08 会话（无补丁） | 19:11 会话（只修 `applyAll` 的 1.0.0） |
|---|---|---|
| `Creating physics pipeline` → 下一维度 | 10.9 s + 8.0 s | 620 / 54 / 27 ms |
| 4 个维度 → `Preparing start region` | ~72 s | **743 ms** |
| 服务端 `applyAll()` 净耗时 | 每维度 ~18.9 s（18:09 那次实测 18.2 s，同量级） | **88.29 ms**（200 definitions / 376,073 状态 / 15,958 条目 / ~4.08 亿次线性比较被避免） |
| 客户端 Render thread 空窗 | **12.47 s** | **12.87 s（未变）** |
| `Failed to apply tag physics properties` | 6 条 | 2 条（服务端 1 + 客户端 1） |

结论：`applyAll()` 那一侧被压到约 1/215，**但客户端那 12.9 s 一点没动**——它不是 `applyAll()` 在跑，
而是 §6.7 的第二条入口。这段空窗在两次会话里的起止特征完全一致：结束于 Sable 自己那条
`Unknown block: copycats:copycat_catwalk`，即"逐条 `applyToBlocks` 正好跑到那条 definition"。

---

## 6. 根因

### 6.1 调用点：每维度一次（`dev.ryanhcode.sable.Sable`）

```java
@ApiStatus.Internal
public static void defaultSubLevelContainerInitializer(Level level, SubLevelContainer container) {
    if (container instanceof ServerSubLevelContainer) {
        ...
        serverContainer.addObserver(new SubLevelTicketLoadingSystem(serverContainer));
        PhysicsBlockPropertiesDefinitionLoader.INSTANCE.applyAll();   // ← 每个维度都跑
    }
}
```

### 6.2 双重循环

`dev/ryanhcode/sable/physics/config/block_properties/PhysicsBlockPropertiesDefinitionLoader`：

```java
public void applyAll() {
    for (PhysicsBlockPropertiesDefinition definition : this.definitions)
        applyToBlocks(definition);
}

public static void applyToBlocks(PhysicsBlockPropertiesDefinition definition) {
    ...
    for (Block block : blocks) {
        StateDefinition stateDefinition = block.getStateDefinition();
        for (BlockState state : stateDefinition.getPossibleStates())      // ← 外层：每个状态
            ((BlockStateExtension) state).sable$loadProperties(stateDefinition, definition);
    }
}
```

`dev/ryanhcode/sable/mixin/block_properties/BlockStateMixin`：

```java
public void sable$loadProperties(StateDefinition stateDefinition, BlockState state) {
    if (this.sable$properties == null)
        this.sable$properties = new Object[PhysicsBlockPropertyTypes.count()];
    this.sable$applyPropertySet(definition.properties());
    if (definition.overrides().isPresent()) {
        for (Map.Entry override : definition.overrides().get().entrySet()) {   // ← 内层：扫全部条目
            if (!((BlockStateConditionSet) override.getKey()).matches(stateDefinition, state))
                continue;
            this.sable$applyPropertySet((Map) override.getValue());
        }
    }
}
```

`BlockStateConditionSet.matches()` 每条判断代价：

```java
for (BlockStateCondition condition : this.blockStateConditions) {
    Property property = stateDefinition.getProperty(condition.property());   // HashMap<String,Property> 查表
    Comparable expectedValue = property.getValue(condition.value()).orElse(null);  // 每次重新解析字符串
    if (!state.getValue(property).equals(expectedValue)) return false;
}
```

⇒ `applyAll()` 复杂度 **O(Σ_definition 状态数 × 覆盖条目数 × 条件数)**。

### 6.3 数据量（`copyfoxies-2.2.0.jar` → `data/copyfoxies/physics_block_properties/`）

全部 11 个文件都是**按状态全枚举**（条目数 == 状态数）：

| 文件 | 条目 | 状态 | 比较次数 |
|---|---:|---:|---:|
| `copyfox_edge_cut_prism` | 5,184 | 5,184 | **26,873,856** |
| `copyfox_edge_cut_centered_prism` | 5,184 | 5,184 | **26,873,856** |
| `copyfox_dual_half_column` | 2,025 | 2,025 | 4,100,625 |
| `copyfox_variable_slope` | 648 | 648 | 419,904 |
| `copyfox_centered_variable_slope` | 648 | 648 | 419,904 |
| `copyfox_quarter_panel` | 625 | 625 | 390,625 |
| `copyfox_corner_variable_cube` | 512 | 512 | 262,144 |
| `copyfox_center_variable_cube` | 256 | 256 | 65,536 |
| 其余 3 个 | 81 ×3 | | 19,683 |
| **合计** | **15,325** | | **59,426,133** |

### 6.4 横向对比：为什么偏偏是 copyfoxies

全实例 `*/physics_block_properties/*.json` 数据量排名：

| Mod | 文件数 | 体积 |
|---|---:|---:|
| **copyfoxies-2.2.0** | 11 | **1,528.2 KB（90%）** |
| aviation-1.8.0 | 12 | 146.0 KB |
| create_atmospherics | 40 | 6.1 KB |
| Copycats+ 3.0.11 | 23 | **5.0 KB** |
| 其余 12 个 mod | — | 合计 ~18 KB |
| 总计 | | 1,698.4 KB |

Copycats+ 本体 23 个文件只有 5 KB（只写 `properties` 默认值，不写 `overrides`），所以它的方块几乎零成本。

### 6.5 成本模型可复算

```
冗余比较数 = Σ_i ( 状态数_i × 覆盖条目数_i ) × 维度数
          = 59,426,133 × 6
          ≈ 3.56 亿次 matches()
每次 matches ≈ 5 × (String→Property 哈希 + 值字符串解析 + 比较)
实测 ≈ 18.2 s / 维度  ⇒  ≈ 3.3 M 次比较/秒
```

若只跑一次（6→1），预期 **109 s → 18 s**；若再加预索引，预期 **→ 数十毫秒**（见 §7）。

### 6.6 为什么"数据侧压缩"不可行（已证）

对 `copyfox_edge_cut_prism` 做了最小决定性属性子集搜索（`corner / positive_side / thickness / u_point / v_point` 共 5 个属性）：

| 被删除的属性 | 归并后键数 | 冲突数 |
|---|---:|---:|
| `corner` | 1,296 | 2,528 |
| `positive_side` | 2,592 | 2,528 |
| `thickness` | 648 | 4,088 |
| `u_point` | 576 | 4,352 |
| `v_point` | 576 | 4,352 |

任意 1~3 个子集都无法决定质量（5,184 条里含 **269 个互不相同的质量值**）。
⇒ **这张表是真正的逐状态数据，不是可以塌成规则的冗余。责任在消费方（Sable），不在数据方。**

### 6.7 第二个入口：客户端逐包应用（2026-11-19 补记）

`applyAll()` 不是唯一入口。Sable 在数据包同步时把**每一条 definition 各自打成一个包**发给玩家，
客户端收到后逐条调用 `applyToBlocks(definition)`：

`dev.ryanhcode.sable.neoforge.SableNeoForge`（`OnDatapackSyncEvent` → 每个相关玩家）：

```java
private void syncDataPack(OnDatapackSyncEvent event) {
    SableCommonEvents.syncDataPacket(
            packet -> event.getRelevantPlayers().forEach(player -> player.connection.send(packet)));
}
```

`dev.ryanhcode.sable.SableCommonEvents`：

```java
public static void syncDataPacket(VeilPacketManager.PacketSink sink) {
    // 一条 definition 一个包 —— 不是整批一个包
    sink.sendPacket(PhysicsBlockPropertiesDefinitionLoader.INSTANCE.getDefinitions().stream()
            .map(ClientboundPhysicsPropertyPacket::new).toArray(CustomPacketPayload[]::new));
    ...
}
```

`dev.ryanhcode.sable.network.packets.tcp.ClientboundPhysicsPropertyPacket`：

```java
public void handle(PacketContext context) {
    Minecraft.getInstance().execute(
            () -> PhysicsBlockPropertiesDefinitionLoader.applyToBlocks(this.definition));  // 客户端线程，逐条线性扫描
}
```

三条推论：

1. **`applyAll()` 侧的"只跑一次 + 缓存"对它完全无效**——它根本不经过 `applyAll()`；
2. **触发频率**是"每次数据包同步"，即每次玩家进档（以及 `/reload`），与维度数无关；
3. 它跑在**客户端主线程**（Render thread），与 §5.1 里服务端那 18 s 是两份独立开销，会叠加。

实测（§5.4）：只修 `applyAll` 时服务端 4 维 743 ms，客户端仍有 12.87 s 空窗；未装补丁的对照组
12.47 s。两者几乎相同 ⇒ 这段耗时与 `applyAll` 无关，只与"逐条 `applyToBlocks`"有关。

> 附注：`syncDataPacket` 还会顺带发 `DimensionPhysicsData` 与 `ClientboundFloatingBlockMaterialPacket`，
> 但只有 physics 属性包是逐 definition 的，且接收端每条都会触发一次全表扫描。

---

## 7. 修复方案

### 方案 A：修 Sable（推荐，根治）

#### A1 —— 全局只跑一次（1 行级改动，收益 6×）

`applyAll()` 的产物写在 `BlockState` 的全局字段上（`BlockStateMixin.sable$properties`），
**与维度无关**，因此从第 2 个维度起完全是重复劳动。

在 `PhysicsBlockPropertiesDefinitionLoader` 内加一次性标志：

```java
private boolean sable$applied = false;

public void applyAll() {
    if (this.sable$applied) return;
    this.sable$applied = true;
    for (PhysicsBlockPropertiesDefinition definition : this.definitions)
        applyToBlocks(definition);
}
```

**必须处理的重载语义**：`apply(Map<ResourceLocation, JsonElement>, ...)` 在数据包重载时会
`definitions.clear()` 并重新填充，此时必须把标志复位（在 `apply()` 里 `this.sable$applied = false;`），
否则数据包改动后质量表不会刷新。这是 A1 唯一的正确性陷阱。

#### A2 —— 预索引（核心改动，收益 ~1,000×）

把"每个状态扫全部条目"反过来，改成"每个条目登记一次，每个状态查一次表"：

1. 对每个 definition，取所有 override key 用到的属性集合 `P_key`。
2. 建立一个 `Map<StateKey, List<OverrideEntry>>`：`StateKey` = 该状态在 `P_key` 上的取值元组。
3. 遍历 `getPossibleStates()`，为每个状态算一次 `StateKey`，取桶，**只对该桶内的条目**做
   `matches()`（保留"多条 partial key 可同时命中"的原语义）。
4. 缓存 `Map<BlockState, Object[]>` 结果，使重复 `applyAll()` 变为 O(1)。

复杂度：**O(状态数 × 条件数 + 条目数)**，本 case 从 59.4 M 降到 ~1.5 万。

> 注：本实例中所有 key 都是"全属性绑定"，第 3 步桶内平均 1 条，退化为一次哈希查找。

#### A3 —— 顺手修掉噪音（可选）

`applyToBlocks()` 对未注册方块的报错（`copycats:copycat_catwalk`）每个维度重复 6 次；
既然 A1 已把调用收敛为一次，这条噪音自然只剩 1 条。**不建议**为了消掉它去删 Copycats+ 的数据文件。

#### A4 —— 两条入口都要盖（否则只治一半）

只改 `applyAll()`（A1 + A2）能把服务端从 ~18.9 s/维度压到 743 ms（§5.4 实测），但客户端那 12.9 s
一分不减：它的入口是 `applyToBlocks(definition)`（§6.7）。两种收口方式：

- **推荐**：把索引实现放进 `applyToBlocks()` 内部，`applyAll()` 只剩"循环调用它"——两条入口天然
  共享同一套索引，也不需要各自维护缓存；
- 或者保留 `applyAll()` 的整批短路，同时再给 `applyToBlocks()` 套一层单条索引
  （实现上就是把 A2 的逻辑抽成 `applyDefinition(definition)` 供两边调用）。

客户端不需要额外同步任何东西：索引结果只依赖 `StateDefinition` 与两边的方块注册表，两端本就一致。

**成本量级**：即使 200 条 definition 每条各建一次索引，客户端侧总量也只有几十毫秒
（本地补丁实测：11,544 个状态，逐条 `applyToBlocks` 0.008 s vs 上游原实现 0.10 s，见 §7 方案 B）。

---

### 方案 B：本地补丁 mod（不改上游，可立即落地）

针对 `PhysicsBlockPropertiesDefinitionLoader` 写 mixin：

```java
@Mixin(PhysicsBlockPropertiesDefinitionLoader.class)
public abstract class PhysicsLoaderMixin {
    @Unique private static boolean costfix$applied = false;

    // 止血版：只跑一次
    @Inject(method = "applyAll", at = @At("HEAD"), cancellable = true)
    private void costfix$once(CallbackInfo ci) {
        if (costfix$applied) { ci.cancel(); return; }
        costfix$applied = true;
    }
}
```

- **止血版 ≈ 12 行**：109 s → 18 s。风险极低，因为 `applyAll()` 幂等。
- **根治版**：在上面基础上 `@Cancel` 掉原方法体，改调自写的预索引实现（需 `@Shadow` 拿
  `definitions` 字段，或 `@Accessor`）。约 100 行。
- 同样需要在数据包重载时复位标志（可 `@Inject` 到 `apply(...)` 的 HEAD）。

**已知风险**：`definitions` 是私有字段、`PhysicsBlockPropertiesDefinition` 是 record 且带
`Optional<Map<BlockStateConditionSet, ...>>`，跨版本易碎。补丁 mod 应锁定
`sable (>=2.0.6, <2.1)` 并在加载失败时降级为"不注入 + 打 WARN"，绝不能静默禁用。

**已落地（2026-11-19，v1.0.1）**：`D:\C\MC\mods\projects\sable-costfix`（modId `sablecostfix`）
按"根治版 + 双入口"实现，且**不再用一次性布尔标志**——改为比较数据集内容是否相等，
`/reload` 换数据后自动重跑，天然绕开 A1 的复位陷阱：

| 项 | 值 |
|---|---|
| 注入点 | `applyAll()`（整批 + 内容相等短路）与 `applyToBlocks(definition)`（客户端 packet 路径），均 `@Inject(HEAD, cancellable)`、`remap = false` |
| 索引 | 值元组 → 覆盖集 的 `HashMap`；条件键形状不一致时该条退回上游逐状态语义 |
| 写路径 | 合成 `overrides = Optional.empty()` 的 definition 喂 `sable$loadProperties()`，不碰私有数组 |
| 失败模式 | 注入失败即崩（`require = 1`）；运行期异常 → 记 error + 放行上游，不静默 |
| 依赖 | `sable [2.0.6,2.1)`，compileOnly，jar 内不含任何上游 class |
| 实测（dev 专用服） | 21 definitions / 1,308 条目 / 14.49 ms；三路 `verify`：原 0.10 s vs applyAll 0.007 s / packet 0.008 s，mismatches 0/0 |
| 实测（实例） | 服务端 4 维 743 ms（对照 72 s）；200 definitions / 376,073 状态 / 15,958 条目 / ~4.08 亿次比较被避免 / 88.29 ms |

---

### 方案 C：临时止血（不改任何代码）

| 手段 | 收益 | 代价 |
|---|---|---|
| 回退 copyfoxies 至 2.0.0 | 109 s → ~1.3 s | 失去 2.2.0 的新方块（该 mod 只在 CurseForge，Modrinth project 404） |
| 从 jar 删掉两个 `edge_cut_prism` 的 physics json（各 548 KB） | 109 s → ~15 s | 这两个方块在 Sable 载具上质量回落默认值；其余 9 个方块不受影响 |
| 继续用 2.2.0 不处理 | — | 每次进档 +109 s，专用服开服同样 |

---

### 方案 D：修 copyfoxies —— **不可行（已证）**

见 §6.6。表是逐状态真数据。若作者愿意配合，唯一能做的是**把 `overrides` 换成更少条件的部分键**，
但 §6.6 已证明 5 个属性缺一不可，因此**没有无损压缩空间**。此路关闭。

---

## 8. 验收标准

修完必须同时满足：

1. **耗时**：`Creating physics pipeline (overworld)` → `Preparing start region` 的差值
   < 2 s（基线旧版 1.2 s；现状 72 s）。逐维度相邻 `Creating physics pipeline` 间隔 < 1 s（本地补丁实测 620 / 54 / 27 ms，未装补丁时每段 ~18.9 s）。
2. **数值不变**：抽查 `copyfox_edge_cut_prism` 的
   `corner=0,positive_side=false,thickness=1,u_point=0,v_point=0` 状态，`sable:mass` 必须仍为 `0.125`；
   同坐标 `thickness=8` 必须仍为 `1.0`。
   （注意：`thickness=8` 那一组共 648 个状态、61 个不同质量值，"全组都是 1.0"是错的断言；
   正确写法是"该组最大值仍为 1.0"。严格做法是逐槽位比对整个属性数组，而不是抽查单个值。）
3. **幂等**：跑完整 6 个维度后的结果，与只跑 1 次的结果逐位相同。
4. **重载正确**：改数据包 / `/reload` 后，新增或修改的 `physics_block_properties` 必须生效（验证 A1 的复位逻辑）。
5. **无回归**：使用**部分条件键**的 mod 数据仍然生效 —— 用 Copycats+ 的 23 个文件（5 KB）与
   aviation 的 146 KB 做覆盖测试。
6. **日志**：`Failed to apply tag physics properties` 从 6 条（服务端每维度一条）降为每端 1 条（服务端 + 客户端共 2 条，属预期变化，不是新错误）。
7. **客户端**（2026-11-19 追加）：进档过程中 Render thread **不再出现 ~12.9 s 静默**；
   长度 > 1 s 的空窗应与 `applyToBlocks` 无关（基线：同一实例未装补丁时该空窗 12.5 s，
   只修 `applyAll` 时 12.9 s）。等价性可用"逐条 `applyToBlocks` 重跑后与上游参考跑逐槽位比对"
   来验证（本地补丁的 `/sablecostfix verify` 是这么做的：11,544 状态 / 0 mismatch）。

---

## 9. 附录 A：复算命令（PowerShell，可直接跑）

统计每个 mod 的 physics 数据体量：

```powershell
Add-Type -AssemblyName System.IO.Compression.FileSystem
foreach ($j in Get-ChildItem '.\mods' -Filter *.jar) {
    try { $z = [IO.Compression.ZipFile]::OpenRead($j.FullName) } catch { continue }
    $f = $z.Entries | Where-Object { $_.FullName -like '*/physics_block_properties/*.json' }
    if ($f) { '{0,-60} {1,4} {2,8:N1} KB' -f $j.Name, $f.Count, (($f | Measure-Object Length -Sum).Sum / 1KB) }
    $z.Dispose()
}
```

单文件条目数 / 键空间：

```powershell
$e = $z.Entries | Where-Object { $_.FullName -eq 'data/copyfoxies/physics_block_properties/copyfox_edge_cut_prism.json' }
$sr = New-Object IO.StreamReader($e.Open()); $t = $sr.ReadToEnd(); $sr.Close()
([regex]::Matches($t, '[a-z_]+=') | Measure-Object).Count     # 25,920 = 5,184 条 × 5 属性
```

日志侧对照（取 pipeline → start region 的差值）：

```powershell
Select-String -Path .\logs\latest.log -Encoding UTF8 -Pattern 'Creating physics pipeline|Preparing start region|Failed to apply tag physics'
```

找某个方法在 jar 里的全部引用方（§6.7 就是靠这一步定位到客户端 packet 路径的）：

```powershell
$needle = 'applyToBlocks'
Add-Type -AssemblyName System.IO.Compression.FileSystem
$z = [IO.Compression.ZipFile]::OpenRead('D:\mc\PCL2\.minecraft\versions\1.21.1-NeoForge_21.1.265\mods\sable-neoforge-1.21.1-2.0.6.jar')
foreach ($e in $z.Entries) {
    if ($e.FullName -notlike '*.class') { continue }
    $ms = New-Object IO.MemoryStream; $s = $e.Open(); $s.CopyTo($ms); $s.Close()
    if ([Text.Encoding]::ASCII.GetString($ms.ToArray()).Contains($needle)) { $e.FullName }
}
$z.Dispose()
# 同上，换成 'ClientboundPhysicsPropertyPacket' 可以找到发包方
```

---

## 10. 附录 B：原始日志摘录

```
[1911月2026 18:09:47.342] [Server thread/INFO] [dev.ryanhcode.sable.Sable/]: Creating physics pipeline for ResourceKey[minecraft:dimension / minecraft:overworld] using RapierPhysicsPipelineProvider
[1911月2026 18:09:47.369] [Server thread/INFO] [dev.ryanhcode.sable.Sable/]: Using game directory relative Sable Rapier native directory ...\.sable\natives
[1911月2026 18:09:47.435] [Server thread/INFO] [com.sable.patch64_bit.Sable64bit/]: Loaded Sable64 f64 Rapier native: ...\.sable64bit\natives\sable_rapier_x86_64_windows.dll
[1911月2026 18:09:57.429] [Server thread/ERROR] [dev.ryanhcode.sable.Sable/]: Failed to apply tag physics properties. Unknown block: copycats:copycat_catwalk
[1911月2026 18:10:05.371] [Server thread/INFO] [ByePregen Density Column Compiler/]: Compiled density column: ...
...
[1911月2026 18:10:59.368] [Server thread/INFO] [net.minecraft.server.MinecraftServer/]: Preparing start region for dimension minecraft:overworld
```

对照旧版（同存档）：

```
[1911月2026 16:38:12.842] [Server thread/INFO] [dev.ryanhcode.sable.Sable/]: Creating physics pipeline for ResourceKey[minecraft:dimension / minecraft:overworld] ...
[1911月2026 16:38:12.934] [Server thread/INFO] [com.sable.patch64_bit.Sable64bit/]: Loaded Sable64 f64 Rapier native: ...
[1911月2026 16:38:12.965] [Server thread/ERROR] [dev.ryanhcode.sable.Sable/]: Failed to apply tag physics properties. Unknown block: copycats:copycat_catwalk
[1911月2026 16:38:13.111] [Server thread/INFO] [ByePregen Density Column Compiler/]: Compiled density column: ...
[1911月2026 16:38:14.087] [Server thread/INFO] [net.minecraft.server.MinecraftServer/]: Preparing start region for dimension minecraft:overworld
```

---

## 11. 反编译证据（可直接核对）

> **2026-11-19 22:45 注**：`D:\C\MC\mods\tmp\*` 下的反编译树已随工作区清理删除（`reports/` 目录同样
> 已删，**本仓库的 `docs/` 是这份报告与英文 issue 的唯一副本**）。下表保留类名与行号作为引用锚点，
> 重出方法见 §9：输入 jar 在实例 `mods/sable-neoforge-1.21.1-2.0.6.jar`，反编译器在工作区
> `tools/cfr.jar`（或 `vf.jar`）。文件名里的 `tmp\sable_*_src` 是当时的输出目录，按同样的目录名重出即可对上。

| 文件 | 位置 | 内容 |
|---|---|---|
| `D:\C\MC\mods\tmp\sable_bp_src\dev\ryanhcode\sable\physics\config\block_properties\PhysicsBlockPropertiesDefinitionLoader.java` | L96–123 | `applyToBlocks`：状态 × 条目双重循环 |
| 同上 | L125–129 | `applyAll()` |
| `D:\C\MC\mods\tmp\sable_bs_src\dev\ryanhcode\sable\mixin\block_properties\BlockStateMixin.java` | L41–52 | `sable$loadProperties`：内层线性扫 |
| `D:\C\MC\mods\tmp\sable_bp_src\dev\ryanhcode\sable\physics\config\block_properties\BlockStateConditionSet.java` | L56–70 | `matches()`：每条件重复查表 + 解析字符串 |
| `D:\C\MC\mods\tmp\sable_core_src\dev\ryanhcode\sable\Sable.java` | L80–95 | `defaultSubLevelContainerInitializer`，L93 调 `applyAll()` |
| `D:\C\MC\mods\tmp\sable_neoforge_src\dev\ryanhcode\sable\neoforge\SableNeoForge.java` | L96–98 | `OnDatapackSyncEvent` → `SableCommonEvents.syncDataPacket(...)` |
| `D:\C\MC\mods\tmp\sable_events_src\dev\ryanhcode\sable\SableCommonEvents.java` | L108–112 | `syncDataPacket`：一条 definition 打一个包 |
| `D:\C\MC\mods\tmp\sable_packet_src\dev\ryanhcode\sable\network\packets\tcp\ClientboundPhysicsPropertyPacket.java` | L33–35 | `handle()`：客户端 `Minecraft.execute(() -> applyToBlocks(definition))` |

复现反编译：

```powershell
# 先 extract 成 jar，直接传目录给 cfr 会 FileNotFoundException 拒绝访问
java -jar D:\C\MC\mods\tools\cfr.jar <单类打成的jar> --outputdir <输出目录>
```
