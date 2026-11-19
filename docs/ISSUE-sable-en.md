# `applyAll()` is O(states × overrides) and re-runs per dimension; `applyToBlocks()` re-runs per packet on the client

## Summary

`PhysicsBlockPropertiesDefinitionLoader.applyAll()` is invoked once **per dimension** from
`Sable.defaultSubLevelContainerInitializer(...)`, but it writes to global `BlockState` fields, so
dimensions 2..N are pure redundant work.

Worse, `applyToBlocks()` does a nested scan: for **every** possible `BlockState` of a block it
linearly walks **every** entry of `definition.overrides()`, calling
`BlockStateConditionSet.matches()`. With a data mod that ships exhaustive per-state override
tables, this becomes O(states × overrides) and dominates world load.

Measured on a 6-dimension save: **~18.2 s per dimension, ~109 s total**, versus **1.2 s** before
the data mod was updated (58× on the 4 dimensions before `Preparing start region`).

There is a **second entry point** that bypasses all of that: on datapack sync the server sends **one
`ClientboundPhysicsPropertyPacket` per definition**, and the client handles each one with
`Minecraft.getInstance().execute(() -> applyToBlocks(definition))` on its main thread. Patching only
`applyAll()` leaves this path untouched — in the same instance the server went from 18.9 s/dimension to
**743 ms** for 4 dimensions, while the client still froze for **12.9 s** on join (baseline without any
patch: 12.5 s). So the fix has to cover `applyToBlocks()` itself, not just `applyAll()`.

---

## Environment

- Minecraft 1.21.1, NeoForge 21.1.265 (FML 4.0.45)
- `sable-neoforge-1.21.1-2.0.6.jar` + `sable64bit-1.0.1.jar`
- JDK 25.0.4.1, G1, `-Xmx8G`
- Data mod providing the large tables: `copyfoxies` 2.2.0 (`data/copyfoxies/physics_block_properties/`)

---

## Reproduction

1. Load a save with 6 dimensions (Overworld, Nether, End, plus 3 modded dimensions).
2. Watch the delta between `Creating physics pipeline for ResourceKey[.../minecraft:overworld]`
   and `Preparing start region for dimension minecraft:overworld`.

Observed timeline (`logs/latest.log`):

```
overworld        18:09:47.342  ->  18:10:05.371   (18.03 s)  tag-error +10.09 s, then +7.94 s
the_nether       18:10:05.745  ->  18:10:23.352   (17.61 s)  tag-error  +9.56 s, then +8.04 s
the_end          18:10:23.401  ->  18:10:41.503   (18.10 s)  tag-error +10.04 s, then +8.07 s
rift_corridor    18:10:41.540  ->  18:10:59.339   (17.80 s)  tag-error +10.07 s, then +7.73 s
-- Preparing start region 18:10:59.368 --
faucet_test      18:11:00.015  ->  18:11:18.417   (18.40 s)
projectile_test  18:11:18.417  ->  18:11:37.766   (19.35 s)
```

The `Failed to apply tag physics properties. Unknown block: copycats:copycat_catwalk` line splits
each block in two — it is emitted mid-list while iterating definitions, which confirms the cost is
spread across the whole `applyAll()` pass rather than in one bad definition.

Control run, same save, before the data mod update:

```
16:38:12.842 Creating physics pipeline (overworld)
16:38:12.965 Failed to apply tag physics properties   (+0.123 s)
16:38:13.111 Compiled density column                  (+0.146 s)
16:38:14.087 Preparing start region                   (4 dimensions in 1.245 s)
```

Ruled out: GC (longest pause 51 ms over the session), extra dimensions (same 6 before/after),
disk/network (zero log lines from any thread inside the 10 s windows — pure CPU spin).

---

## Root cause

### Call site — once per dimension

`dev.ryanhcode.sable.Sable`

```java
@ApiStatus.Internal
public static void defaultSubLevelContainerInitializer(Level level, SubLevelContainer container) {
    if (container instanceof ServerSubLevelContainer) {
        ...
        serverContainer.addObserver(new SubLevelTicketLoadingSystem(serverContainer));
        PhysicsBlockPropertiesDefinitionLoader.INSTANCE.applyAll();   // per dimension
    }
}
```

### Call site — once per definition, on the client

`dev.ryanhcode.sable.neoforge.SableNeoForge`

