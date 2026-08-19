import {
	existsSync,
	mkdirSync,
	readFileSync,
	readdirSync,
	rmSync,
	writeFileSync,
} from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { deflateSync } from 'node:zlib';

const WIDTH = 64;
const HEIGHT = 64;
const CHANNELS = 4;
const EXPECTED_PROVIDER_COUNT = 4;
const EXPECTED_FAMILY_COUNT = 4;
const EXPECTED_VARIANT_COUNT = 4;
const PNG_SIGNATURE = Buffer.from([137, 80, 78, 71, 13, 10, 26, 10]);
const TEXTURE_PREFIX = 'arenaagents:textures/entity/';
const SCRIPT_DIRECTORY = path.dirname(fileURLToPath(import.meta.url));
const PROJECT_DIRECTORY = path.resolve(SCRIPT_DIRECTORY, '..');
const MANIFEST_PATH = path.join(
	PROJECT_DIRECTORY,
	'src',
	'main',
	'resources',
	'assets',
	'arenaagents',
	'identity',
	'agent_visual_manifest.json',
);
const OUTPUT_DIRECTORY = path.join(
	PROJECT_DIRECTORY,
	'src',
	'main',
	'resources',
	'assets',
	'arenaagents',
	'textures',
	'entity',
);

const PALETTES = Object.freeze({
	codex: Object.freeze({
		dark: Object.freeze([17, 24, 28, 255]),
		base: Object.freeze([31, 42, 47, 255]),
		mid: Object.freeze([72, 94, 99, 255]),
		light: Object.freeze([211, 229, 228, 255]),
		accent: Object.freeze([24, 202, 196, 255]),
	}),
	gemini: Object.freeze({
		dark: Object.freeze([27, 35, 53, 255]),
		base: Object.freeze([80, 99, 137, 255]),
		mid: Object.freeze([142, 166, 211, 255]),
		light: Object.freeze([235, 240, 249, 255]),
		accent: Object.freeze([91, 139, 235, 255]),
	}),
	kimi: Object.freeze({
		dark: Object.freeze([20, 18, 35, 255]),
		base: Object.freeze([45, 39, 70, 255]),
		mid: Object.freeze([105, 94, 151, 255]),
		light: Object.freeze([229, 225, 243, 255]),
		accent: Object.freeze([144, 116, 235, 255]),
	}),
	cursor: Object.freeze({
		dark: Object.freeze([18, 20, 20, 255]),
		base: Object.freeze([39, 43, 42, 255]),
		mid: Object.freeze([104, 111, 101, 255]),
		light: Object.freeze([226, 231, 213, 255]),
		accent: Object.freeze([205, 220, 69, 255]),
	}),
});

const CUBOID_FACES = Object.freeze([
	[8, 0, 8, 8], [16, 0, 8, 8], [0, 8, 8, 8], [8, 8, 8, 8], [16, 8, 8, 8], [24, 8, 8, 8],
	[20, 16, 8, 4], [28, 16, 8, 4], [16, 20, 4, 12], [20, 20, 8, 12], [28, 20, 4, 12], [32, 20, 8, 12],
	[44, 16, 4, 4], [48, 16, 4, 4], [40, 20, 4, 12], [44, 20, 4, 12], [48, 20, 4, 12], [52, 20, 4, 12],
	[4, 16, 4, 4], [8, 16, 4, 4], [0, 20, 4, 12], [4, 20, 4, 12], [8, 20, 4, 12], [12, 20, 4, 12],
	[20, 48, 4, 4], [24, 48, 4, 4], [16, 52, 4, 12], [20, 52, 4, 12], [24, 52, 4, 12], [28, 52, 4, 12],
	[36, 48, 4, 4], [40, 48, 4, 4], [32, 52, 4, 12], [36, 52, 4, 12], [40, 52, 4, 12], [44, 52, 4, 12],
]);

