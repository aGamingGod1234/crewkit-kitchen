package dev.agaminggod.arenaagents.server;

import java.util.List;
import java.util.Objects;

/** Bounded, world-persisted timeline for a named agent. */
public record SkitScript(String name, String agentSelector, List<SkitStep> steps) {
	public static final int MAX_STEPS = 512;

	public SkitScript {
		name = requireName(name, "name");
		agentSelector = requireName(agentSelector, "agentSelector");
		Objects.requireNonNull(steps, "steps must not be null");
		if (steps.size() > MAX_STEPS) throw new IllegalArgumentException("A skit script may contain at most " + MAX_STEPS + " steps");
		steps = List.copyOf(steps);
		if (steps.stream().anyMatch(Objects::isNull)) throw new IllegalArgumentException("steps must not contain null");
	}

	public SkitScript append(SkitStep step) {
		Objects.requireNonNull(step, "step must not be null");
		if (steps.size() >= MAX_STEPS) throw new IllegalArgumentException("A skit script may contain at most " + MAX_STEPS + " steps");
		java.util.ArrayList<SkitStep> next = new java.util.ArrayList<>(steps);
		next.add(step);
		return new SkitScript(name, agentSelector, next);
	}

	private static String requireName(String value, String field) {
		Objects.requireNonNull(value, field + " must not be null");
		String checked = value.strip();
		if (checked.isEmpty() || checked.length() > 64 || !checked.matches("[A-Za-z0-9_.-]+")) {
			throw new IllegalArgumentException(field + " must be 1-64 letters, numbers, '.', '_' or '-'");
		}
		return checked;
	}
}
