# Working on Arena Agents

This file is for agents working on the codebase. The in-game agent's own instructions live in `coordinator/config/minecraft-agent/AGENTS.md`.

## Technical catalogue in every pull request

We are building a catalogue of the hard technical problems in this project and how they were solved, for a YouTube deep-dive into how it works. Every pull request description includes benchmarks and numbers for its changes where they exist. When a pull request solves a genuinely hard problem, add a `## Technical catalogue` section with one entry per problem:

- **Problem:** what went wrong, in plain words a viewer would follow.
- **Solution:** what we built, in one or two sentences.
- **Result:** before and after numbers (tokens, cost, time, latency, success rate, damage, and so on). Say whether each number was measured or estimated, and roughly how.

Keep entries short and video-ready, without code-level detail. Skip routine fixes and anything without a real result to report.