function loadOutputs() {
	const manifest = JSON.parse(readFileSync(MANIFEST_PATH, 'utf8'));
	if (manifest.schemaVersion !== 1) throw new Error('Manifest schemaVersion must be 1');
	if (!Array.isArray(manifest.providers) || manifest.providers.length !== EXPECTED_PROVIDER_COUNT) {
		throw new Error(`Manifest must declare exactly ${EXPECTED_PROVIDER_COUNT} providers`);
	}

	const outputs = [];
	const providerKeys = new Set();
	const textureNames = new Set();
	for (const [providerIndex, provider] of manifest.providers.entries()) {
		if (typeof provider.key !== 'string' || !PALETTES[provider.key]) {
			throw new Error(`Manifest provider ${providerIndex} has no generator chassis`);
		}
		if (providerKeys.has(provider.key)) throw new Error(`Duplicate manifest provider ${provider.key}`);
		providerKeys.add(provider.key);
		if (!Array.isArray(provider.families) || provider.families.length !== EXPECTED_FAMILY_COUNT) {
			throw new Error(`Manifest provider ${provider.key} must declare exactly ${EXPECTED_FAMILY_COUNT} families`);
		}

		const familyKeys = new Set();
		for (const [familyIndex, family] of provider.families.entries()) {
			if (typeof family.key !== 'string' || familyKeys.has(family.key)) {
				throw new Error(`Manifest provider ${provider.key} has an invalid or duplicate family key`);
			}
			familyKeys.add(family.key);
			if (!Array.isArray(family.variants) || family.variants.length !== EXPECTED_VARIANT_COUNT) {
				throw new Error(`Manifest family ${provider.key}/${family.key} must declare exactly ${EXPECTED_VARIANT_COUNT} variants`);
			}
			for (const [variantIndex, variant] of family.variants.entries()) {
				const texturePath = variant.texturePath;
				if (typeof texturePath !== 'string' || !texturePath.startsWith(TEXTURE_PREFIX)) {
					throw new Error(`Manifest family ${provider.key}/${family.key} has an invalid texture path`);
				}
				const textureName = texturePath.slice(TEXTURE_PREFIX.length);
				if (path.basename(textureName) !== textureName || !textureName.endsWith('.png')) {
					throw new Error(`Manifest texture ${texturePath} must be a direct entity PNG`);
				}
				if (textureNames.has(textureName)) throw new Error(`Duplicate manifest texture ${texturePath}`);
				textureNames.add(textureName);
				outputs.push(Object.freeze({
					providerKey: provider.key,
					providerIndex,
					familyKey: family.key,
					familyIndex,
					variantIndex,
					textureName,
				}));
			}
		}
	}

	const expectedCount = EXPECTED_PROVIDER_COUNT * EXPECTED_FAMILY_COUNT * EXPECTED_VARIANT_COUNT;
	if (outputs.length !== expectedCount) {
		throw new Error(`Manifest output count drift: expected ${expectedCount}, found ${outputs.length}`);
	}
	return outputs;
}

function createPixels() {
	return Buffer.alloc(WIDTH * HEIGHT * CHANNELS);
}

function setPixel(pixels, x, y, color) {
	if (x < 0 || x >= WIDTH || y < 0 || y >= HEIGHT) return;
	const offset = ((y * WIDTH) + x) * CHANNELS;
	for (let channel = 0; channel < CHANNELS; channel += 1) pixels[offset + channel] = color[channel];
}

function fillRect(pixels, x, y, width, height, color) {
	for (let row = y; row < y + height; row += 1) {
		for (let column = x; column < x + width; column += 1) setPixel(pixels, column, row, color);
	}
}

function drawLine(pixels, startX, startY, endX, endY, color, thickness = 1) {
	const steps = Math.max(Math.abs(endX - startX), Math.abs(endY - startY));
	for (let step = 0; step <= steps; step += 1) {
		const x = Math.round(startX + ((endX - startX) * step) / steps);
		const y = Math.round(startY + ((endY - startY) * step) / steps);
		fillRect(pixels, x, y, thickness, thickness, color);
	}
}

function drawBase(pixels, palette) {
	for (const [x, y, width, height] of CUBOID_FACES) fillRect(pixels, x, y, width, height, palette.base);
	for (const [x, y, width, height] of [
		[0, 8, 8, 8], [16, 8, 8, 8],
		[16, 20, 4, 12], [28, 20, 4, 12],
		[40, 20, 4, 12], [48, 20, 4, 12],
		[0, 20, 4, 12], [8, 20, 4, 12],
		[16, 52, 4, 12], [24, 52, 4, 12],
		[32, 52, 4, 12], [40, 52, 4, 12],
	]) fillRect(pixels, x, y, width, height, palette.dark);
	fillRect(pixels, 8, 8, 8, 2, palette.mid);
	fillRect(pixels, 20, 20, 8, 2, palette.mid);
	fillRect(pixels, 32, 20, 8, 2, palette.mid);
}

