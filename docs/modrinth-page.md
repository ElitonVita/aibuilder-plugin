# Modrinth — submission draft

Fill the fields below at https://modrinth.com when creating the project. Nothing is published yet.

## Project fields

| Field | Value |
|---|---|
| Name | AIBuilder |
| Slug (auto) | aibuilder |
| Summary (≤ 80 chars) | Guided builder NPCs with AI and built-in models for Paper servers |
| Description | See *Project page* below |
| Categories | Utility, Building, Adventure |
| License | MIT |
| Source | (optional — GitHub repo not published) |
| Issue tracker / Discord | (optional — add if desired) |
| Icon | `publication/assets/icon-256x256.png` (also `icon-512x512.png` for larger slots) |
| Screenshots | GIF/video demo + screenshots of a build in progress **(to capture in game)** |

## First version upload

| Field | Value |
|---|---|
| Version name | `1.0.0` |
| File | `build/AIBuilder-1.0.0.jar` (373.958 bytes, SHA256 `2979e46202d9c96959c092351135aa942763c256f330a8cbdd2e558e2272b0a2`) |
| Game versions | **Decision pending.** Developed and tested on Paper 26.3 with `api-version: '1.21'`. Declare only versions actually used; recommended: `1.21.x` if confirmed on real servers, otherwise the 26.x series. **Do not declare versions that were never tested.** |
| Loaders | Paper |
| Version type | Release |
| Changelog | See `CHANGELOG-1.0.0.md` |

## Project page (paste into Modrinth)

> **AIBuilder** gives every player named builder NPCs (Alfred, Bruce, Clark, Diana, Tony) that help build in the world.
>
> - **Built-in models — no AI key required.** Seven original constructions (cottages, modern houses, a 3-story building, a giant oak, a treehouse) that any player can order from the clickable menu.
> - **Custom builds with your own OpenRouter key.** Talk to the builder in chat (`@alfred build a stone cottage with a garden`) and it plans the build; you review and confirm before anything is placed. Usage costs are billed by OpenRouter to your account.
> - **Safety first.** Every AI plan is confirmed by the player first. Build areas, world limits, spawn protection, and GriefPrevention claims stay enforced. Without CoreProtect, only natural terrain/vegetation inside the confirmed selection can be removed.
> - **Terrain tools.** Inspect, clean, and level a marked area with a preview before you confirm; operator-only deep clean/level for heavy work.
> - **EN/PT.** Menus, help, and chat in English or Portuguese.
>
> **Requirements:** Paper server (tested on Paper 26.3, `api-version 1.21`), Java 25. No hard dependencies — GriefPrevention and CoreProtect are optional.
> **License:** MIT.

## Extra constructions (additional files on this version)

Attach these 5 zips as **additional files** on the version page (sizes + SHA-256 verified at build time, see `publication/prebuilt-packs/manifest.json`):

| File | Constructions |
|---|---:|
| `Monumentos-do-Mundo-Volume-1-1.0.0.zip` | 16 world monuments |
| `Reinos-Medievais-Volume-1-1.0.0.zip` | 16 medieval buildings |
| `Vila-Viva-Volume-1-1.0.0.zip` | 16 village buildings |
| `Casas-Village-Volume-1-1.0.0.zip` | 7 biome village houses |
| `Castelos-e-Casas-Volume-1-1.0.0.zip` | 2 (Cinderella Castle, Modern Cube Home) |

Each zip: loose `.json` files + `README.md` (EN/PT install) + `manifest.json` + `LICENSE-MIT.txt` + `.sha256` sidecar. **Total: 57 constructions** on top of the 7 built-in models. Also note the packs in the project page text.

## Final checks before clicking "Create project"

- [x] Public version decided: `1.0.0` (`plugin.yml`, `pom.xml`, `build.sh` all updated; verified in jar)
- [ ] Game versions declared = versions actually tested
- [x] Icon 256×256 created (`publication/assets/icon-256x256.png`)
- [ ] Demo video/GIF + screenshots captured in game
- [ ] Jar rebuilt from final source; SHA recorded in this file
- [ ] No real API key anywhere in the published jar (`config.yml` ships with the placeholder)
