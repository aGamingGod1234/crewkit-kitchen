package dev.agaminggod.arenaagents.client.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import dev.agaminggod.arenaagents.protocol.ProtocolConstants;
import dev.agaminggod.arenaagents.protocol.ProtocolException;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Locale;
import java.util.Set;
import net.fabricmc.loader.api.FabricLoader;

public final class AgentConfigLoader {
	public static final String CONFIG_FILE_NAME = "arenaagents.json";
	public static final String LOOPBACK_HOST = "127.0.0.1";
	public static final int MAX_CONFIG_BYTES = 16_384;

	private static final String FIELD_AGENT_ID = "agentId";
	private static final String FIELD_BRIDGE_PORT = "bridgePort";
	private static final String FIELD_OBSERVATION_RADIUS = "observationRadius";
	private static final String FIELD_ENABLED = "enabled";
	private static final String FIELD_HOST = "host";
	private static final Set<String> ALLOWED_FIELDS = Set.of(
			FIELD_AGENT_ID,
			FIELD_BRIDGE_PORT,
			FIELD_OBSERVATION_RADIUS,
			FIELD_ENABLED,
			FIELD_HOST
	);
	private static final Set<String> LOOPBACK_HOST_NAMES = Set.of(
			LOOPBACK_HOST,
			"localhost",
			"::1",
			"0:0:0:0:0:0:0:1"
	);
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

	private AgentConfigLoader() {
	}

	public static Path defaultConfigPath() {
		return FabricLoader.getInstance().getConfigDir().resolve(CONFIG_FILE_NAME);
	}

	public static AgentConfig loadOrCreate() throws IOException {
		return loadOrCreate(defaultConfigPath());
	}

	public static AgentConfig loadOrCreate(Path path) throws IOException {
		Path configPath = requirePath(path);
		if (Files.exists(configPath)) {
			return load(configPath);
		}

		Path parent = configPath.getParent();
		if (parent != null) {
			Files.createDirectories(parent);
		}
		String defaultJson = GSON.toJson(AgentConfig.defaults()) + System.lineSeparator();
		try {
			Files.writeString(
					configPath,
					defaultJson,
					StandardCharsets.UTF_8,
					StandardOpenOption.CREATE_NEW,
					StandardOpenOption.WRITE
			);
			return AgentConfig.defaults();
		} catch (FileAlreadyExistsException exception) {
			return load(configPath);
		}
	}

	public static AgentConfig load(Path path) throws IOException {
		Path configPath = requirePath(path);
		long size = Files.size(configPath);
		if (size > MAX_CONFIG_BYTES) {
			throw new ProtocolException(
					ProtocolConstants.ERROR_OUT_OF_RANGE,
					"Agent config exceeds maximum of " + MAX_CONFIG_BYTES + " UTF-8 bytes"
			);
		}
		return parse(Files.readString(configPath, StandardCharsets.UTF_8));
	}

	public static AgentConfig parse(String json) throws ProtocolException {
		JsonObject object = parseObject(json);
		validateKnownFields(object);
		validateOptionalHost(object);
		return new AgentConfig(
				requireString(object, FIELD_AGENT_ID),
				requireInteger(object, FIELD_BRIDGE_PORT),
				requireInteger(object, FIELD_OBSERVATION_RADIUS),
				requireBoolean(object, FIELD_ENABLED)
		);
	}

	private static Path requirePath(Path path) {
		if (path == null) {
			throw new ProtocolException(ProtocolConstants.ERROR_INVALID_FIELD, "Config path must not be null");
		}
		return path.toAbsolutePath().normalize();
	}

	private static JsonObject parseObject(String json) {
		if (json == null) {
			throw new ProtocolException(ProtocolConstants.ERROR_INVALID_FIELD, "Agent config JSON must not be null");
		}
		if (json.getBytes(StandardCharsets.UTF_8).length > MAX_CONFIG_BYTES) {
			throw new ProtocolException(
					ProtocolConstants.ERROR_OUT_OF_RANGE,
					"Agent config exceeds maximum of " + MAX_CONFIG_BYTES + " UTF-8 bytes"
			);
		}
		try {
			JsonElement parsed = JsonParser.parseString(json);
			if (!parsed.isJsonObject()) {
				throw new ProtocolException(ProtocolConstants.ERROR_MALFORMED_JSON, "Agent config JSON must be an object");
			}
			return parsed.getAsJsonObject();
		} catch (JsonParseException exception) {
			throw new ProtocolException(
					ProtocolConstants.ERROR_MALFORMED_JSON,
					"Malformed agent config JSON: " + exception.getMessage(),
					exception
			);
		}
	}

	private static void validateKnownFields(JsonObject object) {
		for (String field : object.keySet()) {
			if (!ALLOWED_FIELDS.contains(field)) {
				throw new ProtocolException(
						ProtocolConstants.ERROR_UNKNOWN_FIELD,
						"Unknown agent config field '" + field + "'"
				);
			}
		}
	}

	private static void validateOptionalHost(JsonObject object) {
		if (!object.has(FIELD_HOST)) {
			return;
		}
		String host = requireString(object, FIELD_HOST);
		if (!isLoopbackHost(host)) {
			throw new ProtocolException(
					"LOOPBACK_REQUIRED",
					"Bridge host must be loopback; the server binds to " + LOOPBACK_HOST
			);
		}
	}

	private static boolean isLoopbackHost(String host) {
		String normalized = host.toLowerCase(Locale.ROOT);
		if (LOOPBACK_HOST_NAMES.contains(normalized)) {
			return true;
		}

		String[] octets = normalized.split("\\.", -1);
		if (octets.length != 4) {
			return false;
		}
		try {
			if (Integer.parseInt(octets[0]) != 127) {
				return false;
			}
			for (int index = 1; index < octets.length; index++) {
				int value = Integer.parseInt(octets[index]);
				if (value < 0 || value > 255) {
					return false;
				}
			}
			return true;
		} catch (NumberFormatException exception) {
			return false;
		}
	}

	private static String requireString(JsonObject object, String field) {
		JsonElement element = requireField(object, field);
		if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()) {
			throw invalidField(field, "a string");
		}
		return element.getAsString();
	}

	private static int requireInteger(JsonObject object, String field) {
		JsonElement element = requireField(object, field);
		if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isNumber()) {
			throw invalidField(field, "an integer");
		}
		try {
			return new BigDecimal(element.getAsString()).intValueExact();
		} catch (ArithmeticException | NumberFormatException exception) {
			throw invalidField(field, "an integer");
		}
	}

	private static boolean requireBoolean(JsonObject object, String field) {
		JsonElement element = requireField(object, field);
		if (!element.isJsonPrimitive()) {
			throw invalidField(field, "a boolean");
		}
		JsonPrimitive primitive = element.getAsJsonPrimitive();
		if (!primitive.isBoolean()) {
			throw invalidField(field, "a boolean");
		}
		return primitive.getAsBoolean();
	}

	private static JsonElement requireField(JsonObject object, String field) {
		if (!object.has(field) || object.get(field).isJsonNull()) {
			throw new ProtocolException(
					ProtocolConstants.ERROR_MISSING_FIELD,
					"Required agent config field '" + field + "' is missing"
			);
		}
		return object.get(field);
	}

	private static ProtocolException invalidField(String field, String expectedType) {
		return new ProtocolException(
				ProtocolConstants.ERROR_INVALID_FIELD,
				"Agent config field '" + field + "' must be " + expectedType
		);
	}
}
