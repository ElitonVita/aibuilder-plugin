# Security policy

## Reporting a vulnerability

Thank you for helping keep AIBuilder and its users safe.

**Please report security vulnerabilities privately, not in public issues.**

1. Go to the repository's **Security** tab on GitHub.
2. Click **Report a vulnerability** and describe the issue using GitHub's private-advisory form.

If private reporting is unavailable on the repository, open a **private** conversation with the maintainer through any other channel you already have — do not post vulnerability details in a public issue.

Please include, where possible:

- The affected version (`AIBuilder-1.0.0.jar`, or `plugin.yml` `version`) and platform (Paper 26.3 / Java 25).
- Steps to reproduce.
- The impact you observed or expect (e.g. arbitrary block changes, claim/spawn bypass, inventory access, data exposure).
- Any logs relevant to the report (`plugins/AIBuilder/actions.log` + `logs/latest.log` excerpts; **never** include a real API key or a full `config.yml`).

## What we ask of reporters

- Give us a reasonable amount of time to fix the issue before any public disclosure (coordinated disclosure). We will credit you in the fix notes unless you prefer anonymity.
- Do not exploit the issue beyond the minimum needed to confirm it, and do not access or modify other servers' data.

## Supported versions

| Version | Supported |
| ------- | --------- |
| 1.0.x (latest release) | Yes — security fixes |
| Anything older | Community help only; upgrade to 1.0.x first |

## Scope

In scope: the AIBuilder plugin code (`src/`), release assets (`AIBuilder-1.0.0.jar`), the construction-pack JSONs in `releases/`, and the documentation in this repository.

Out of scope: Minecraft itself, Paper, OpenRouter and other third-party services, attacks on servers that run the plugin (misconfiguration, social engineering, physical/server access), and issues in plugins you run alongside AIBuilder.

## Security design notes (for users and auditors)

- Every AI-generated plan waits for an explicit player confirmation (`/builder yes`) before any block changes.
- Construction is bounded by area, world limits, spawn-exclusion radius, and material/protection checks; GriefPrevention claims remain protected and a broken claim integration fails closed.
- Without CoreProtect, removing natural terrain/vegetation is limited to the confirmed selection; set `allow-break-without-coreprotect-in-selection: false` for fully conservative behavior.
- Deep clean/level is operator-only and off by default; external protection plugins remain authoritative.
- No API key ever ships with the plugin. Each server operator configures their own OpenRouter key; never share logs or config backups that contain one.
