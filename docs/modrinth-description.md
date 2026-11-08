# AIBuilder — description (paste into Modrinth)

**AIBuilder** gives players named builder NPCs (Alfred, Bruce, Clark, Diana, Tony) that help build in the world on Paper servers.

**Built-in models — no AI key required.** Seven original constructions (Country Cottage, Modern House, Modern Cube Home, Ranch House, Giant Oak Tree, Modern 3-Story Building, Treehouse) that any player can order from the clickable menu.

**Custom builds with your own OpenRouter key.** Talk to the builder in chat (`@alfred build a small stone cottage with a garden`) and it plans the build; you review the material list and required area, then confirm before anything is placed. Usage costs are billed by OpenRouter to your account. Without a key, built-in models and all other non-AI tools keep working.

**Safety first.** Every AI plan is confirmed by the player before construction starts. Build areas, world limits, spawn protection, and GriefPrevention claims stay enforced. Without CoreProtect, the default permits removing natural terrain/vegetation only inside the confirmed selection — set `allow-break-without-coreprotect-in-selection: false` for fully conservative behavior.

**Extra constructions.** Beyond the 7 built-in models, 57 more constructions are available as downloadable packs (world monuments, medieval kingdoms, living village, biome houses, castles). Extract a pack's JSON files into `plugins/AIBuilder/prebuilts/`, run `/builder reload`, then order them like any built-in model.

**Terrain tools.** Inspect, clean, and level a marked area with a preview before confirming. Operator-only deep clean/level is available off by default and can remove structures inside the selected box; external protection plugins remain authoritative.

**EN/PT.** Menus, help, and chat in English or Portuguese.

## Requirements

- Paper server (developed and tested on Paper 26.3; `api-version: '1.21'`)
- Java 25
- No hard dependencies. Optional: GriefPrevention (claims), CoreProtect (history), OpenRouter API key (custom AI builds only)

## Known limitations

- Constructions use **full solid blocks only** — doors, beds, stairs, slabs, torches, carpets, plants, and fluids are not supported.
- One project is capped at 2,500 blocks (`max-project-blocks`) and AI plans at 250 actions; raise limits carefully while watching server performance.
- Movement, combat, and leveling behavior have been verified in tests and on the author's server; behavior on very large or heavily protected worlds may vary.

## Quick start

1. Put `AIBuilder-1.0.0.jar` in `plugins/` and start Paper.
2. Run `/builder`, create a builder, mark an area with the diamond shovel (left click corner 1, right click corner 2).
3. Pick a built-in model — no key needed — or configure an OpenRouter key for custom builds.
4. Review the preview and confirm. The builder only changes blocks after confirmation.

## License

MIT License — Copyright (c) 2026 elitonvita.
