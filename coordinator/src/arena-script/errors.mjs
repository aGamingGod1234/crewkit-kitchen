export class ArenaScriptError extends Error {
	constructor(code, message, location = null, options = undefined) {
		super(message, options);
		this.name = 'ArenaScriptError';
		this.code = code;
		this.location = location;
	}
}
