import { randomBytes } from 'node:crypto';
import http from 'node:http';

import { MINECRAFT_DYNAMIC_TOOLS } from './native-minecraft-tools.mjs';

const MCP_PATH = '/mcp';
const MAX_REQUEST_BYTES = 1_024 * 1_024;
const SERVER_INFO = Object.freeze({ name: 'minecraft', title: 'Arena Agents Minecraft tools', version: '1.0.0' });
const FALLBACK_PROTOCOL_VERSION = '2025-06-18';

/**
 * Serves the exact native Minecraft tool set to Claude Code over loopback MCP
 * (Streamable HTTP, JSON responses). Codex receives the same tools as app-server
 * dynamic tools; each Claude agent gets its own bearer token so a process can only
 * reach the body it was launched for.
 */
export class ClaudeToolServer {
	#routes = new Map();
	#server = null;
	#starting = null;
	#port = null;
	#tools = MINECRAFT_DYNAMIC_TOOLS.map(({ name, description, inputSchema }) => ({ name, description, inputSchema }));

	/** Registers one agent route. `callTool(name, args, meta)` returns MCP tool-call content. */
	async register({ callTool, onToolsListed = () => {} }) {
		if (typeof callTool !== 'function') throw new TypeError('callTool must be a function');
		await this.#start();
		const token = randomBytes(32).toString('hex');
		this.#routes.set(token, { callTool, onToolsListed });
		return {
			url: `http://127.0.0.1:${this.#port}${MCP_PATH}`,
			token,
			unregister: () => { this.#routes.delete(token); },
		};
	}

	async stop() {
		this.#routes.clear();
		const server = this.#server;
		this.#server = null;
		this.#starting = null;
		this.#port = null;
		if (server === null) return;
		server.closeAllConnections?.();
		await new Promise((resolve) => server.close(() => resolve()));
	}

	#start() {
		if (this.#server !== null) return Promise.resolve();
		this.#starting ??= new Promise((resolve, reject) => {
			const server = http.createServer((request, response) => { void this.#handle(request, response); });
			// Tool calls such as runProgram legitimately hold a request open for minutes.
			server.requestTimeout = 0;
			server.headersTimeout = 30_000;
			server.once('error', (error) => { this.#starting = null; reject(error); });
			server.listen(0, '127.0.0.1', () => {
				server.unref();
				this.#server = server;
				this.#port = server.address().port;
				resolve();
			});
		});
		return this.#starting;
	}

	async #handle(request, response) {
		try {
			if (new URL(request.url ?? '/', 'http://127.0.0.1').pathname !== MCP_PATH) return send(response, 404);
			const route = this.#routes.get(bearerToken(request.headers.authorization));
			if (route === undefined) return send(response, 401);
			// The JSON-response flavour of Streamable HTTP has no server-initiated stream.
			if (request.method !== 'POST') return send(response, 405, null, { allow: 'POST' });
			const message = await readJson(request);
			if (message === null || typeof message !== 'object' || Array.isArray(message)) {
				return sendJson(response, rpcError(null, -32600, 'Expected one JSON-RPC message'));
			}
			if (message.id === undefined || message.id === null) return send(response, 202);
			sendJson(response, await this.#dispatch(route, message));
		} catch (error) {
			if (!response.headersSent) sendJson(response, rpcError(null, -32700, String(error?.message ?? error).slice(0, 256)));
		}
	}

	async #dispatch(route, { id, method, params }) {
		switch (method) {
			case 'initialize':
				return rpcResult(id, {
					protocolVersion: typeof params?.protocolVersion === 'string' ? params.protocolVersion : FALLBACK_PROTOCOL_VERSION,
					capabilities: { tools: { listChanged: false } },
					serverInfo: SERVER_INFO,
				});
			case 'ping':
				return rpcResult(id, {});
			case 'tools/list':
				try { route.onToolsListed(); } catch { /* readiness reporting cannot fail discovery */ }
				return rpcResult(id, { tools: this.#tools });
			case 'tools/call': {
				if (typeof params?.name !== 'string') return rpcError(id, -32602, 'tools/call requires a tool name');
				const toolUseId = typeof params?._meta?.['claudecode/toolUseId'] === 'string' ? params._meta['claudecode/toolUseId'] : null;
				return rpcResult(id, await route.callTool(params.name, params.arguments ?? {}, { toolUseId }));
			}
			default:
				return rpcError(id, -32601, `Method not found: ${String(method).slice(0, 64)}`);
		}
	}
}

function bearerToken(header) {
	const match = /^Bearer ([0-9a-f]{64})$/.exec(typeof header === 'string' ? header : '');
	return match === null ? null : match[1];
}

async function readJson(request) {
	const chunks = [];
	let size = 0;
	for await (const chunk of request) {
		size += chunk.length;
		if (size > MAX_REQUEST_BYTES) throw new Error('MCP request exceeded the size limit');
		chunks.push(chunk);
	}
	return JSON.parse(Buffer.concat(chunks).toString('utf8'));
}

function rpcResult(id, result) { return { jsonrpc: '2.0', id, result }; }
function rpcError(id, code, message) { return { jsonrpc: '2.0', id, error: { code, message } }; }

function send(response, status, body = null, headers = {}) {
	response.writeHead(status, headers);
	response.end(body ?? undefined);
}

function sendJson(response, value) {
	send(response, 200, JSON.stringify(value), { 'content-type': 'application/json' });
}
