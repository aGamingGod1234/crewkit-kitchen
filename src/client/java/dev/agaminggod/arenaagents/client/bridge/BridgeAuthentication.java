package dev.agaminggod.arenaagents.client.bridge;

import dev.agaminggod.arenaagents.protocol.ProtocolConstants;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** HMAC proofs for the legacy bridge handshake; the installation secret never crosses the socket. */
public final class BridgeAuthentication {
	public static final int NONCE_BYTES = 32;
	public static final int PROOF_BYTES = 32;
	public static final int TOKEN_LENGTH = 43;

	private static final String HMAC_ALGORITHM = "HmacSHA256";
	private static final String CONTEXT = "arenaagents-legacy-bridge-v2";
	private static final String COORDINATOR_PROOF = "coordinator-proof";
	private static final String BRIDGE_PROOF = "bridge-proof";
	private static final SecureRandom RANDOM = new SecureRandom();

	private BridgeAuthentication() {
	}

	public static String newNonce() {
		byte[] nonce = new byte[NONCE_BYTES];
		RANDOM.nextBytes(nonce);
		return Base64.getUrlEncoder().withoutPadding().encodeToString(nonce);
	}

	public static String coordinatorProof(
			String secret,
			String agentId,
			String challengeMessageId,
			String responseMessageId,
			String bridgeNonce,
			String coordinatorNonce
	) {
		return proof(
				secret,
				COORDINATOR_PROOF,
				agentId,
				challengeMessageId,
				responseMessageId,
				bridgeNonce,
				coordinatorNonce
		);
	}

	public static String bridgeProof(
			String secret,
			String agentId,
			String challengeMessageId,
			String responseMessageId,
			String bridgeNonce,
			String coordinatorNonce
	) {
		return proof(
				secret,
				BRIDGE_PROOF,
				agentId,
				challengeMessageId,
				responseMessageId,
				bridgeNonce,
				coordinatorNonce
		);
	}

	public static boolean isNonce(String value) {
		return isCanonicalToken(value, NONCE_BYTES);
	}

	public static boolean isProof(String value) {
		return isCanonicalToken(value, PROOF_BYTES);
	}

	public static boolean proofsMatch(String expected, String supplied) {
		if (!isProof(expected) || !isProof(supplied)) {
			return false;
		}
		return MessageDigest.isEqual(
				expected.getBytes(StandardCharsets.US_ASCII),
				supplied.getBytes(StandardCharsets.US_ASCII)
		);
	}

	private static String proof(String secret, String role, String... fields) {
		try {
			Mac mac = Mac.getInstance(HMAC_ALGORITHM);
			mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), HMAC_ALGORITHM));
			mac.update(encode(CONTEXT));
			mac.update(encode(role));
			mac.update(encode(Integer.toString(ProtocolConstants.PROTOCOL_VERSION)));
			for (String field : fields) {
				mac.update(encode(field));
			}
			return Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal());
		} catch (GeneralSecurityException exception) {
			throw new IllegalStateException("HMAC-SHA-256 is unavailable", exception);
		}
	}

	private static byte[] encode(String value) {
		byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
		ByteArrayOutputStream output = new ByteArrayOutputStream(Integer.BYTES + bytes.length);
		output.writeBytes(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
		output.writeBytes(bytes);
		return output.toByteArray();
	}

	private static boolean isCanonicalToken(String value, int expectedBytes) {
		if (value == null || value.length() != TOKEN_LENGTH) {
			return false;
		}
		try {
			byte[] decoded = Base64.getUrlDecoder().decode(value);
			return decoded.length == expectedBytes
					&& Base64.getUrlEncoder().withoutPadding().encodeToString(decoded).equals(value);
		} catch (IllegalArgumentException exception) {
			return false;
		}
	}
}
