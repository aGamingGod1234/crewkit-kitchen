package dev.agaminggod.arenaagents.crewkit.flow;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * Minimal QR Code encoder: byte mode, error correction L or M, versions 1-40.
 *
 * Adapted from Project Nayuki's "QR Code generator library" (Java), MIT License.
 * Copyright (c) Project Nayuki. https://www.nayuki.io/page/qr-code-generator-library
 * Permission is hereby granted, free of charge, to any person obtaining a copy of this software and
 * associated documentation files, to deal in the Software without restriction, subject to the
 * condition that the above copyright notice and this permission notice are included in all copies.
 * The Software is provided "as is", without warranty of any kind.
 */
public final class QrEncoder {
	public enum Ecc {
		L(0, 1), M(1, 0);
		final int index;
		final int formatBits;
		Ecc(int index, int formatBits) { this.index = index; this.formatBits = formatBits; }
	}

	private static final byte[][] ECC_CODEWORDS_PER_BLOCK = {
		{-1, 7, 10, 15, 20, 26, 18, 20, 24, 30, 18, 20, 24, 26, 30, 22, 24, 28, 30, 28, 28, 28, 28, 30, 30, 26, 28, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30},
		{-1, 10, 16, 26, 18, 24, 16, 18, 22, 22, 26, 30, 22, 22, 24, 24, 28, 28, 26, 26, 26, 26, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28},
	};
	private static final byte[][] NUM_ERROR_CORRECTION_BLOCKS = {
		{-1, 1, 1, 1, 1, 1, 2, 2, 2, 2, 4, 4, 4, 4, 4, 6, 6, 6, 6, 7, 8, 8, 9, 9, 10, 12, 12, 12, 13, 14, 15, 16, 17, 18, 19, 19, 20, 21, 22, 24, 25},
		{-1, 1, 1, 1, 2, 2, 4, 4, 4, 5, 5, 5, 8, 9, 9, 10, 10, 11, 13, 14, 16, 17, 17, 18, 20, 21, 23, 25, 26, 28, 29, 31, 33, 35, 37, 38, 40, 43, 45, 47, 49},
	};

	private final int version;
	private final int size;
	private final Ecc ecc;
	private final boolean[][] modules;
	private final boolean[][] isFunction;

	private QrEncoder(int version, Ecc ecc) {
		this.version = version;
		this.ecc = ecc;
		this.size = version * 4 + 17;
		this.modules = new boolean[size][size];
		this.isFunction = new boolean[size][size];
	}

	/** Encodes text as UTF-8 bytes. Returns modules[y][x], true = dark. No quiet zone. */
	public static boolean[][] encode(String text, Ecc ecc) {
		return encode(text, ecc, -1);
	}

	/** forcedMask in 0..7, or -1 to pick the lowest-penalty mask. */
	static boolean[][] encode(String text, Ecc ecc, int forcedMask) {
		byte[] data = text.getBytes(StandardCharsets.UTF_8);
		int version = 1;
		for (; ; version++) {
			if (version > 40) throw new IllegalArgumentException("Text too long for a QR code: " + data.length + " bytes");
			int capacityBits = numDataCodewords(version, ecc) * 8;
			int usedBits = 4 + (version < 10 ? 8 : 16) + data.length * 8;
			if (usedBits <= capacityBits) break;
		}
		int capacityBits = numDataCodewords(version, ecc) * 8;
		BitBuffer bb = new BitBuffer(capacityBits);
		bb.append(0b0100, 4);
		bb.append(data.length, version < 10 ? 8 : 16);
		for (byte b : data) bb.append(b & 0xFF, 8);
		bb.append(0, Math.min(4, capacityBits - bb.length));
		bb.append(0, (8 - bb.length % 8) % 8);
		for (int pad = 0xEC; bb.length < capacityBits; pad ^= 0xEC ^ 0x11) bb.append(pad, 8);
		byte[] dataCodewords = new byte[capacityBits / 8];
		for (int i = 0; i < bb.length; i++) {
			if (bb.bits[i]) dataCodewords[i >>> 3] |= (byte) (1 << (7 - (i & 7)));
		}

		QrEncoder qr = new QrEncoder(version, ecc);
		qr.drawFunctionPatterns();
		qr.drawCodewords(qr.addEccAndInterleave(dataCodewords));
		int mask = forcedMask;
		if (mask < 0) {
			int minPenalty = Integer.MAX_VALUE;
			for (int i = 0; i < 8; i++) {
				qr.applyMask(i);
				qr.drawFormatBits(i);
				int penalty = qr.penaltyScore();
				if (penalty < minPenalty) {
					mask = i;
					minPenalty = penalty;
				}
				qr.applyMask(i);
			}
		}
		qr.applyMask(mask);
		qr.drawFormatBits(mask);
		return qr.modules;
	}

