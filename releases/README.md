# release-assets

These files are **upload staging** for GitHub Releases — they are NOT committed to git.

## How to publish

1. On GitHub, open the repo → **Releases → Draft a new release**.
2. Tag: `v1.0.0`. Title: `AIBuilder 1.0.0`.
3. Release notes: paste `docs/CHANGELOG-1.0.0.md`.
4. Attach the files from this folder:
   - `AIBuilder-1.0.0.jar` (SHA-256 in `AIBuilder-1.0.0.jar.sha256`)
   - The 5 construction-pack zips (`Monumentos-do-Mundo-Volume-1-1.0.0.zip`, `Reinos-Medievais-Volume-1-1.0.0.zip`, `Vila-Viva-Volume-1-1.0.0.zip`, `Casas-Village-Volume-1-1.0.0.zip`, `Castelos-e-Casas-Volume-1-1.0.0.zip`)
5. Publish the release.
