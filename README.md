# Special Guards

Special Guards are custom guard variants defined in JSON data files. They spawn naturally in villages (based on weight), or can be summoned with a command. Each definition can customize equipment, inventory, attributes, behavior, loot, and more.

## File Location

Place files under:
```
data/<namespace>/guardvillagers/special_guards/<name>.json
```

The filename (without `.json`) becomes part of the guard's type ID: `<namespace>:<name>`.

**Example:** `data/mymod/guardvillagers/special_guards/elite_archer.json` → type `mymod:elite_archer`

---

## Summon Command

```
/guardvillagers special summon <namespace>:<name>
/guardvillagers special list
```

Requires permission level 2 (operator).

---

## Spell Commands

Grant and remove spells on a living guard at runtime. Same permission level 2.

```
/guardvillagers spell grant  <guard> <spell_id> [passive_only]
/guardvillagers spell revoke <guard> <spell_id>
/guardvillagers spell clear  <guard>
/guardvillagers spell list   <guard>
```

`grant` validates the spell against the Spell Engine registry and refuses unknown IDs. Spell IDs
tab-complete; `revoke` only suggests spells actually granted to that guard.

`list` prints every spell the guard knows with its category and source, so granted spells can be told
apart from ones coming from equipment:

```
[Guard #482] Spells (3):
  wizards:fire_meteor    ACTIVE   PROJECTILE  GRANTED
  paladins:holy_shock    ACTIVE   PROJECTILE  MAINHAND
  druids:barkskin        PASSIVE  PASSIVE_DEFENSE  ARMOR
```

Granting or revoking triggers an immediate spell-manager refresh, and revoking a spell mid-cast
interrupts that cast.

---

## Minimal Example

```json
{
  "spawn": {
    "weight": 50
  },
  "equipment": {
    "mainhand": { "id": "minecraft:iron_sword", "count": 1 }
  }
}
```

---

## Full Schema Reference

### Top-level fields

| Field | Type | Description |
|---|---|---|
| `required_mod` | `string` | If set, this guard only loads when the named mod is present (e.g. `"wizards"`). |
| `spawn` | object | Controls how and where the guard spawns. |
| `display` | object | Visual customization (name, model variant). |
| `behavior` | object | AI and interaction flags. |
| `loot` | object | Death loot configuration. |
| `equipment` | object | Armor and hand items. |
| `inventory` | array | Items placed in internal inventory slots (0–5) or the spell slot (slot 6). |
| `spells` | array | Spells granted directly to the guard, with no item required. Additive over equipment. |
| `attributes` | object | Entity attribute overrides. |
| `config_overrides` | object | Per-guard overrides of global config values. |
| `entity_data` | object | Arbitrary NBT-like key/value data stored on the guard. |
| `command_tags` | array | Command tags added to the guard on spawn (queryable with `@e[tag=...]`). |

---

### `spawn`

```json
"spawn": {
  "weight": 50,
  "max_per_village": 1,
  "biomes": ["#minecraft:is_overworld", "minecraft:plains"],
  "dimensions": ["minecraft:overworld"],
  "structures": ["#minecraft:village", "ctov:large/village_plains"],
  "structure_search_radius": 32
}
```

| Field | Type | Default | Description |
|---|---|---|---|
| `weight` | int | `1` | Relative spawn weight. Higher = more common. Set to `0` to disable natural spawning (summon-only). |
| `max_per_village` | int | none | Maximum number of this type allowed near a village (proximity-based, 96 block radius). Omit for no limit. |
| `biomes` | array of strings | any | List of biome IDs or tags (e.g. `"#minecraft:is_overworld"`) where this guard can spawn. Omit to allow all biomes. |
| `dimensions` | array of strings | any | List of dimension IDs (e.g. `"minecraft:overworld"`) where this guard can spawn. Omit to allow all dimensions. |
| `structures` | array of strings | any | List of structure IDs or structure tags. The guard only spawns if its position is **inside** one of these structures. Omit to allow anywhere. |
| `structure_search_radius` | int | `0` | Horizontal radius (blocks) to probe outward when testing `structures`. `0` tests only the exact spawn position. |

#### `structures`

Restricts the guard to positions inside a matching generated structure. Accepts both exact IDs and tags:

```json
"structures": ["#minecraft:village"]
"structures": ["lios_outlandish_villages:hobbit_village"]
"structures": ["ctov:large/village_plains", "ctov:medium/village_plains"]
```

A leading `#` marks a structure tag (e.g. `"#minecraft:village"`, `"#ctov:large_village"`). Anything else
is treated as an exact structure ID. Unknown IDs never match — if a mod isn't loaded, that entry is simply
inert rather than an error.

The test asks whether the position lies within the structure's bounding box, so it is true anywhere in the
village's footprint, not merely near its center.

**`structure_search_radius`** exists because a guard can spawn just outside the structure bounds — at the
edge of a village's farmland, for example. With a non-zero radius the check probes outward on a 16-block
grid and matches if any probe lands inside the structure. Keep it small; each probe is a structure lookup.

| Value | Effect |
|---|---|
| `0` (default) | Strict — the guard must stand inside the structure box. |
| `16`–`32` | Tolerant of village-edge spawns. Recommended when using `#minecraft:village`. |
| `64`+ | Loose. Can match a neighbouring structure; prefer `biomes` for broad control. |

Filters combine with **AND** across categories and **OR** within one category. So `biomes` + `structures`
both have to pass, while any single entry inside `structures` satisfies that category.

---

### `display`

```json
"display": {
  "name": "&6Fire Mage &eGuard",
  "name_visible": true,
  "variant": 1
}
```

| Field | Type | Default | Description |
|---|---|---|---|
| `name` | string | none | Custom display name. Supports `&` color codes (e.g. `&a` = green, `&l` = bold). Omit for the default "Guard" name. |
| `name_visible` | bool | `true` | Whether the name tag is visible above the guard's head. |
| `variant` | int | random | Model skin variant. `0` = villager model variant A, `1` = B, etc. Omit to use a random variant. |

**Color code reference:** `&0`–`&9` digits, `&a`–`&f` hex letters, `&k` obfuscated, `&l` bold, `&m` strikethrough, `&n` underline, `&o` italic, `&r` reset.

---

### `behavior`

```json
"behavior": {
  "hireable": true,
  "hero_of_the_village": false,
  "follow_hero": true,
  "patrolling": true,
  "following": false,
  "block_gui": false,
  "lock_equipment": false,
  "immutable_equipment": false,
  "attack_players": false,
  "attack_mobs": ["minecraft:cow", "minecraft:pig"]
}
```

| Field | Type | Default | Description |
|---|---|---|---|
| `hireable` | bool | global config | Whether players can hire this guard. Overrides the global `allowHiring` config. |
| `hero_of_the_village` | bool | `false` | If `true`, the guard receives Hero of the Village treatment (special equipment, patrol behavior). |
| `follow_hero` | bool | global config | Whether this guard follows the Hero of the Village. |
| `patrolling` | bool | default | Whether the guard patrols its village. |
| `following` | bool | default | Whether the guard actively follows its owner when hired. |
| `block_gui` | bool | `false` | If `true`, the guard's inventory screen cannot be opened by non-owners. If not hired, nobody can open it. |
| `lock_equipment` | bool | `false` | If `true`, the GUI opens normally but all equipment/weapon/spell slots are read-only — no one can insert or remove items. |
| `immutable_equipment` | bool | `false` | If `true`, the guard's equipped items take no durability damage in combat. |
| `attack_players` | bool | `false` | If `true`, the guard will attack players on sight (same rules as hostile mobs — protected allies like the owner are still exempt). |
| `attack_mobs` | array of strings | none | List of entity type IDs this guard will proactively attack in addition to normal hostile mobs (e.g. `["minecraft:cow", "minecraft:pig"]`). Does not override the blacklist. |

---

### `hiring`

Overrides the global hiring item and cost for this specific guard type.

```json
"hiring": {
  "item": "minecraft:diamond",
  "cost": 3
}
```

| Field | Type | Default | Description |
|---|---|---|---|
| `item` | string | global config (`hiringItem`) | The item ID required to hire this guard (e.g. `"minecraft:gold_ingot"`). |
| `cost` | int | global config (`hiringItemCount`) | How many of the hiring item are consumed on hire. Minimum 1. |

Both fields are optional — omit either to fall back to the global config value.

---

### `loot`