	private static final class BitBuffer {
		final boolean[] bits;
		int length;
		BitBuffer(int capacity) { bits = new boolean[capacity]; }
		void append(int val, int len) {
			for (int i = len - 1; i >= 0; i--) bits[length++] = ((val >>> i) & 1) != 0;
		}
	}

	private void drawFunctionPatterns() {
		for (int i = 0; i < size; i++) {
			setFunctionModule(6, i, i % 2 == 0);
			setFunctionModule(i, 6, i % 2 == 0);
		}
		drawFinderPattern(3, 3);
		drawFinderPattern(size - 4, 3);
		drawFinderPattern(3, size - 4);
		int[] align = alignmentPatternPositions();
		int n = align.length;
		for (int i = 0; i < n; i++) {
			for (int j = 0; j < n; j++) {
				if (!(i == 0 && j == 0 || i == 0 && j == n - 1 || i == n - 1 && j == 0)) {
					drawAlignmentPattern(align[i], align[j]);
				}
			}
		}
		drawFormatBits(0);
		drawVersion();
	}

	private void drawFormatBits(int mask) {
		int data = ecc.formatBits << 3 | mask;
		int rem = data;
		for (int i = 0; i < 10; i++) rem = (rem << 1) ^ ((rem >>> 9) * 0x537);
		int bits = (data << 10 | rem) ^ 0x5412;
		for (int i = 0; i <= 5; i++) setFunctionModule(8, i, bit(bits, i));
		setFunctionModule(8, 7, bit(bits, 6));
		setFunctionModule(8, 8, bit(bits, 7));
		setFunctionModule(7, 8, bit(bits, 8));
		for (int i = 9; i < 15; i++) setFunctionModule(14 - i, 8, bit(bits, i));
		for (int i = 0; i < 8; i++) setFunctionModule(size - 1 - i, 8, bit(bits, i));
		for (int i = 8; i < 15; i++) setFunctionModule(8, size - 15 + i, bit(bits, i));
		setFunctionModule(8, size - 8, true);
	}

	private void drawVersion() {
		if (version < 7) return;
		int rem = version;
		for (int i = 0; i < 12; i++) rem = (rem << 1) ^ ((rem >>> 11) * 0x1F25);
		int bits = version << 12 | rem;
		for (int i = 0; i < 18; i++) {
			boolean b = bit(bits, i);
			int a = size - 11 + i % 3;
			int c = i / 3;
			setFunctionModule(a, c, b);
			setFunctionModule(c, a, b);
		}
	}

	private void drawFinderPattern(int x, int y) {
		for (int dy = -4; dy <= 4; dy++) {
			for (int dx = -4; dx <= 4; dx++) {
				int dist = Math.max(Math.abs(dx), Math.abs(dy));
				int xx = x + dx;
				int yy = y + dy;
				if (0 <= xx && xx < size && 0 <= yy && yy < size) setFunctionModule(xx, yy, dist != 2 && dist != 4);
			}
		}
	}

