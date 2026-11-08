# SpigotMC — resource draft (BBCode)

SpigotMC uses BBCode, not Markdown. Paste the block below into the resource description. SpigotMC reviews new resources manually (usually 1–2 days) — submit after Modrinth, reusing the same jar.

---

[b]AIBuilder[/b] - Guided builder NPCs for Paper servers.

[b]What it does[/b]
Players guide named builder NPCs (Alfred, Bruce, Clark, Diana, Tony) that build in the world for them.

- [b]Built-in models, no AI key required.[/b] Seven original constructions (cottages, modern houses, a 3-story building, a giant oak, a treehouse) ordered from the clickable menu.
- [b]Custom builds with your own OpenRouter key.[/b] Talk to the builder in chat ([font=monospace]@alfred build a stone cottage with a garden[/font]) - it plans, you review and confirm, then it builds. Costs are billed by OpenRouter to your account.
- [b]Safety first.[/b] Every AI plan is confirmed by the player before construction. Build areas, world limits, spawn protection, and GriefPrevention claims stay enforced. Without CoreProtect, only natural terrain/vegetation inside the confirmed selection can be removed.
- [b]Terrain tools.[/b] Inspect, clean, and level a marked area with a preview before confirming; operator-only deep clean/level for heavy work.
- [b]EN/PT.[/b] Menus, help, and chat in English or Portuguese.

[b]Requirements[/b]
Paper server (tested on Paper 26.3, api-version 1.21), Java 25. No hard dependencies - GriefPrevention and CoreProtect are optional.

[b]Quick start[/b]
[list]
[*]Drop the jar in [font=monospace]plugins/[/font] and start Paper
[*]Run [font=monospace]/builder[/font], create a builder, mark an area with the diamond shovel
[*]Pick a built-in model (no key needed) or set your OpenRouter key in [font=monospace]plugins/AIBuilder/config.yml[/font]
[*]Review the preview and confirm - the builder only changes blocks after confirmation
[/list]

[b]License[/b]
MIT License - Copyright (c) 2026 elitonvita.

---

## Version to upload

File: `build/AIBuilder-1.0.0.jar` (SHA256 `2979e462...2b0a2`). Game-version caveats as in `modrinth-page.md` — do not declare Minecraft versions that were never tested.