function drawCodexChassis(pixels, palette) {
	// Squared helmet and split visor.
	fillRect(pixels, 8, 8, 8, 2, palette.light);
	fillRect(pixels, 8, 10, 2, 5, palette.mid);
	fillRect(pixels, 14, 10, 2, 5, palette.mid);
	fillRect(pixels, 9, 11, 2, 2, palette.accent);
	fillRect(pixels, 13, 11, 2, 2, palette.accent);
	fillRect(pixels, 11, 11, 2, 2, palette.dark);
	fillRect(pixels, 25, 9, 6, 2, palette.light);
	fillRect(pixels, 27, 11, 2, 4, palette.mid);

	// Open-knot torso construction on front and back.
	for (const originX of [20, 32]) {
		fillRect(pixels, originX, 20, 3, 2, palette.light);
		fillRect(pixels, originX + 5, 20, 3, 2, palette.light);
		fillRect(pixels, originX, 22, 2, 4, palette.light);
		fillRect(pixels, originX + 6, 22, 2, 4, palette.light);
		fillRect(pixels, originX + 1, 26, 3, 2, palette.light);
		fillRect(pixels, originX + 4, 26, 3, 2, palette.accent);
		fillRect(pixels, originX + 3, 24, 2, 2, palette.dark);
		fillRect(pixels, originX + 2, 28, 4, 2, palette.accent);
	}
	// Broad symmetric shoulder frame on both arms.
	fillRect(pixels, 44, 20, 4, 4, palette.light);
	fillRect(pixels, 36, 52, 4, 4, palette.light);
	fillRect(pixels, 52, 20, 4, 3, palette.mid);
	fillRect(pixels, 44, 52, 4, 3, palette.mid);
	fillRect(pixels, 44, 24, 1, 6, palette.accent);
	fillRect(pixels, 39, 56, 1, 6, palette.accent);
}

function drawFourPointMark(pixels, originX, originY, color) {
	fillRect(pixels, originX + 2, originY, 2, 6, color);
	fillRect(pixels, originX, originY + 2, 6, 2, color);
	fillRect(pixels, originX + 1, originY + 1, 4, 4, color);
}

function drawGeminiChassis(pixels, palette) {
	// Four-point face mark on a light upper body.
	fillRect(pixels, 8, 8, 8, 8, palette.mid);
	drawFourPointMark(pixels, 9, 9, palette.light);
	fillRect(pixels, 11, 11, 2, 2, palette.accent);
	fillRect(pixels, 24, 8, 8, 8, palette.light);
	drawFourPointMark(pixels, 25, 9, palette.mid);

	// Mirrored quadrant torso construction.
	fillRect(pixels, 20, 20, 4, 6, palette.light);
	fillRect(pixels, 24, 20, 4, 6, palette.mid);
	fillRect(pixels, 20, 26, 4, 6, palette.mid);
	fillRect(pixels, 24, 26, 4, 6, palette.light);
	fillRect(pixels, 32, 20, 4, 6, palette.mid);
	fillRect(pixels, 36, 20, 4, 6, palette.light);
	fillRect(pixels, 32, 26, 4, 6, palette.light);
	fillRect(pixels, 36, 26, 4, 6, palette.mid);
	fillRect(pixels, 23, 23, 2, 2, palette.accent);
	fillRect(pixels, 35, 23, 2, 2, palette.accent);

	// Bright diagonal shoulder structure.
	drawLine(pixels, 44, 20, 46, 24, palette.light, 2);
	drawLine(pixels, 38, 52, 36, 56, palette.light, 2);
	drawLine(pixels, 52, 20, 54, 24, palette.accent, 2);
	drawLine(pixels, 46, 52, 44, 56, palette.accent, 2);
}

