---
name: Arena Agents Field Console
description: A readable in-world mission console for configuring and directing Minecraft agents.
colors:
  obsidian-panel: "#18202B"
  slate-surface: "#27313E"
  selected-surface: "#35465A"
  cloud-text: "#F2F5F8"
  mist-text: "#AEB8C4"
  signal-amber: "#F2BD58"
  success-mint: "#66D9A3"
  fault-coral: "#FF737A"
typography:
  title:
    fontFamily: "Minecraft, monospace"
    fontSize: "18px"
    fontWeight: 700
    lineHeight: 1.25
  body:
    fontFamily: "Minecraft, monospace"
    fontSize: "12px"
    fontWeight: 400
    lineHeight: 1.4
  label:
    fontFamily: "Minecraft, monospace"
    fontSize: "10px"
    fontWeight: 600
    lineHeight: 1.2
rounded:
  control: "2px"
  panel: "3px"
spacing:
  xs: "4px"
  sm: "6px"
  md: "12px"
  lg: "18px"
components:
  action-primary:
    backgroundColor: "{colors.signal-amber}"
    textColor: "{colors.obsidian-panel}"
    typography: "{typography.label}"
    rounded: "{rounded.control}"
    padding: "6px 12px"
  roster-selected:
    backgroundColor: "{colors.selected-surface}"
    textColor: "{colors.cloud-text}"
    typography: "{typography.body}"
    rounded: "{rounded.control}"
    padding: "6px 8px"
---

# Design System: Arena Agents Field Console

## Overview

**Creative North Star: "The Field Command Table"**

The player opens this console while standing in an active world, often under a bright sky with visual noise behind the interface. The console is a dark, opaque-enough map table layered above that world, with strong text contrast, deliberate sections, and one signal color reserved for current selection and primary action.

It must feel native to Minecraft without looking like a vanilla settings menu. Custom-rendered navigation, roster rows, status timelines, segmented controls, and world-coordinate previews provide identity. Default buttons are implementation primitives, not the visual system.

**Key Characteristics:**

- Strong full-row selected states with explicit Selected text.
- A stable three-region shell: navigation, working canvas, live activity rail.
- Progressive disclosure between setup, group messaging, and arena operations.
- Dense but readable information with no nested card grid.

## Colors

The palette is restrained: tinted charcoal surfaces and one amber signal, with semantic mint and coral reserved for runtime truth.

### Primary

- **Signal Amber:** Current selection, primary action, and active progress only.

### Secondary

- **Success Mint:** Confirmed readiness or completed work.
- **Fault Coral:** Blocking errors and destructive confirmations.

### Neutral

- **Obsidian Panel:** Main console shell.
- **Slate Surface:** Controls, rows, and secondary regions.
- **Selected Surface:** Full-row selection reinforcement beneath amber details.
- **Cloud Text:** Primary readable copy.
- **Mist Text:** Supporting metadata that remains legible.

**The Signal Rarity Rule.** Amber marks what is current or actionable. It is never decorative.

## Typography

**Display Font:** Minecraft with monospace fallback
**Body Font:** Minecraft with monospace fallback
**Label/Mono Font:** Minecraft with monospace fallback

**Character:** The native glyph language preserves game identity. Hierarchy comes from placement, scale, spacing, and color rather than introducing an unrelated display typeface.

### Hierarchy

- **Title** (700, 18px, 1.25): Current workspace and selected subject.
- **Body** (400, 12px, 1.4): Agent names, tasks, status explanations, and arena details.
- **Label** (600, 10px, 1.2): Navigation, field names, chips, and compact metadata.

**The Human Copy Rule.** Provider and model metadata may be precise, but lifecycle and error text must be phrased for a player, not a protocol engineer.

## Elevation

Depth uses tonal layering and one-pixel keylines, not decorative shadows or glass blur. The world remains context, while the console stays readable across snow, desert, caves, and night.

**The Flat Console Rule.** Surfaces are flat at rest. Focus and selection change fill and keyline contrast, never add ornamental glow.

## Components

### Buttons

- **Shape:** Pixel-tight corners (2px visual radius or squared stepped corners).
- **Primary:** Amber fill with dark text, used once per workspace.
- **Hover / Focus:** Brighter keyline and a small tonal lift.
- **Secondary:** Slate fill with cloud text.
- **Disabled:** Reduced contrast plus explanatory status nearby.

### Chips

- **Style:** Compact provider, model, effort, and speed labels.
- **State:** Selected chips use both fill and a checkmark or Selected label.

### Cards / Containers

- **Corner Style:** Tight panel corners (3px).
- **Background:** Obsidian shell with slate working surfaces.
- **Shadow Strategy:** None.
- **Border:** One-pixel cool-gray keyline.
- **Internal Padding:** 12px working regions, 6px dense rows.

### Inputs / Fields

- **Style:** Slate-black field, full-width label above, visible caret.
- **Focus:** Amber one-pixel keyline and retained label.
- **Error / Disabled:** Coral explanation or muted field with reason.

### Navigation

Use persistent navigation for Agents, Group, Live, and Build. It remains visible across every workspace, using a left rail when wide and a four-part top row when compact. The active destination has a filled background, amber marker, and text label. Never use icons alone.

### Agent Roster Row

The entire row is the target. It shows a friendly name, readable model label, current state, and provider color as secondary metadata. Selection changes the full background, adds an amber outline, and prints Selected. In group mode, a visible checkbox and selected count replace the single-selection treatment.

## Do's and Don'ts

### Do:

- **Do** reserve amber for current selection, active progress, and the primary action.
- **Do** show exact arena coordinates and placement mode before applying blocks.
- **Do** show changed block count, remaining block count, and current construction region during arena work.
- **Do** keep single-agent setup and saved-group management in different workspaces.
- **Do** expose a readable failure with a recovery action when planning stalls.

### Don't:

- **Don't** build a pile of default Minecraft buttons with no hierarchy.
- **Don't** use tiny glyphs as the only selection signal.
- **Don't** bundle configuration and group messaging into one crowded surface.
- **Don't** present technical slugs, IDs, revisions, and internal state names as primary copy.
- **Don't** report progress that does not correspond to visible or measurable world changes.
- **Don't** use glassmorphism, gradient text, colored side stripes, or identical card grids.