```json
"loot": {
  "use_tables": false,
  "drop_chance": 0.25,
  "death_loot_table": "mymod:entities/elite_archer",
  "death_drops": [
    { "id": "minecraft:arrow", "count": 16 },
    {
      "chance": 0.5,
      "stack": { "id": "minecraft:diamond", "count": 1 }
    }
  ]
}
```

| Field | Type | Default | Description |
|---|---|---|---|
| `use_tables` | bool | `true` | If `true`, the guard uses the standard loot-table system for equipment drops. If `false`, only the items in `equipment`/`inventory` are considered, and `drop_chance` applies. |
| `drop_chance` | float `0.0–1.0` | global config | Per-slot chance for each equipped item to drop on death when `use_tables` is `false`. |
| `death_loot_table` | string | none | Identifier of a loot table file to roll on death (e.g. `"mymod:entities/guard_drops"`). Only fires if the guard is **not** hired. |
| `death_drops` | array | none | Inline list of items to drop on death. Each entry is an [item config](#item-config). Chance rolls apply at spawn time. Does not fire if the guard is hired. |

`death_loot_table` and `death_drops` can be used together — both will fire on death.

---

### `equipment`

Sets armor and hand items. Each slot value is an [item config](#item-config) (can be a direct item, a weighted choices array, or a `"chance"`-gated item).

```json
"equipment": {
  "head":      { "id": "minecraft:iron_helmet",     "count": 1 },
  "chest":     { "id": "minecraft:iron_chestplate", "count": 1 },
  "legs":      { "id": "minecraft:iron_leggings",   "count": 1 },
  "feet":      { "id": "minecraft:iron_boots",      "count": 1 },
  "mainhand":  { "id": "minecraft:iron_sword",      "count": 1 },
  "offhand":   { "id": "minecraft:shield",          "count": 1 }
}
```

**Slot names:** `head` / `helmet`, `chest` / `chestplate`, `legs` / `leggings`, `feet` / `boots`, `mainhand` / `main_hand`, `offhand` / `off_hand`.

If `use_tables: true` (the default), equipment set here is applied **after** the loot table runs, overriding those slots.
If `use_tables: false`, equipment set here is the only source of items for those slots.

---

### `inventory`

An array of slot entries. Each entry places an item into a specific internal inventory slot.

```json
"inventory": [
  {
    "slot": 4,
    "stack": { "id": "minecraft:shield", "count": 1 }
  },
  {
    "slot": 4,
    "chance": 0.5,
    "stack": { "id": "minecraft:shield", "count": 1 }
  },
  {
    "spell_slot": true,
    "stack": {
      "id": "spell_engine:spell_scroll",
      "count": 1,
      "components": {
        "spell_engine:spell_container": { "spell_ids": ["wizards:fire_meteor"] },
        "spell_engine:item_model": "wizards:item/spell_scroll/fire",
        "minecraft:rarity": "rare"
      }
    }
  }
]
```

| Field | Type | Description |
|---|---|---|
| `slot` | int | Internal slot index: `0`=head, `1`=chest, `2`=legs, `3`=feet, `4`=offhand, `5`=mainhand, `6`=spell slot. |
| `spell_slot` | bool | Shorthand for `"slot": 6` (the spell scroll slot). Use this for clarity. |
| `stack` or `item` | object | The item to place (supports full Minecraft item NBT/components). |
| `chance` | float `0.0–1.0` | Probability this item is placed. Roll happens at spawn. |
| `choices` | array | Weighted random pick — see [choices](#choices-array). |

---

### `spells`

Grants spells to the guard directly, with no scroll, spell book, or weapon required. Additive — these
stack on top of whatever the guard's equipment already provides, and they survive gear changes.

```json
"spells": [
  "wizards:fire_meteor",
  "paladins:flash_heal",
  { "id": "druids:barkskin", "passive_only": true },
  { "id": "bards_rpg:song_of_celerity", "chance": 0.5 }
]
```

Entries are either a plain spell ID string, or an object:

| Field | Type | Default | Description |
|---|---|---|---|
| `id` | string | required | Spell ID. Must exist in the Spell Engine registry, or the entry is skipped with a warning. |
| `passive_only` | bool | `false` | Categorize as a passive even if the spell would normally be active. |
| `chance` | float `0.0–1.0` | `1.0` | Probability the spell is granted. Rolled once at spawn, like `inventory` and `death_drops`. |

Granted spells appear with source `GRANTED` in the spell manager, and are stored on the guard as NBT
under `GrantedSpells`, so they persist across saves.

**Duplicates are ignored.** If a granted spell is already provided by the guard's gear, the gear-derived
entry wins and the grant is skipped for that category — the spell will not be double-counted when the
guard picks what to cast.

There is deliberately **no per-grant tier field**. Spell tier is a property of the spell definition
itself (`Spell.tier`), and the "best castable spell" logic reads it from the registry, so a per-grant
override would be stored but never honored.

---

### `attributes`

Overrides base entity attribute values. Uses standard Minecraft attribute IDs.

```json
"attributes": {
  "minecraft:generic.max_health":      40.0,
  "minecraft:generic.movement_speed":  0.42,
  "minecraft:generic.attack_damage":   6.0,
  "minecraft:generic.follow_range":    32.0,
  "minecraft:generic.armor":           8.0,
  "minecraft:generic.armor_toughness": 2.0,
  "minecraft:generic.knockback_resistance": 0.5
}
```

---

### `config_overrides`

Overrides global GuardVillagers config values for this guard only. All keys are optional.

```json
"config_overrides": {
  "spellSlotScrollChance":           0.75,
  "spellSlotScrollMaxTier":          4,
  "healthModifier":                  40.0,
  "speedModifier":                   0.48,
  "followRangeModifier":             28.0,
  "amountOfHealthRegenerated":       2.0,
  "reputationRequirement":           0,
  "reputationRequirementToBeAttacked": -10000,
  "reputationLostOnAttack":          0,
  "hiringItemCount":                 3,
  "chanceToDropEquipment":           0.0,
  "allowHiring":                     true,
  "followHero":                      true,
  "giveGuardStuffHotv":              true,
  "setGuardPatrolHotv":              true
}
```

| Key | Type | Global default | Description |
|---|---|---|---|
| `spellSlotScrollChance` | float | `0.25` | Chance (0–1) that a random scroll is placed in the spell slot on spawn (when no explicit `spell_slot` inventory entry is set). |
| `spellSlotScrollMaxTier` | int | `3` | Maximum tier of scroll that can appear in the spell slot. |
| `healthModifier` | double | `20.0` | Base max health (overridden by `attributes` if both are set). |
| `speedModifier` | double | `0.5` | Base movement speed. |
| `followRangeModifier` | double | `20.0` | Base follow/detection range. |
| `amountOfHealthRegenerated` | float | `1.0` | HP regenerated per regen tick. |
| `reputationRequirement` | int | `15` | Minimum village reputation required for the player to interact with this guard. |
| `reputationRequirementToBeAttacked` | int | `-100` | Reputation threshold below which the guard attacks the player. |
| `reputationLostOnAttack` | int | `25` | Village reputation lost when a player attacks this guard. |
| `hiringItemCount` | int | `5` | Number of items required to hire this guard. |
| `chanceToDropEquipment` | float | `0.0` | Per-slot drop chance for equipment on death (when `use_tables: false`). Alias for `loot.drop_chance`. |
| `allowHiring` | bool | `true` | Whether this guard can be hired. Use `behavior.hireable` instead for clarity. |
| `followHero` | bool | `true` | Whether this guard follows the Hero of the Village player. |
| `giveGuardStuffHotv` | bool | `false` | If true, the Hero of the Village event gives this guard extra items. |
| `setGuardPatrolHotv` | bool | `false` | If true, the Hero of the Village event puts this guard in patrol mode. |

---

### `entity_data`

Stores arbitrary key/value pairs as NBT on the guard. Supported types: boolean, number, string.

```json
"entity_data": {
  "Persistent": true,
  "MyCustomFlag": false,
  "ThreatLevel": 3,
  "Label": "alpha"
}
```

`"Persistent": true` is the standard way to prevent the guard from despawning.

---

### `command_tags`

Adds Minecraft command tags to the guard. Useful for targeting with `@e[tag=...]` in commands or functions.

The tags `guardvillagers:special_guard` and `guardvillagers:special_guard/<namespace>/<name>` are always added automatically.

```json
"command_tags": [
  "mymod:elite",
  "mymod:archer"
]
```

---

## Item Config

Items can be specified in three ways anywhere an item is expected (`equipment` slots, `inventory` entries, `death_drops`):

### 1. Direct item

```json
{ "id": "minecraft:iron_sword", "count": 1 }
```

With components (enchantments, custom name, etc.):
```json
{
  "id": "minecraft:iron_sword",
  "count": 1,
  "components": {
    "minecraft:enchantments": {
      "levels": { "minecraft:sharpness": 3 }
    },
    "minecraft:custom_name": "{\"text\":\"Guardian Blade\",\"color\":\"aqua\",\"italic\":false}"
  }
}
```

### 2. Chance-gated item

Item only appears with the given probability (roll at spawn). 0.0 = never, 1.0 = always.

```json
{
  "chance": 0.5,
  "stack": { "id": "minecraft:diamond", "count": 1 }
}
```

### 3. Choices array (weighted random)

One entry is picked randomly based on weight. Include `{ "empty": true }` for a "nothing" outcome.

```json
[
  { "weight": 3, "stack": { "id": "minecraft:iron_sword",    "count": 1 } },
  { "weight": 1, "stack": { "id": "minecraft:diamond_sword", "count": 1 } },
  { "weight": 1, "empty": true }
]
```

Or as an object with `"choices"`:
```json
{
  "choices": [
    { "weight": 2, "stack": { "id": "minecraft:crossbow", "count": 1 } },
    { "weight": 1, "stack": { "id": "minecraft:bow",      "count": 1 } }
  ]
}
```

---

## Complete Examples

### Vanilla-compatible Knight (no mod required)

```json
{
  "spawn": {
    "weight": 40,
    "max_per_village": 2,
    "dimensions": ["minecraft:overworld"]
  },
  "display": {
    "name": "&7Iron Knight",
    "name_visible": true,
    "variant": 0
  },
  "behavior": {
    "hireable": true,
    "follow_hero": true
  },
  "loot": {
    "use_tables": false,
    "drop_chance": 0.1
  },
  "equipment": {
    "head":    { "id": "minecraft:iron_helmet",     "count": 1 },
    "chest":   { "id": "minecraft:iron_chestplate", "count": 1 },
    "legs":    { "id": "minecraft:iron_leggings",   "count": 1 },
    "feet":    { "id": "minecraft:iron_boots",      "count": 1 },
    "mainhand": [
      { "weight": 3, "stack": { "id": "minecraft:iron_sword",  "count": 1 } },
      { "weight": 1, "stack": { "id": "minecraft:iron_axe",    "count": 1 } }
    ],
    "offhand": { "id": "minecraft:shield", "count": 1 }
  },
  "attributes": {
    "minecraft:generic.max_health":     35.0,
    "minecraft:generic.movement_speed": 0.44,
    "minecraft:generic.attack_damage":  5.0
  },
  "config_overrides": {
    "reputationRequirement": 0,
    "hiringItemCount": 4
  },
  "entity_data": {
    "Persistent": true
  },
  "command_tags": ["mymod:knight"]
}
```

---

### Fire Mage (requires Wizards mod)

```json
{
  "required_mod": "wizards",
  "spawn": {
    "weight": 30,
    "max_per_village": 1,
    "dimensions": ["minecraft:overworld"]
  },
  "display": {
    "name": "&c&lFire Mage",
    "name_visible": true
  },
  "behavior": {
    "hireable": true,
    "follow_hero": true
  },
  "loot": {
    "use_tables": false,
    "drop_chance": 0.25,
    "death_drops": [
      { "id": "minecraft:blaze_powder", "count": 4 },
      {
        "chance": 0.3,
        "stack": { "id": "minecraft:fire_charge", "count": 1 }
      }
    ]
  },
  "equipment": {
    "mainhand": { "id": "wizards:wand_fire", "count": 1 }
  },
  "inventory": [
    {
      "spell_slot": true,
      "choices": [
        { "weight": 3, "stack": {
            "id": "spell_engine:spell_scroll",
            "count": 1,
            "components": {
              "spell_engine:spell_container": { "spell_ids": ["wizards:fire_meteor"] },
              "spell_engine:item_model": "wizards:item/spell_scroll/fire",
              "minecraft:rarity": "rare"
            }
          }
        },
        { "weight": 1, "stack": {
            "id": "spell_engine:spell_scroll",
            "count": 1,
            "components": {
              "spell_engine:spell_container": { "spell_ids": ["wizards:flame_burst"] },
              "spell_engine:item_model": "wizards:item/spell_scroll/fire"
            }
          }
        }
      ]
    }
  ],
  "attributes": {
    "minecraft:generic.max_health":     30.0,
    "minecraft:generic.movement_speed": 0.42
  },
  "config_overrides": {
    "spellSlotScrollChance": 0.75,
    "spellSlotScrollMaxTier": 4,
    "amountOfHealthRegenerated": 2.0
  },
  "entity_data": { "Persistent": true },
  "command_tags": ["mymod:fire_mage"]
}
```

---

### Hostile Captain (block_gui, non-hireable, summon-only)

```json
{
  "spawn": {
    "weight": 0
  },
  "display": {
    "name": "&4&lVillage Captain",
    "name_visible": true,
    "variant": 0
  },
  "behavior": {
    "hireable": false,
    "follow_hero": false,
    "patrolling": false,
    "block_gui": true
  },
  "loot": {
    "use_tables": false,
    "drop_chance": 0.0,
    "death_drops": [
      { "id": "minecraft:emerald", "count": 3 },
      {
        "chance": 0.15,
        "stack": {
          "id": "minecraft:diamond_sword",
          "count": 1,
          "components": {
            "minecraft:enchantments": { "levels": { "minecraft:sharpness": 2 } }
          }
        }
      }
    ]
  },
  "equipment": {
    "head":    {
      "id": "minecraft:iron_helmet",
      "count": 1,
      "components": {
        "minecraft:enchantments": { "levels": { "minecraft:protection": 3 } }
      }
    },
    "chest":   { "id": "minecraft:iron_chestplate", "count": 1 },
    "legs":    { "id": "minecraft:iron_leggings",   "count": 1 },
    "feet":    { "id": "minecraft:iron_boots",      "count": 1 },
    "mainhand": { "id": "minecraft:diamond_sword",  "count": 1 },
    "offhand":  { "id": "minecraft:shield",         "count": 1 }
  },
  "attributes": {
    "minecraft:generic.max_health":     60.0,
    "minecraft:generic.attack_damage":  8.0,
    "minecraft:generic.movement_speed": 0.46,
    "minecraft:generic.armor":          12.0
  },
  "config_overrides": {
    "reputationRequirement": 0,
    "reputationRequirementToBeAttacked": -10000,
    "reputationLostOnAttack": 100
  },
  "entity_data": { "Persistent": true },
  "command_tags": ["mymod:captain"]
}
```

---

## Notes

- `required_mod`: If the mod is not loaded, the entire JSON file is silently skipped at startup.
- `use_tables: false` is required for `equipment` overrides to be fully applied (otherwise the guard's equipment first comes from loot tables, then overrides are layered on top per-slot).
- `death_drops` items are rolled at **spawn time** (chance gates apply once when the guard is created). Items that pass the roll are stored on the guard and dropped when it dies (only for non-hired guards).
- `death_loot_table` rolls at **death time** (same as vanilla loot tables).
- The spell slot (`spell_slot: true` / `slot: 6`) is only functional when a Spell Engine–based spell mod is present.
- Attribute values in `attributes` set the **base value** of the attribute, overwriting defaults. Values in `config_overrides` for `healthModifier`/`speedModifier`/`followRangeModifier` affect the same underlying values and are applied before `attributes`, so prefer `attributes` for precision.

---

# Armor Themes

Armor themes let you define which armor set a guard wears based on its main-hand weapon. The system is fully datapackable — you can add new mappings or override existing ones without touching any code.

## How it works

When a guard spawns, the following sequence runs:

1. **Loot tables** fill all equipment slots randomly (`guard_helmet.json`, `guard_main_hand.json`, etc.). Generic guards (iron sword, bow, etc.) keep this vanilla armor as their final look.
2. **Armor themes** check the resolved main-hand weapon. If a theme matches, it overwrites the armor slots with the themed items. This only fires for slots that the loot table already filled — an empty slot stays empty.
3. **Special guard overrides** (if applicable) run last and overwrite everything. Armor themes do not affect special guards.

Guards with no matching theme (e.g. plain iron sword) are unaffected and keep their loot-table armor.

## File Location

```
data/<namespace>/guardvillagers/guard_armor_themes/<name>.json
```

Files from all loaded namespaces are merged together. Entries are evaluated in load order — first match wins.

## Schema

Each file contains a `"themes"` array. Each entry has match conditions and a result:

```json
{
  "themes": [
    {
      "match_items":        ["namespace:item_id"],
      "match_namespaces":   ["namespace"],
      "match_path_contains": ["keyword"],
      "match_item_tags":    ["tag_namespace:tag_path"],
      "prefix": "namespace:armor_set_"
    }
  ]
}
```

### Match conditions

All conditions present in an entry are **ANDed** — every non-empty list must produce at least one match. At least one condition must be provided.

| Field | Description |
|---|---|
| `match_items` | Exact item IDs (e.g. `"druids:wand_nature"`). Matches if the weapon's full ID equals any entry. |
| `match_namespaces` | Item namespace (e.g. `"wizards"`). Matches if the weapon's namespace equals any entry. |
| `match_path_contains` | Substring of the item path (e.g. `"wand_fire"`). Matches if the path contains any entry. |
| `match_item_tags` | Item tag IDs (e.g. `"rpg_series:archetype/healing_weapon"`). Matches if the weapon is in any listed tag. Leading `#` is optional. |

### Result

Specify each armor slot explicitly. Any slot can be omitted to leave it unchanged (keeps whatever the loot table gave). The armor items can be from a completely different mod than the weapon.

```json
{
  "match_namespaces": ["spellbladenext"],
  "match_path_contains": ["frost"],
  "head":  "brimstone-battlemages:ashen_battlemage_head",
  "chest": "brimstone-battlemages:ashen_battlemage_chest",
  "legs":  "brimstone-battlemages:ashen_battlemage_legs",
  "feet":  "brimstone-battlemages:ashen_battlemage_feet"
}
```

## Examples

### Mod-specific wand → robe set

```json
{
  "match_namespaces": ["wizards"],
  "match_path_contains": ["wand_fire"],
  "head":  "wizards:fire_robe_head",
  "chest": "wizards:fire_robe_chest",
  "legs":  "wizards:fire_robe_legs",
  "feet":  "wizards:fire_robe_feet"
}
```

Matches `wizards:wand_fire` (namespace = wizards AND path contains "wand_fire").

### Specific items → shared armor set

```json
{
  "match_items": ["rogues:iron_dagger", "rogues:iron_sickle"],
  "head":  "rogues:rogue_armor_head",
  "chest": "rogues:rogue_armor_chest",
  "legs":  "rogues:rogue_armor_legs",
  "feet":  "rogues:rogue_armor_feet"
}
```

### Tag-based match (paladins healing vs melee split)

```json
{
  "match_namespaces": ["paladins"],
  "match_item_tags": ["rpg_series:archetype/healing_weapon"],
  "head":  "paladins:priest_robe_head",
  "chest": "paladins:priest_robe_chest",
  "legs":  "paladins:priest_robe_legs",
  "feet":  "paladins:priest_robe_feet"
}
```

Both conditions must match: item must be from the `paladins` namespace AND be in the `healing_weapon` tag.

### Weapon from one mod, armor from another

```json
{
  "match_namespaces": ["spellbladenext"],
  "match_path_contains": ["arcane", "gleam"],
  "head":  "brimstone-battlemages:ashen_battlemage_head",
  "chest": "brimstone-battlemages:ashen_battlemage_chest",
  "legs":  "brimstone-battlemages:ashen_battlemage_legs",
  "feet":  "brimstone-battlemages:ashen_battlemage_feet"
}
```

If `brimstone-battlemages` is not loaded, those item IDs won't exist in the registry — the slots silently stay as their loot-table values.

## Notes

- If an armor item ID doesn't exist in the registry (mod not loaded), that slot is silently skipped.
- Only slots that were already filled by the loot tables get overwritten. The loot tables act as the "guard has armor" gate.
- Entries are matched in file load order. The first matching entry wins — put more specific entries before broader namespace catches.
- `match_path_contains` entries within the same list are ORed (any one match is enough). This lets you handle aliases: `["wand_wind", "wand_air"]`.
