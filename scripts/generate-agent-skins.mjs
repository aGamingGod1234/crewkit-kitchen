import { mkdirSync, writeFileSync } from 'node:fs';
import path from 'node:path';
import { deflateSync } from 'node:zlib';

const WIDTH = 64;
const HEIGHT = 64;
const CHANNELS = 4;
const OUTPUT_DIRECTORY = path.resolve(
	'src',
	'main',
	'resources',
	'assets',
	'arenaagents',
	'textures',
	'entity',
);
const ICON_PATH = path.resolve('src', 'main', 'resources', 'assets', 'arenaagents', 'icon.png');

const BASE = Object.freeze([22, 25, 30, 255]);
const BASE_LIGHT = Object.freeze([31, 36, 43, 255]);
const WHITE = Object.freeze([232, 240, 244, 255]);
const TRANSPARENT = Object.freeze([0, 0, 0, 0]);
const VARIANTS = Object.freeze([
	Object.freeze({ provider: 'codex', name: 'cyan', accent: Object.freeze([24, 213, 226, 255]), mark: 'knot' }),
	Object.freeze({ provider: 'codex', name: 'violet', accent: Object.freeze([138, 83, 239, 255]), mark: 'knot' }),
	Object.freeze({ provider: 'codex', name: 'emerald', accent: Object.freeze([46, 211, 119, 255]), mark: 'knot' }),
	Object.freeze({ provider: 'codex', name: 'amber', accent: Object.freeze([255, 170, 38, 255]), mark: 'knot' }),
	Object.freeze({ provider: 'gemini', name: 'blue', accent: Object.freeze([66, 133, 244, 255]), mark: 'spark' }),
	Object.freeze({ provider: 'gemini', name: 'red', accent: Object.freeze([234, 67, 53, 255]), mark: 'spark' }),
	Object.freeze({ provider: 'gemini', name: 'yellow', accent: Object.freeze([251, 188, 5, 255]), mark: 'spark' }),
	Object.freeze({ provider: 'gemini', name: 'green', accent: Object.freeze([52, 168, 83, 255]), mark: 'spark' }),
	Object.freeze({ provider: 'kimi', name: 'moon', accent: Object.freeze([42, 99, 255, 255]), mark: 'crescent' }),
	Object.freeze({ provider: 'kimi', name: 'ice', accent: Object.freeze([75, 210, 255, 255]), mark: 'crescent' }),
	Object.freeze({ provider: 'kimi', name: 'orchid', accent: Object.freeze([165, 92, 255, 255]), mark: 'crescent' }),
	Object.freeze({ provider: 'kimi', name: 'solar', accent: Object.freeze([255, 181, 58, 255]), mark: 'crescent' }),
]);

const CUBOID_FACES = Object.freeze([
	// Head.
	Object.freeze([8, 0, 8, 8]), Object.freeze([16, 0, 8, 8]),
	Object.freeze([0, 8, 8, 8]), Object.freeze([8, 8, 8, 8]),
	Object.freeze([16, 8, 8, 8]), Object.freeze([24, 8, 8, 8]),
	// Torso.
	Object.freeze([20, 16, 8, 4]), Object.freeze([28, 16, 8, 4]),
	Object.freeze([16, 20, 4, 12]), Object.freeze([20, 20, 8, 12]),
	Object.freeze([28, 20, 4, 12]), Object.freeze([32, 20, 8, 12]),
	// Right arm and leg.
	Object.freeze([44, 16, 4, 4]), Object.freeze([48, 16, 4, 4]),
	Object.freeze([40, 20, 4, 12]), Object.freeze([44, 20, 4, 12]),
	Object.freeze([48, 20, 4, 12]), Object.freeze([52, 20, 4, 12]),
	Object.freeze([4, 16, 4, 4]), Object.freeze([8, 16, 4, 4]),
	Object.freeze([0, 20, 4, 12]), Object.freeze([4, 20, 4, 12]),
	Object.freeze([8, 20, 4, 12]), Object.freeze([12, 20, 4, 12]),
	// Left leg and arm.
	Object.freeze([20, 48, 4, 4]), Object.freeze([24, 48, 4, 4]),
	Object.freeze([16, 52, 4, 12]), Object.freeze([20, 52, 4, 12]),
	Object.freeze([24, 52, 4, 12]), Object.freeze([28, 52, 4, 12]),
	Object.freeze([36, 48, 4, 4]), Object.freeze([40, 48, 4, 4]),
	Object.freeze([32, 52, 4, 12]), Object.freeze([36, 52, 4, 12]),
	Object.freeze([40, 52, 4, 12]), Object.freeze([44, 52, 4, 12]),
]);