```java
private void syncDataPack(OnDatapackSyncEvent event) {
    SableCommonEvents.syncDataPacket(
            packet -> event.getRelevantPlayers().forEach(player -> player.connection.send(packet)));
}
```

`dev.ryanhcode.sable.SableCommonEvents`

```java
public static void syncDataPacket(VeilPacketManager.PacketSink sink) {
    // one packet per definition, not one batch
    sink.sendPacket(PhysicsBlockPropertiesDefinitionLoader.INSTANCE.getDefinitions().stream()
            .map(ClientboundPhysicsPropertyPacket::new).toArray(CustomPacketPayload[]::new));
    ...
}
```

`dev.ryanhcode.sable.network.packets.tcp.ClientboundPhysicsPropertyPacket`

```java
public void handle(PacketContext context) {
    Minecraft.getInstance().execute(
            () -> PhysicsBlockPropertiesDefinitionLoader.applyToBlocks(this.definition));
}
```

So every join (and every `/reload`) re-runs the whole nested scan on the client main thread, once per
definition, with no guard at all — and it stacks with the server-side cost instead of replacing it.

Measured client-side (render thread silence between the last join log line and the first physics-property
log line, same instance and save):

| build | client stall on join |
|---|---|
| unpatched | 12.5 s |
| only `applyAll()` patched (server: 18.9 s/dim → 743 ms for 4 dims) | **12.9 s** |

### Nested scan

`dev.ryanhcode.sable.physics.config.block_properties.PhysicsBlockPropertiesDefinitionLoader`

```java
public void applyAll() {
    for (PhysicsBlockPropertiesDefinition definition : this.definitions)
        applyToBlocks(definition);
}

public static void applyToBlocks(PhysicsBlockPropertiesDefinition definition) {
    ...
    for (Block block : blocks) {
        StateDefinition stateDefinition = block.getStateDefinition();
        for (BlockState state : stateDefinition.getPossibleStates())            // states
            ((BlockStateExtension) state).sable$loadProperties(stateDefinition, definition);
    }
}
```

`dev.ryanhcode.sable.mixin.block_properties.BlockStateMixin`

```java
public void sable$loadProperties(StateDefinition stateDefinition, BlockState state) {
    if (this.sable$properties == null)
        this.sable$properties = new Object[PhysicsBlockPropertyTypes.count()];
    this.sable$applyPropertySet(definition.properties());
    if (definition.overrides().isPresent()) {
        for (Map.Entry override : definition.overrides().get().entrySet()) {     // overrides
            if (!((BlockStateConditionSet) override.getKey()).matches(stateDefinition, state))
                continue;
            this.sable$applyPropertySet((Map) override.getValue());
        }
    }
}
```

`BlockStateConditionSet.matches()` additionally re-does a `StateDefinition.getProperty(String)`
lookup and a **string parse** (`property.getValue(String)`) for every condition, on every
comparison — so the constant factor is not small.

### Cost with the offending data

11 files, all exhaustive (entry count == state count):

| file | entries | states | comparisons |
|---|---:|---:|---:|
| `copyfox_edge_cut_prism` | 5,184 | 5,184 | 26,873,856 |
| `copyfox_edge_cut_centered_prism` | 5,184 | 5,184 | 26,873,856 |
| `copyfox_dual_half_column` | 2,025 | 2,025 | 4,100,625 |
| 5 more | 2,741 | — | 1,503,059 |
| 3 more | 243 | — | 19,683 |
| **total** | **15,325** | | **59,426,133 per dimension** |

× 6 dimensions ≈ **356 M** `matches()` calls per world load. The client pays the same table again on
each join, one definition at a time (see the second call site above), which is where the extra ~13 s of
render-thread stall comes from.

Note: for completeness, this data set is 90% of all `physics_block_properties` bytes in the
instance (1,528 KB of 1,698 KB; the next largest mod is 146 KB). **The data mod is not doing
anything wrong** — I verified there is no lossless compression: for the prism block, every one of
the 5 properties genuinely affects the resulting mass (dropping any single property produces
2,528–4,352 conflicting states), and the 5,184 entries carry 269 distinct mass values. So the fix
belongs on the consumer side.

---

## Proposed fix

### 1. Run once, not once per dimension (large, easy win)

`applyAll()` writes to `BlockState.sable$properties`, which is global and dimension-independent,
so guard it:

```java
private boolean sable$applied = false;

public void applyAll() {
    if (this.sable$applied) return;
    this.sable$applied = true;
    for (PhysicsBlockPropertiesDefinition definition : this.definitions)
        applyToBlocks(definition);
}
```

