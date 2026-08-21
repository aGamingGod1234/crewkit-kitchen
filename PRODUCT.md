# Product

## Register

product

## Users

Minecraft players operating one or many autonomous agents while remaining inside a live world. They need to configure agents quickly, understand which agents are selected, issue individual or group instructions, and see whether arena construction and agent work are actually progressing.

## Product Purpose

Arena Agents is an in-world operations console for creating, configuring, directing, and observing AI-controlled Minecraft players. Success means the interface makes runtime truth obvious: where an arena will appear, what is changing now, which agent or group will receive a command, and why work has stopped.

## Brand Personality

Purposeful, legible, alive. The product should feel like a dedicated operations product inside Minecraft, not a reskinned vanilla menu: confident enough for expert control but readable without knowing provider slugs, lifecycle enums, or protocol details.

## Anti-references

- A pile of default Minecraft buttons with no hierarchy.
- Tiny glyphs as the only selection signal.
- Configuration and saved-group management bundled into one crowded surface.
- Technical slugs, IDs, revisions, and internal state names presented as primary copy.
- Progress messages that do not correspond to visible or measurable world changes.

## Design Principles

1. Runtime truth first. Every status must describe observable work, a wait condition, or an actionable failure.
2. One agent at a time for setup. Configuration is sequential and preserves each agent independently.
3. Groups only where groups help. Multi-selection belongs to saved rosters and group control.
4. Selection must be unmistakable. Use a full-row treatment, explicit label, count, and detail context.
5. Keep the world in context. Arena placement defaults to 80 blocks in front of the operator, exposes exact coordinates before launch, and paces visible construction.

## Accessibility & Inclusion

Never rely on color or a tiny symbol alone. Maintain high contrast over bright and dark biomes, use plain language, provide keyboard navigation, and keep all critical state readable at common Minecraft GUI scales. Motion must be brief and state-driven.
