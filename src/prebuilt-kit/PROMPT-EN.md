# PROMPT: create a custom pre-built structure (pre-moldado) for AIBuilder

Copy everything below the line "=== BEGIN PROMPT ===" into your AI assistant, then add your request
at the end, where it says REQUEST. The AI must answer with one JSON file and nothing else.

=== BEGIN PROMPT ===

You are designing a pre-built structure for AIBuilder, a Minecraft Paper 26.3 plugin. A player
will save your answer as a `.json` file, and an in-game builder NPC will then place the structure
block by block inside an area that the player marks in the world. Your JSON must describe the
structure only. It must never contain world coordinates.

## Context you must respect

- The Minecraft server is used by children. Keep every design child-safe and family-friendly:
  no weapons, no violence, no skulls or heads, no signs or text, no chests with items, no
  graphic themes. Themes such as houses, trees, towers, gardens, farms, bridges and castles are fine.
- The builder places blocks in order, flies when needed, and never places blocks on top of the
  player or itself. You do not need to plan the route.
- The builder places ONLY the blocks you describe. It does not use player inventory for
  pre-built structures. Nothing else is placed.
- The player chooses the location in the world with the builder's diamond shovel (marking an area),
  then runs `/construtor <name> pre-moldado <id>`. You do not choose a location.

## Coordinate system (relative, not world)

- Coordinates are integers `[x, y, z]` relative to the MINIMUM corner of the marked area.
- `x` grows east and `z` grows south; `y` grows upward. By convention the main door faces south,
  which means the door is on the side of the largest `z`. Declare `"entrance": "south"`.
  The player can choose a different entrance direction at planning time. The plugin rotates
  geometry and block states; never pre-rotate to a player's world location.
- `y = 0` is the foundation layer (the first layer on the ground). Blocks are placed at `y >= 0`.
  The only exception allowed is `y = -1`, for a roof overhang or eave that hangs 1 block below
  the foundation. Nothing lower than `y = -1`.
- The footprint must start at `x = 0` and `z = 0` (its minimum corner is `[0, *, 0]`).
  The planner centres the structure inside the marked area.
- Limits: at most 4096 parts by default; at most 2500 blocks by default in total (the server may set a different limit);
  footprint at most 200 x 200 blocks on x and z; height at most 200 blocks by default (server configurable); at least 1 block.
- Vertical coordinate rule: for `walls`, `wall` and `roof_steps`, the HEIGHT comes from
  `height` or `layers`. Write the same `y` in `from` and `to`. Do not rely on `to.y`.

All workload limits are chosen by the server operator. The automatic selection height starts
at 100, but the importer dimension limit defaults to 200. Empty space counts toward bounding
volume, while only placed blocks count toward `max-project-blocks` (default 2500). Ask for the
server limits before designing a larger structure; never assume an unlimited block budget.

## Output format (strict)

Return ONLY one raw JSON object. No markdown, no code fences, no comments, no text before or after.
Use double quotes. No trailing commas. Keys listed below only. Any other root key is an error.

Root object:

```
{
  "id": "lowercase_id",            // required. 3-40 chars, [a-z][a-z0-9_]*, unique, not a built-in id
  "title": "Nome Exibido",         // required. Portuguese (Brazil) display name, 1-60 chars
  "description": "...",            // required. Portuguese (Brazil), 1-240 chars: size and main features
  "entrance": "south",             // optional. ORIGINAL entrance direction: north/south/east/west
  "category": "casas",             // optional. [a-z_]{2,30}. Default "personalizados"
  "parts": [ ... ]                 // required. 1 to the configured part limit (default 4096), applied in order
}
```

Forbidden root keys (they cause an error): `x`, `y`, `z`, `world`, `origin`, `area`, `location`, `pos`.
A key starting with `_` is ignored, but do not use one.

Built-in ids that you must NOT reuse: `casa_campo`, `casa_moderna`, `cubo_moderno`, `casa_rasa`,
`arvore_gigante`, `predio_moderno_3_andares`, `casa_na_arvore`.

## Part types

Every part has `type` and `material`. A `data` string is optional and is needed only for
blocks with states (doors, stairs, glass panes, leaves). All `from`/`to` are inclusive.

1. `box`: fills a solid rectangular volume.
   `{ "type": "box", "material": "STONE_BRICKS", "from": [0,0,0], "to": [9,0,7] }`
   Volume = all integer cells between from and to, inclusive. Use `from == to` for one block.

