# 1.0.1 — cover the client packet path

Sable applies `physics_block_properties` from **two** entry points; 1.0.0 only patched the first one.

| entry point | who calls it | 1.0.0 | 1.0.1 |
|---|---|---|---|
| `applyAll()` | server, once per dimension | hash-indexed | hash-indexed + content short-circuit |
| `applyToBlocks(definition)` | client, once per packet (`ClientboundPhysicsPropertyPacket`) | untouched | hash-indexed |

Measured on a 6-dimension save with copyfoxies 2.2.0 (15,325 per-state override entries):

- **server**: 18.9 s per dimension → **88 ms** (200 definitions / 376,073 block states / ~408 M linear
  comparisons avoided); 4 dimensions to `Preparing start region` 72 s → **743 ms**
- **client join**: ~12.9 s render-thread stall → tens of ms (measured on the dev server; in-game
  re-measurement still pending — see the warning below)
- **values**: byte-identical, verified slot by slot against an unpatched reference run

## Install

Drop the jar into `mods/` on **both** client and server. Requires Sable `2.0.6`–`2.0.x`, MC 1.21.1 /
NeoForge 21.1. No config, no world data — delete the jar to uninstall.

## Verify it yourself

`/sablecostfix verify` (op level 2) runs Sable's original implementation once as a reference, re-runs both
indexed paths, and compares every block state's property array slot by slot:

```
sable-costfix: verify PASS — N state(s) compared; original X s vs applyAll Y ms / packet Z ms; mismatches applyAll=0 packet=0
```

Full write-up: [docs/REPORT-zh.md](docs/REPORT-zh.md) (Chinese),
[docs/ISSUE-sable-en.md](docs/ISSUE-sable-en.md) (English upstream issue draft).

---

⚠ **Honest status**: 1.0.0 was validated in-game for the server path only (that is where the 72 s → 743 ms
number comes from). 1.0.1 adds the client path and passes the dev-server equivalence test (11,544 states,
0 mismatches, `/sablecostfix verify` three-way), but the "client join no longer stalls ~13 s" claim still
awaits one real client launch. If you hit anything, the `sable-costfix:` lines in `logs/latest.log` plus
`/sablecostfix status` are enough to diagnose.
