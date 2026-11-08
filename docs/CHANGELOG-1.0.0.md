# AIBuilder 1.0.0 — first release notes

**What this release is:** a Paper plugin that gives players named builder NPCs (Alfred, Bruce, Clark, Diana, Tony). Builders place original built-in models without any AI key, or plan custom builds through the player's own OpenRouter API key. Every AI plan is shown as a preview and confirmed by the player before construction starts.

**Highlights**

- Seven built-in models (Country Cottage, Modern House, Modern Cube Home, Ranch House, Giant Oak Tree, Modern 3-Story Building, Treehouse) — no AI key needed.
- Custom builds via OpenRouter chat (`@alfred build ...`), with material list and area preview before confirmation.
- Terrain tools: inspect, clean, and level a marked area with preview; operator-only deep clean/level (off by default).
- Protection-aware: claims (GriefPrevention), spawn exclusion, world limits; CoreProtect history when available; conservative fallback without it.
- EN/PT languages; parental hours/quotas for AI requests; the builder never attacks players (hostile monsters only, when idle).
- Saved builds with automatic checkpoints every 1,000 blocks; resume after restart.

**Requirements:** Paper 26.3 (api-version 1.21), Java 25. No hard dependencies.

**Fix in this build:** terrain `check()` only applies the internal spawn rule in normal mode; Deep mode respects the documented policy (operator-only, native protection events authoritative).
- **Extra constructions:** 5 downloadable packs with 57 constructions total (world monuments, medieval kingdoms, living village, biome houses, castles) — see `publication/prebuilt-packs/INVENTARIO.md`.

**Known limits:** built-in geometries are full solid blocks only (no doors, stairs, slabs, torches, carpets, plants); AI plans are capped by `max-ai-actions` (250) and `max-project-blocks` (2,500); in-game behavior of movement, combat, and leveling still needs observation on player worlds.