2. `walls`: a closed rectangular ring of walls (only the outer edge), `height` blocks tall, with
   optional openings (doors, windows).
   `{ "type": "walls", "material": "OAK_PLANKS", "from": [0,1,0], "to": [9,1,7], "height": 4,
      "openings": [ { "side": "south", "at": 4, "width": 1, "height": 2 } ] }`
   - `from` and `to` describe the floor plan; both must have the same `y` (the first wall layer).
   - The walls occupy `y` .. `y + height - 1`.
   - `openings` (optional, configurable, default 1024): `side` is `north`, `south`, `east` or `west`;
     `at` is the offset from the west (for north/south) or north (for east/west) edge of that side,
     starting at 0; `width` and `height` are in blocks; `y` is the bottom of the opening relative to
     the wall bottom (default 0). Openings are cut out of the walls; then place the door or glass
     with separate parts on the opening cells.

3. `wall`: one straight wall line.
   `{ "type": "wall", "material": "BRICKS", "from": [0,1,0], "to": [9,1,0], "height": 3 }`
   `from` and `to` must share x or z (a straight line). Same `openings` rules as `walls`.

4. `roof_steps`: a stepped roof, each layer one block inset from the previous one.
   `{ "type": "roof_steps", "material": "SPRUCE_PLANKS", "from": [0,5,0], "to": [9,5,7], "layers": 4 }`
   Fills `layers` levels starting at `from.y`, each layer inset by one block. `layers` must be at
   most half of the smaller footprint side. For a flat roof, use `box` instead.

Parts are applied in order: a later part overwrites an earlier part on the same cell. Use this to
make a door, a window or a light on top of a wall.

## Blocks: materials and data

- `material` is an uppercase Minecraft item/block id such as `OAK_PLANKS` or `STONE_BRICKS`.
  NO `minecraft:` prefix in `material`.
- `data` is a block state string in the form `minecraft:<same_material_lowercase>[key=value,key=value]`.
  The part `material` and the `data` name must match: `OAK_DOOR` requires `minecraft:oak_door[...]`.
  Use only lowercase keys and values, no spaces.

Accepted materials: any non-air block material available in the target server's Paper 26.3+ API,
except the protected materials listed below. The plugin also checks each block's data and whether
the block can be placed at its target location. Prefer explicit `data` for stateful blocks (doors,
stairs, panes, leaves, lights, rails, signs, and similar blocks). Use only material IDs supported by
the server that will load the model. Examples that work well:
- Stone and earth: STONE, STONE_BRICKS, COBBLESTONE, MOSSY_COBBLESTONE, POLISHED_ANDESITE,
  POLISHED_DIORITE, SMOOTH_STONE, SMOOTH_QUARTZ, QUARTZ_BLOCK, BRICKS, DIRT, COARSE_DIRT, ROOTED_DIRT,
  GRASS_BLOCK, MOSS_BLOCK, PACKED_MUD, TERRACOTTA, SANDSTONE, RED_SANDSTONE, CLAY.
- Wood planks: OAK_PLANKS, SPRUCE_PLANKS, BIRCH_PLANKS, DARK_OAK_PLANKS, JUNGLE_PLANKS, ACACIA_PLANKS,
  CHERRY_PLANKS, MANGROVE_PLANKS, BAMBOO_PLANKS, CRIMSON_PLANKS, WARPED_PLANKS.
- Logs (wood columns): OAK_LOG, SPRUCE_LOG, BIRCH_LOG, DARK_OAK_LOG, JUNGLE_LOG, ACACIA_LOG.
- Wool and concrete: WHITE_WOOL, BLACK_WOOL, BROWN_WOOL, LIGHT_BLUE_WOOL, WHITE_CONCRETE,
  GRAY_CONCRETE, LIGHT_GRAY_CONCRETE, BLACK_CONCRETE, ...
- Functional but simple: CRAFTING_TABLE, BOOKSHELF.
- Light: GLOWSTONE, SEA_LANTERN, SHROOMLIGHT.
- Leaves are allowed with data: OAK_LEAVES, SPRUCE_LEAVES, BIRCH_LEAVES, DARK_OAK_LEAVES,
  CHERRY_LEAVES. Use `persistent=true` so they never decay:
  `"data": "minecraft:oak_leaves[persistent=true,waterlogged=false]"`.

Stateful block examples (include `data` so orientation and connected parts are clear):
- Doors (lower and upper halves are two separate parts, both with data):
  `"data": "minecraft:oak_door[facing=south,half=lower,hinge=left,open=false,powered=false]"` and
  the same with `half=upper`. Use the same material for both halves, e.g. OAK_DOOR, SPRUCE_DOOR, BIRCH_DOOR.
- Stairs (single block, one step):
  `"data": "minecraft:oak_stairs[facing=east,half=bottom,shape=straight,waterlogged=false]"`.
  `facing` is the direction the step goes UP toward (east, west, north, south).
- Glass panes (data is needed; connection flags must be valid): prefer GLASS (a full block, no data)
  for windows. Use GLASS_PANE only if you give all four flags:
  `"data": "minecraft:glass_pane[north=false,east=false,south=false,west=false,waterlogged=false]"`.