**Correctness trap:** `apply(Map<ResourceLocation, JsonElement>, ResourceManager, ProfilerFiller)`
clears and repopulates `definitions` on every datapack reload. The flag must be reset there
(`this.sable$applied = false;`) or edited `physics_block_properties` will stop taking effect after
`/reload`, world creation, or a resource-pack change. A cheap robust variant is to invalidate
whenever `definitions` is repopulated, or to key the cache on a reload generation counter.

This alone: **109 s → ~18 s**.

### 2. Index instead of scan (root fix)

Invert the loops: register each override entry once, then look up per state.

1. Collect the property set `P` used by the definition's override keys.
2. Build `Map<StateKey, List<Entry>>` where `StateKey` is the tuple of a state's values on `P`.
3. For each state, compute `StateKey` once, fetch the bucket, and only run `matches()` on entries in
   that bucket — this preserves the existing "multiple partial keys may match one state" semantics.
4. Optionally cache the merged `Map<BlockState, Object[]>` so later applications are O(1) (makes
   fix 1 belt-and-braces rather than required).

Complexity becomes **O(states × conditions + entries)** — here 59.4 M → ~15 K.
Combined with fix 1, expected world load contribution drops to the tens of milliseconds.

### 3. Cover `applyToBlocks()` itself (otherwise the client is unaffected)

Fixes 1 and 2 live in `applyAll()`, but the client never calls it — it calls `applyToBlocks(definition)`
once per packet (second call site above). Suggested shape: put the indexed implementation **inside**
`applyToBlocks()` and let `applyAll()` be a plain loop over it. Both entry points then share one
implementation, the "already applied" cache becomes an optional optimisation instead of load-bearing
logic, and the per-dimension and per-packet paths cannot drift apart. With one definition per call this
costs about 8 ms for the whole table in our local harness (11,544 states), versus 0.10 s for the
unpatched scan.

---

## Verification

- `Creating physics pipeline (overworld)` → `Preparing start region` delta < 2 s (was 72 s).
- Adjacent dimensions < 1 s apart (measured 620 / 54 / 27 ms; was ~18.9 s per dimension).
- Mass values unchanged, e.g. `copyfox_edge_cut_prism` state
  `corner=0,positive_side=false,thickness=1,u_point=0,v_point=0` still `sable:mass = 0.125`, and the
  same coordinates with `thickness=8` still `1.0` (the maximum over the whole `thickness=8` group must
  stay `1.0`; that group has 648 states / 61 distinct masses, so compare the array, not just one value).
- Idempotent: after all 6 dimensions, identical to a single application.
- Datapack reload still updates properties (either reset the "already applied" state on
  `apply(...)`, or compare dataset contents instead of using a one-shot flag).
- Definitions using **partial** condition keys still apply — test with Copycats+ (23 files, 5 KB)
  and another data mod with partial keys.
- `Failed to apply tag physics properties` goes from 6 lines (one per dimension, server) to 1 per side — expected, not a new error.
- **Client**: no ~13 s render-thread stall while joining (baseline 12.5 s unpatched, 12.9 s with fixes
  1+2 only). Equivalence for the per-definition path: reset the per-state property arrays, run
  `applyToBlocks()` once per definition, and compare slot by slot against an unpatched reference run
  (our harness: 200 definitions / 11,544 states / 0 mismatches).
- The client re-applies on every datapack sync, so the indexed path must stay cheap **per definition**,
  not just per batch.

---

## Notes

Happy to submit a PR for all three fixes (say the word if you want fix 3 shaped differently — moving the
index into `applyToBlocks()` is my preferred shape, since it makes the per-dimension and per-packet paths
one code path).

The related `Unknown block: copycats:copycat_catwalk` log is unrelated noise: `Copycats+` ships
`data/copycats/physics_block_properties/copycat_catwalk.json` for a block id that is not in its
registry, and it is logged identically on versions where load is fast. With fix 1 it simply goes from six
lines to one per session.

For reference, a local patch mod implementing fixes 2+3 this way (compileOnly against sable 2.0.6, both
`applyAll()` and `applyToBlocks()` intercepted, per-state arrays compared slot by slot against an
unpatched reference run) measured: 200 definitions / 376,073 states / 15,958 override entries applied in
**88 ms** on the server (was ~18.9 s per dimension), and the client path at ~8 ms for the whole table
instead of 12.9 s.