function createPixels() {
	const pixels = Buffer.alloc(WIDTH * HEIGHT * CHANNELS);
	for (let y = 0; y < HEIGHT; y += 1) {
		for (let x = 0; x < WIDTH; x += 1) setPixel(pixels, x, y, TRANSPARENT);
	}
	return pixels;
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

function drawBase(pixels) {
	for (const [x, y, width, height] of CUBOID_FACES) fillRect(pixels, x, y, width, height, BASE);
	fillRect(pixels, 8, 8, 8, 2, BASE_LIGHT);
	fillRect(pixels, 20, 20, 8, 2, BASE_LIGHT);
	fillRect(pixels, 32, 20, 8, 2, BASE_LIGHT);
}

function drawHead(pixels, accent) {
	// Symmetric visor and small reasoning node on the front face.
	fillRect(pixels, 9, 11, 2, 2, accent);
	fillRect(pixels, 13, 11, 2, 2, accent);
	setPixel(pixels, 11, 13, WHITE);
	setPixel(pixels, 12, 13, WHITE);
	// Back-of-head circuit spine.
	fillRect(pixels, 27, 10, 2, 4, accent);
	setPixel(pixels, 26, 13, WHITE);
	setPixel(pixels, 29, 13, WHITE);
}

function drawKnot(pixels, originX, originY, accent) {
	const whitePoints = [
		[1, 1], [2, 1], [0, 2], [3, 2], [0, 3], [2, 3],
		[1, 4], [2, 4], [3, 5], [4, 5], [4, 6], [5, 6],
	];
	const accentPoints = [
		[4, 1], [5, 1], [5, 2], [4, 3], [3, 3], [3, 4],
		[0, 5], [1, 5], [1, 6], [2, 7], [3, 7], [4, 7],
	];
	for (const [x, y] of whitePoints) setPixel(pixels, originX + x, originY + y, WHITE);
	for (const [x, y] of accentPoints) setPixel(pixels, originX + x, originY + y, accent);
}

function drawBody(pixels, accent, mark) {
	drawProviderMark(pixels, 21, 22, accent, mark);
	drawProviderMark(pixels, 33, 22, accent, mark);
	// Arms and legs use mirrored circuit rails.
	for (const [x, y] of [[44, 22], [47, 22], [52, 22], [55, 22]]) {
		fillRect(pixels, x, y, 1, 8, accent);
	}
	for (const [x, y] of [[4, 22], [7, 22], [20, 54], [23, 54], [36, 54], [39, 54]]) {
		fillRect(pixels, x, y, 1, 8, accent);
	}
	fillRect(pixels, 21, 30, 6, 1, WHITE);
	fillRect(pixels, 33, 30, 6, 1, WHITE);
}

function drawProviderMark(pixels, originX, originY, accent, mark) {
	if (mark === 'knot') return drawKnot(pixels, originX, originY, accent);
	if (mark === 'spark') {
		fillRect(pixels, originX + 2, originY, 2, 8, accent);
		fillRect(pixels, originX, originY + 3, 6, 2, WHITE);
		setPixel(pixels, originX + 1, originY + 2, accent);
		setPixel(pixels, originX + 4, originY + 5, accent);
		return;
	}
	fillRect(pixels, originX + 1, originY + 1, 4, 6, accent);
	fillRect(pixels, originX + 3, originY + 1, 3, 5, BASE);
	setPixel(pixels, originX + 4, originY + 6, WHITE);
}

function drawIcon() {
	const pixels = createPixels();
	fillRect(pixels, 0, 0, WIDTH, HEIGHT, BASE);
	fillRect(pixels, 4, 4, WIDTH - 8, HEIGHT - 8, BASE_LIGHT);
	const cyan = VARIANTS[0].accent;
	const firstLoop = [[18, 14], [26, 14], [34, 22], [34, 30], [26, 38], [18, 38], [10, 30], [10, 22]];
	const secondLoop = [[30, 26], [38, 26], [46, 34], [46, 42], [38, 50], [30, 50], [22, 42], [22, 34]];
	drawLoop(pixels, firstLoop, WHITE);
	drawLoop(pixels, secondLoop, cyan);
	fillRect(pixels, 27, 27, 10, 10, BASE_LIGHT);
	fillRect(pixels, 30, 30, 4, 4, WHITE);
	return pixels;
}

function drawLoop(pixels, points, color) {
	for (let index = 0; index < points.length; index += 1) {
		const [startX, startY] = points[index];
		const [endX, endY] = points[(index + 1) % points.length];
		drawThickLine(pixels, startX, startY, endX, endY, color);
	}
}

function drawThickLine(pixels, startX, startY, endX, endY, color) {
	const steps = Math.max(Math.abs(endX - startX), Math.abs(endY - startY));
	for (let step = 0; step <= steps; step += 1) {
		const x = Math.round(startX + ((endX - startX) * step) / steps);
		const y = Math.round(startY + ((endY - startY) * step) / steps);
		fillRect(pixels, x - 2, y - 2, 5, 5, color);
	}
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
		Buffer.from([137, 80, 78, 71, 13, 10, 26, 10]),
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

mkdirSync(OUTPUT_DIRECTORY, { recursive: true });
for (const variant of VARIANTS) {
	const pixels = createPixels();
	drawBase(pixels);
	drawHead(pixels, variant.accent);
	drawBody(pixels, variant.accent, variant.mark);
	const outputPath = path.join(OUTPUT_DIRECTORY, `${variant.provider}_agent_${variant.name}.png`);
	writeFileSync(outputPath, encodePng(pixels));
	process.stdout.write(`${outputPath}\n`);
}
writeFileSync(ICON_PATH, encodePng(drawIcon()));
process.stdout.write(`${ICON_PATH}\n`);