function drawKimiChassis(pixels, palette) {
	// Hooded edge and crescent face asymmetry.
	fillRect(pixels, 8, 8, 3, 8, palette.light);
	fillRect(pixels, 11, 8, 5, 8, palette.dark);
	fillRect(pixels, 10, 9, 4, 6, palette.accent);
	fillRect(pixels, 12, 9, 3, 5, palette.dark);
	fillRect(pixels, 9, 10, 2, 4, palette.light);
	fillRect(pixels, 24, 8, 5, 8, palette.dark);
	fillRect(pixels, 29, 8, 3, 8, palette.light);
	fillRect(pixels, 25, 10, 4, 4, palette.accent);

	// Crescent/arc torso and high-contrast side panel.
	fillRect(pixels, 20, 20, 3, 12, palette.light);
	fillRect(pixels, 23, 20, 5, 12, palette.dark);
	fillRect(pixels, 22, 21, 5, 9, palette.accent);
	fillRect(pixels, 24, 21, 4, 7, palette.dark);
	fillRect(pixels, 21, 23, 2, 5, palette.light);
	fillRect(pixels, 32, 20, 5, 12, palette.dark);
	fillRect(pixels, 37, 20, 3, 12, palette.light);
	fillRect(pixels, 33, 22, 4, 7, palette.accent);
	fillRect(pixels, 35, 22, 3, 5, palette.dark);
	fillRect(pixels, 44, 20, 2, 12, palette.light);
	fillRect(pixels, 38, 52, 2, 12, palette.light);
	fillRect(pixels, 52, 20, 2, 12, palette.dark);
	fillRect(pixels, 44, 52, 2, 12, palette.dark);
}

function drawCursorChassis(pixels, palette) {
	// Angled helmet split; intentionally unrelated to Codex's squared visor.
	fillRect(pixels, 8, 8, 8, 8, palette.dark);
	for (let row = 0; row < 8; row += 1) {
		const split = Math.min(7, row + 1);
		fillRect(pixels, 8, 8 + row, split, 1, palette.mid);
		fillRect(pixels, 8 + split, 8 + row, 1, 1, palette.accent);
	}
	fillRect(pixels, 9, 9, 2, 2, palette.light);
	fillRect(pixels, 24, 8, 8, 8, palette.dark);
	drawLine(pixels, 24, 9, 30, 15, palette.accent, 1);
	fillRect(pixels, 24, 13, 3, 3, palette.mid);

	// Arrow-cursor torso cut on front and back.
	for (const originX of [20, 32]) {
		fillRect(pixels, originX, 20, 8, 12, palette.mid);
		fillRect(pixels, originX + 1, 21, 2, 8, palette.light);
		fillRect(pixels, originX + 3, 23, 2, 2, palette.light);
		fillRect(pixels, originX + 5, 25, 2, 2, palette.light);
		fillRect(pixels, originX + 3, 27, 2, 4, palette.light);
		fillRect(pixels, originX + 5, 29, 2, 2, palette.accent);
	}
	// Diagonal forearm bands.
	drawLine(pixels, 44, 24, 46, 28, palette.light, 2);
	drawLine(pixels, 38, 56, 36, 60, palette.light, 2);
	drawLine(pixels, 52, 25, 54, 29, palette.accent, 2);
	drawLine(pixels, 46, 57, 44, 61, palette.accent, 2);
}

function drawProviderChassis(pixels, providerKey, palette) {
	if (providerKey === 'codex') return drawCodexChassis(pixels, palette);
	if (providerKey === 'gemini') return drawGeminiChassis(pixels, palette);
	if (providerKey === 'kimi') return drawKimiChassis(pixels, palette);
	if (providerKey === 'cursor') return drawCursorChassis(pixels, palette);
	throw new Error(`No chassis renderer for provider ${providerKey}`);
}

function drawFamilyMotif(pixels, familyIndex, palette) {
	if (familyIndex === 0) {
		// Central upper-body spine with open crown.
		fillRect(pixels, 22, 36, 4, 2, palette.light);
		fillRect(pixels, 23, 38, 2, 6, palette.accent);
		fillRect(pixels, 34, 36, 4, 2, palette.light);
		fillRect(pixels, 35, 38, 2, 6, palette.accent);
		return;
	}
	if (familyIndex === 1) {
		// Wide stepped chevrons.
		fillRect(pixels, 20, 36, 2, 3, palette.light);
		fillRect(pixels, 26, 36, 2, 3, palette.light);
		fillRect(pixels, 22, 38, 2, 3, palette.accent);
		fillRect(pixels, 24, 40, 2, 3, palette.accent);
		fillRect(pixels, 32, 36, 2, 3, palette.light);
		fillRect(pixels, 38, 36, 2, 3, palette.light);
		fillRect(pixels, 34, 38, 2, 3, palette.accent);
		fillRect(pixels, 36, 40, 2, 3, palette.accent);
		return;
	}
	if (familyIndex === 2) {
		// Two broad value rails joined across the chest.
		fillRect(pixels, 20, 36, 3, 8, palette.mid);
		fillRect(pixels, 25, 36, 3, 8, palette.light);
		fillRect(pixels, 22, 39, 4, 2, palette.accent);
		fillRect(pixels, 32, 36, 3, 8, palette.light);
		fillRect(pixels, 37, 36, 3, 8, palette.mid);
		fillRect(pixels, 34, 39, 4, 2, palette.accent);
		return;
	}
	if (familyIndex === 3) {
		// Split gate around a dark center window.
		fillRect(pixels, 20, 36, 2, 9, palette.accent);
		fillRect(pixels, 26, 36, 2, 9, palette.light);
		fillRect(pixels, 22, 36, 4, 2, palette.light);
		fillRect(pixels, 22, 39, 4, 5, palette.dark);
		fillRect(pixels, 32, 36, 2, 9, palette.light);
		fillRect(pixels, 38, 36, 2, 9, palette.accent);
		fillRect(pixels, 34, 36, 4, 2, palette.light);
		fillRect(pixels, 34, 39, 4, 5, palette.dark);
		return;
	}
	throw new Error(`No family motif renderer for index ${familyIndex}`);
}

