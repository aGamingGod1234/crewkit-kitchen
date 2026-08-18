# Self-Healing Scenario Runtime Design

## Goal

Make authored arenas converge to their blueprints, ensure PvP loot and containment are ready before agents spawn, and let scenarios run until gameplay or the operator ends them rather than an arbitrary clock.

## Corrective arena verification

Arena construction becomes a bounded convergence loop:

1. Apply the canonical blueprint.
2. Exhaustively verify every canonical position.
3. Collect every mismatch, not only five samples.
4. Rewrite the exact canonical states at mismatched positions.
5. Reverify the complete canonical blueprint.

Three correction passes are allowed. A normal grass/dirt or neighbor-update mismatch should converge on the first correction. The bound prevents an impossible or continuously-mutating blueprint from keeping the server in a permanent build state. A non-convergent build fails with the pass count, total mismatch count, and representative samples.

Grass remains the authored surface. Grass/dirt mismatches are corrected instead of whitelisted.

## PvP arena

Citadel Collapse remains a Survival scenario and gains:

- a bedrock floor beneath the managed arena;
- a bedrock lower perimeter and barrier wall high enough to contain every participant;
- cobbled-deepslate cache boxes replaced with roofed houses containing doors and loot containers;
- cobblestone surface ruins replaced with underground cobblestone dungeons reached by stairs;
- deterministic useful loot in every chest and barrel;
- empty contestant inventories at activation.

Loot is tiered by landmark instead of drawing every container from one undifferentiated list:

- houses provide food, leather armor, basic tools, shields, and basic weapons;
- underground dungeons provide iron armor pieces, iron weapons, bows/crossbows, arrows, food, and occasional utility items;
- central containers provide contested high-tier equipment and utility items.

After block verification, the runtime writes a deterministic container manifest and verifies every expected slot. Missing or incorrect stacks are rewritten before activation. Container readiness is therefore part of arena readiness rather than a best-effort postscript.

## Scenario clocks

Scenario time becomes elapsed-time telemetry, not an automatic loss condition.

- Citadel Collapse has no grace period; combat is enabled from tick zero.
- Thinking Tower has no 20-minute cutoff. Individual finishes continue to be detected and announced.
- All built-in scenarios continue until a gameplay terminal condition or an operator stop.
- PvP may finish when one living participant or one living team remains.
- Parkour may finish when every participant finishes; otherwise it remains active for observation.
- Building and open-ended Survival scenarios finish through explicit completion/operator control, not duration expiry.

Existing phase names may remain as descriptive elapsed-time milestones, but reaching the final scheduled tick must not stop agents or generate a duration-elapsed result.

## Chat and operator feedback

Build progress distinguishes `VERIFY` from `REPAIR`. A repaired build reports how many mismatches were corrected and how many passes were needed. Only a non-convergent or structurally invalid arena is shown as failed.

## Verification

Focused tests must prove:

- a mismatch enters repair and converges after the expected state is rewritten;
- a continuously changing state fails after exactly three correction passes;
- the PvP blueprint contains containment, houses, underground dungeons, and all expected containers;
- PvP loadouts are empty;
- loot manifests are deterministic, nonempty, and tier-appropriate;
- clocks do not finish at the former duration;
- Citadel Collapse begins without a grace phase;
- all project verification suites pass.