	private void drawAlignmentPattern(int x, int y) {
		for (int dy = -2; dy <= 2; dy++) {
			for (int dx = -2; dx <= 2; dx++) setFunctionModule(x + dx, y + dy, Math.max(Math.abs(dx), Math.abs(dy)) != 1);
		}
	}

	private void setFunctionModule(int x, int y, boolean dark) {
		modules[y][x] = dark;
		isFunction[y][x] = true;
	}

	private byte[] addEccAndInterleave(byte[] data) {
		int numBlocks = NUM_ERROR_CORRECTION_BLOCKS[ecc.index][version];
		int blockEccLen = ECC_CODEWORDS_PER_BLOCK[ecc.index][version];
		int rawCodewords = numRawDataModules(version) / 8;
		int numShortBlocks = numBlocks - rawCodewords % numBlocks;
		int shortBlockLen = rawCodewords / numBlocks;
		byte[][] blocks = new byte[numBlocks][];
		byte[] divisor = rsDivisor(blockEccLen);
		for (int i = 0, k = 0; i < numBlocks; i++) {
			byte[] dat = Arrays.copyOfRange(data, k, k + shortBlockLen - blockEccLen + (i < numShortBlocks ? 0 : 1));
			k += dat.length;
			byte[] block = Arrays.copyOf(dat, shortBlockLen + 1);
			byte[] eccBytes = rsRemainder(dat, divisor);
			System.arraycopy(eccBytes, 0, block, block.length - blockEccLen, eccBytes.length);
			blocks[i] = block;
		}
		byte[] result = new byte[rawCodewords];
		for (int i = 0, k = 0; i < blocks[0].length; i++) {
			for (int j = 0; j < blocks.length; j++) {
				if (i != shortBlockLen - blockEccLen || j >= numShortBlocks) result[k++] = blocks[j][i];
			}
		}
		return result;
	}

	private void drawCodewords(byte[] data) {
		int i = 0;
		for (int right = size - 1; right >= 1; right -= 2) {
			if (right == 6) right = 5;
			for (int vert = 0; vert < size; vert++) {
				for (int j = 0; j < 2; j++) {
					int x = right - j;
					boolean upward = ((right + 1) & 2) == 0;
					int y = upward ? size - 1 - vert : vert;
					if (!isFunction[y][x] && i < data.length * 8) {
						modules[y][x] = bit(data[i >>> 3], 7 - (i & 7));
						i++;
					}
				}
			}
		}
	}

	private void applyMask(int mask) {
		for (int y = 0; y < size; y++) {
			for (int x = 0; x < size; x++) {
				boolean invert = switch (mask) {
					case 0 -> (x + y) % 2 == 0;
					case 1 -> y % 2 == 0;
					case 2 -> x % 3 == 0;
					case 3 -> (x + y) % 3 == 0;
					case 4 -> (x / 3 + y / 2) % 2 == 0;
					case 5 -> x * y % 2 + x * y % 3 == 0;
					case 6 -> (x * y % 2 + x * y % 3) % 2 == 0;
					default -> ((x + y) % 2 + x * y % 3) % 2 == 0;
				};
				modules[y][x] ^= invert & !isFunction[y][x];
			}
		}
	}

