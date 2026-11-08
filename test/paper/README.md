# Isolated Corcovado acceptance test

**Never install AIBuilderRuntimeCheck on a player server.** It edits its test world and stops its own server. A purpose-specific marker is required. The runner requires a new empty directory, binds only to `127.0.0.1`, and copies no player worlds, private configuration or other plugins.

Requires Java 25, a built AIBuilder JAR, and an existing Paper 26.3 installation with its cached runtime libraries and already-accepted EULA:

```sh
python3 tools/run_paper_corcovado_test.py \
  --source-server '/path/to/existing/Paper/server' \
  --plugin-jar build/AIBuilder-0.1.0.48.jar \
  --work-dir /private/tmp/aibuilder-new-isolated-check \
  --port 25579
```

The runner fails before writing if the directory is nonempty. It writes `corcovado-result.json` and `test-console.log` in the disposable directory. The harness should finish in about one minute after startup on the tested machine; the runner has a ten-minute timeout and only terminates its own subprocess. No speed guarantee is made for other systems.

Coverage:

- The exact exported `mm1_cristo_redentor.json` model, compiled to 49,335 blocks.
- Real Paper `BlockData`, support rules, runtime preparation and placement, then inspection of every final block.
- A complete already-present pass through the real production queue, with zero new-placement credit.
- Separate physical NPC walking over 95 blocks and flight up 96 blocks, without teleporting those routes.
- Two continuous eight-block construction sequences starting with the NPC obstructing the first target, on the ground and airborne. These use the actual queue, follow/movement tick and placement code, with no manual repositioning during either sequence. Checks require all targets placed, zero refusals, no placement through the builder and zero construction teleports.

Scope: placement testing uses controlled NPC positions beside each target, so it does **not** reproduce continuous NPC travel through every block of the full construction. Physical locomotion is tested separately. It also does not reproduce player-server terrain, other plugins, player interaction, network rendering, material charging or production performance. The production rate limits are not raised by this test; the harness sets a larger limit only in its isolated in-memory configuration.

The final `.48` JAR passed the complete runner end to end (exit 0), including the autonomous step-aside sequences. Permanent serverless regressions in `BuilderStepAsideTest` also cover fractional body/head collisions, old goals, repeated edits and unloaded chunks. A negative control with the arrival clearance removed fails those regressions. Recorded evidence: [corcovado-paper-0.1.0.48.json](../evidence/corcovado-paper-0.1.0.48.json).
