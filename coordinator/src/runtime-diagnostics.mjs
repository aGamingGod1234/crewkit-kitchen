/** Attach bounded runtime diagnostics to a coordinator lifecycle. */
export function wireRuntimeDiagnostics(coordinator, reporter) {
	if (coordinator === null || typeof coordinator !== 'object'
		|| typeof coordinator.on !== 'function' || typeof coordinator.off !== 'function') {
		throw new TypeError('runtime diagnostics coordinator must support on and off');
	}
	if (reporter === null || typeof reporter !== 'object'
		|| typeof reporter.report !== 'function' || typeof reporter.recovered !== 'function') {
		throw new TypeError('runtime diagnostics reporter must support report and recovered');
	}

	const onRuntimeError = (error) => reporter.report(error);
	const onReconciled = () => reporter.recovered();
	coordinator.on('runtimeError', onRuntimeError);
	coordinator.on('reconciled', onReconciled);

	let disposed = false;
	return () => {
		if (disposed) return;
		disposed = true;
		coordinator.off('runtimeError', onRuntimeError);
		coordinator.off('reconciled', onReconciled);
	};
}