- Blocks with gravity (SAND, GRAVEL, CONCRETE_POWDER): only if supported from below.

Forbidden materials (the builder refuses them): TNT, LAVA, FIRE, SOUL_FIRE, BEDROCK, SPAWNER,
COMMAND_BLOCK, CHAIN_COMMAND_BLOCK, REPEATING_COMMAND_BLOCK, STRUCTURE_BLOCK, STRUCTURE_VOID, JIGSAW,
BARRIER, END_PORTAL_FRAME, TRIAL_SPAWNER. WATER is allowed only as source water in a fully sealed
planned basin. LIGHT must use `level=15,waterlogged=false`; flowers must pass Paper's support check.
Other non-air block materials supported by the server are accepted, including signs, containers,
rails, plants, slabs, fences, and decorative blocks. Models only specify block material and state;
they do not import sign text, inventory contents, skins, or other block-entity content.

## Design rules that make the structure buildable

1. Follow the block's Paper placement rules. Add support beneath or beside blocks that require it;
   self-supporting blocks and the special LIGHT material do not need a solid block below.
2. Doors: place the floor cell under the door, the lower door half at `y`, and the upper door half at
   `y + 1`. The floor must not be a door. The opening in the wall must be exactly 1 wide and 2 high
   for a single door.
3. Leave the interior free: at least 2 blocks of air above every walkable floor cell. Furniture must
   not block a door or the path from the door to the rest of the room.
4. Keep the total block count reasonable (roughly 80-1200). Prefer `box` parts (few parts, many blocks).
5. The structure is a single connected build. Do not leave floating parts.
6. Foundation: `y = 0` is the ground-level layer. A raised floor sits on top of a foundation layer
   at `y = 0`, for example STONE_BRICKS.

## What a good answer looks like (complete example, follow this shape)

```
{
  "id": "abrigo_madeira",
  "title": "Abrigo de Madeira",
  "description": "5 x 5 x 6. Piso de pedra, paredes de tabua, porta de carvalho e telhado de abeto.",
  "entrance": "south",             // optional. ORIGINAL entrance direction: north/south/east/west
  "category": "casas",
  "parts": [
    { "type": "box", "material": "STONE_BRICKS", "from": [0, 0, 0], "to": [4, 0, 4] },
    { "type": "walls", "material": "OAK_PLANKS", "from": [0, 1, 0], "to": [4, 1, 4], "height": 4,
      "openings": [ { "side": "south", "at": 2, "width": 1, "height": 2 } ] },
    { "type": "box", "material": "OAK_DOOR", "data": "minecraft:oak_door[facing=south,half=lower,hinge=left,open=false,powered=false]", "from": [2, 1, 4], "to": [2, 1, 4] },
    { "type": "box", "material": "OAK_DOOR", "data": "minecraft:oak_door[facing=south,half=upper,hinge=left,open=false,powered=false]", "from": [2, 2, 4], "to": [2, 2, 4] },
    { "type": "box", "material": "SPRUCE_PLANKS", "from": [0, 5, 0], "to": [4, 5, 4] },
    { "type": "box", "material": "CRAFTING_TABLE", "from": [1, 1, 1], "to": [1, 1, 1] },
    { "type": "box", "material": "GLOWSTONE", "from": [3, 4, 2], "to": [3, 4, 2] }
  ]
}
```

## Self-check before you answer (all must be true)

- [ ] The answer is ONE JSON object, no code fence, no comment, no extra text.
- [ ] No root key other than id, title, description, category, parts (no x, y, z, world, origin, area).
- [ ] No world coordinates anywhere. Every coordinate is an integer `[x, y, z]` relative to the
      footprint: the minimum corner is `x = 0`, `z = 0`, and `y >= -1`.
- [ ] `id` is unique (not a built-in id), lowercase, 3-40 chars.
- [ ] Every `from` is less than or equal to `to` on all three axes.
- [ ] `walls` and `wall` have `height`; `roof_steps` has `layers`. `from.y` and `to.y` match.
- [ ] Every block with `data` has the same material name in lowercase inside `minecraft:...[...]`.
- [ ] Doors have both halves, and each half is its own part.
- [ ] No forbidden material and no non-solid decoration from the list above.
- [ ] Footprint and height within configured dimensions (default 200 x 200 x 200), parts within configured limit (default 4096), total blocks at most the configured limit (default 2500).
- [ ] Title and description are in Portuguese (Brazil); keys and ids are in English.

## REQUEST

<Describe the structure you want here. Example: "Uma casa de dois andares, 9 x 7, com varanda,
escada interna, porta de carvalho e janelas de vidro, estilo rustico, cores de madeira.">

=== END PROMPT ===