function drawIndividualSignature(pixels, variantIndex, palette) {
	if (variantIndex === 0) {
		// Left shoulder cap, single back rail, right boot cuff.
		fillRect(pixels, 36, 52, 4, 3, palette.accent);
		fillRect(pixels, 33, 27, 2, 5, palette.light);
		fillRect(pixels, 4, 28, 4, 4, palette.accent);
		return;
	}
	if (variantIndex === 1) {
		// Right shoulder cap, broad back bar, left boot cuff.
		fillRect(pixels, 44, 20, 4, 3, palette.accent);
		fillRect(pixels, 32, 28, 8, 2, palette.light);
		fillRect(pixels, 20, 60, 4, 4, palette.accent);
		return;
	}
	if (variantIndex === 2) {
		// Opposed shoulder slashes, centered back column, split greaves.
		drawLine(pixels, 44, 20, 46, 23, palette.accent, 2);
		drawLine(pixels, 38, 52, 36, 55, palette.accent, 2);
		fillRect(pixels, 35, 27, 2, 5, palette.light);
		fillRect(pixels, 4, 29, 2, 3, palette.accent);
		fillRect(pixels, 22, 61, 2, 3, palette.accent);
		return;
	}
	if (variantIndex === 3) {
		// Twin shoulder blocks, back corner pair, double boot bands.
		fillRect(pixels, 44, 20, 2, 4, palette.accent);
		fillRect(pixels, 38, 52, 2, 4, palette.accent);
		fillRect(pixels, 32, 28, 3, 4, palette.light);
		fillRect(pixels, 37, 28, 3, 4, palette.light);
		fillRect(pixels, 4, 29, 4, 2, palette.accent);
		fillRect(pixels, 20, 61, 4, 2, palette.accent);
		return;
	}
	throw new Error(`No individual signature renderer for index ${variantIndex}`);
}

function renderSkin(output) {
	const pixels = createPixels();
	const palette = PALETTES[output.providerKey];
	drawBase(pixels, palette);
	drawProviderChassis(pixels, output.providerKey, palette);
	drawFamilyMotif(pixels, output.familyIndex, palette);
	drawIndividualSignature(pixels, output.variantIndex, palette);
	return pixels;
}

function encodePng(pixels) {
	const scanlines = Buffer.alloc((WIDTH * CHANNELS + 1) * HEIGHT);
	for (let y = 0; y < HEIGHT; y += 1) {
		const rowStart = y * (WIDTH * CHANNELS + 1);
		scanlines[rowStart] = 0;
		pixels.copy(scanlines, rowStart + 1, y * WIDTH * CHANNELS, (y + 1) * WIDTH * CHANNELS);
	}
	const header = Buffer.alloc(13);
	header.writeUInt32BE(WIDTH, 0);
	header.writeUInt32BE(HEIGHT, 4);
	header[8] = 8;
	header[9] = 6;
	return Buffer.concat([
		PNG_SIGNATURE,
		chunk('IHDR', header),
		chunk('IDAT', deflateSync(scanlines, { level: 9 })),
		chunk('IEND', Buffer.alloc(0)),
	]);
}