	/** Standard ISO 18004 penalty (rules 1-4). Any mask yields a valid code; this only picks a cleaner one. */
	private int penaltyScore() {
		int result = 0;
		for (int pass = 0; pass < 2; pass++) {
			for (int a = 0; a < size; a++) {
				int run = 1;
				for (int b = 1; b < size; b++) {
					boolean cur = pass == 0 ? modules[a][b] : modules[b][a];
					boolean prev = pass == 0 ? modules[a][b - 1] : modules[b - 1][a];
					if (cur == prev) {
						run++;
						if (run == 5) result += 3;
						else if (run > 5) result++;
					} else {
						run = 1;
					}
				}
			}
		}
		for (int y = 0; y < size - 1; y++) {
			for (int x = 0; x < size - 1; x++) {
				boolean c = modules[y][x];
				if (c == modules[y][x + 1] && c == modules[y + 1][x] && c == modules[y + 1][x + 1]) result += 3;
			}
		}
		boolean[] p1 = {true, false, true, true, true, false, true, false, false, false, false};
		boolean[] p2 = {false, false, false, false, true, false, true, true, true, false, true};
		for (int a = 0; a < size; a++) {
			for (int b = 0; b + 11 <= size; b++) {
				boolean h1 = true, h2 = true, v1 = true, v2 = true;
				for (int k = 0; k < 11; k++) {
					boolean h = modules[a][b + k];
					boolean v = modules[b + k][a];
					h1 &= h == p1[k];
					h2 &= h == p2[k];
					v1 &= v == p1[k];
					v2 &= v == p2[k];
				}
				if (h1) result += 40;
				if (h2) result += 40;
				if (v1) result += 40;
				if (v2) result += 40;
			}
		}
		int dark = 0;
		for (boolean[] row : modules) for (boolean m : row) if (m) dark++;
		int total = size * size;
		int k = (Math.abs(dark * 20 - total * 10) + total - 1) / total - 1;
		result += Math.max(0, k) * 10;
		return result;
	}

	private int[] alignmentPatternPositions() {
		if (version == 1) return new int[0];
		int numAlign = version / 7 + 2;
		int step = version == 32 ? 26 : (version * 4 + numAlign * 2 + 1) / (numAlign * 2 - 2) * 2;
		int[] result = new int[numAlign];
		result[0] = 6;
		for (int i = numAlign - 1, pos = size - 7; i >= 1; i--, pos -= step) result[i] = pos;
		return result;
	}

	private static int numRawDataModules(int ver) {
		int result = (16 * ver + 128) * ver + 64;
		if (ver >= 2) {
			int numAlign = ver / 7 + 2;
			result -= (25 * numAlign - 10) * numAlign - 55;
			if (ver >= 7) result -= 36;
		}
		return result;
	}

	private static int numDataCodewords(int ver, Ecc ecc) {
		return numRawDataModules(ver) / 8
			- ECC_CODEWORDS_PER_BLOCK[ecc.index][ver] * NUM_ERROR_CORRECTION_BLOCKS[ecc.index][ver];
	}

	private static byte[] rsDivisor(int degree) {
		byte[] result = new byte[degree];
		result[degree - 1] = 1;
		int root = 1;
		for (int i = 0; i < degree; i++) {
			for (int j = 0; j < result.length; j++) {
				result[j] = (byte) gfMultiply(result[j] & 0xFF, root);
				if (j + 1 < result.length) result[j] ^= result[j + 1];
			}
			root = gfMultiply(root, 0x02);
		}
		return result;
	}

	private static byte[] rsRemainder(byte[] data, byte[] divisor) {
		byte[] result = new byte[divisor.length];
		for (byte b : data) {
			int factor = (b ^ result[0]) & 0xFF;
			System.arraycopy(result, 1, result, 0, result.length - 1);
			result[result.length - 1] = 0;
			for (int i = 0; i < result.length; i++) result[i] ^= (byte) gfMultiply(divisor[i] & 0xFF, factor);
		}
		return result;
	}

	private static int gfMultiply(int x, int y) {
		int z = 0;
		for (int i = 7; i >= 0; i--) {
			z = (z << 1) ^ ((z >>> 7) * 0x11D);
			z ^= ((y >>> i) & 1) * x;
		}
		return z;
	}

	private static boolean bit(int x, int i) {
		return ((x >>> i) & 1) != 0;
	}

	/** Dev check: prints the matrix as 0/1 rows. Usage: java QrEncoder.java <L|M> <mask> <text> */
	public static void main(String[] args) {
		boolean[][] m = encode(args[2], Ecc.valueOf(args[0]), Integer.parseInt(args[1]));
		StringBuilder sb = new StringBuilder();
		for (boolean[] row : m) {
			for (boolean b : row) sb.append(b ? '1' : '0');
			sb.append('\n');
		}
		System.out.print(sb);
	}
}