function chunk(type, data) {
	const typeBuffer = Buffer.from(type, 'ascii');
	const length = Buffer.alloc(4);
	length.writeUInt32BE(data.length, 0);
	const checksum = Buffer.alloc(4);
	checksum.writeUInt32BE(crc32(Buffer.concat([typeBuffer, data])), 0);
	return Buffer.concat([length, typeBuffer, data, checksum]);
}

function crc32(buffer) {
	let crc = 0xffffffff;
	for (const byte of buffer) {
		crc ^= byte;
		for (let bit = 0; bit < 8; bit += 1) crc = (crc >>> 1) ^ (0xedb88320 & -(crc & 1));
	}
	return (crc ^ 0xffffffff) >>> 0;
}

function validateIhdr(buffer) {
	if (buffer.length < 33 || !buffer.subarray(0, 8).equals(PNG_SIGNATURE)) return 'not a PNG';
	if (buffer.readUInt32BE(8) !== 13 || buffer.toString('ascii', 12, 16) !== 'IHDR') return 'has no leading IHDR';
	const width = buffer.readUInt32BE(16);
	const height = buffer.readUInt32BE(20);
	const bitDepth = buffer[24];
	const colorType = buffer[25];
	const compression = buffer[26];
	const filter = buffer[27];
	const interlace = buffer[28];
	if (width !== WIDTH || height !== HEIGHT || bitDepth !== 8 || colorType !== 6
			|| compression !== 0 || filter !== 0 || interlace !== 0) {
		return `has IHDR ${width}x${height}, bitDepth=${bitDepth}, colorType=${colorType}, compression=${compression}, filter=${filter}, interlace=${interlace}`;
	}
	return null;
}

function checkOutputs(outputs) {
	const errors = [];
	const expectedNames = new Set(outputs.map((output) => output.textureName));
	const actualNames = existsSync(OUTPUT_DIRECTORY)
		? readdirSync(OUTPUT_DIRECTORY).filter((name) => name.endsWith('.png')).sort()
		: [];
	if (actualNames.length !== outputs.length) {
		errors.push(`output count drift: manifest=${outputs.length}, directory=${actualNames.length}`);
	}
	for (const actualName of actualNames) {
		if (!expectedNames.has(actualName)) errors.push(`obsolete output: ${actualName}`);
	}

	const providerBuffers = new Map();
	for (const output of outputs) {
		const outputPath = path.join(OUTPUT_DIRECTORY, output.textureName);
		if (!existsSync(outputPath)) {
			errors.push(`missing output: ${output.textureName}`);
			continue;
		}
		const actual = readFileSync(outputPath);
		const ihdrError = validateIhdr(actual);
		if (ihdrError) errors.push(`${output.textureName} ${ihdrError}`);
		const expected = encodePng(renderSkin(output));
		if (!actual.equals(expected)) errors.push(`non-deterministic or stale output: ${output.textureName}`);
		const previous = providerBuffers.get(output.providerKey) ?? [];
		for (const candidate of previous) {
			if (actual.equals(candidate.buffer)) {
				errors.push(`duplicate ${output.providerKey} texture bytes: ${candidate.name} and ${output.textureName}`);
			}
		}
		previous.push({ name: output.textureName, buffer: actual });
		providerBuffers.set(output.providerKey, previous);
	}

	if (errors.length > 0) {
		for (const error of errors) process.stderr.write(`ERROR: ${error}\n`);
		throw new Error(`Agent skin check failed with ${errors.length} error(s)`);
	}
	process.stdout.write(`Validated ${outputs.length} manifest-driven 64x64 RGBA agent textures.\n`);
}

function writeOutputs(outputs) {
	mkdirSync(OUTPUT_DIRECTORY, { recursive: true });
	const expectedNames = new Set(outputs.map((output) => output.textureName));
	for (const output of outputs) {
		writeFileSync(path.join(OUTPUT_DIRECTORY, output.textureName), encodePng(renderSkin(output)));
	}
	for (const actualName of readdirSync(OUTPUT_DIRECTORY)) {
		if (actualName.endsWith('.png') && !expectedNames.has(actualName)) {
			rmSync(path.join(OUTPUT_DIRECTORY, actualName));
		}
	}
	process.stdout.write(`Generated ${outputs.length} manifest-driven agent textures.\n`);
}

const outputs = loadOutputs();
if (process.argv.slice(2).includes('--check')) {
	checkOutputs(outputs);
} else {
	writeOutputs(outputs);
}
