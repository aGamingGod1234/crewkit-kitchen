import base64
import contextlib
import hashlib
import json
import math
import os
import sys
import threading


_RPC_STDOUT = sys.stdout

MAX_TTS_CODE_POINTS = 280
MAX_TTS_PCM_BYTES = 24_000 * 2 * 20
MAX_STT_PCM_BYTES = 48_000 * 2 * 20

_tts_model = None
_stt_model = None
_tts_lock = threading.Lock()
_stt_lock = threading.Lock()
_warmup_lock = threading.Lock()
_warmup_started = False
_warmup_error = None
_response_lock = threading.Lock()

_LOCAL_VOICE_STYLES = (
    (0.48, 0.18),
    (0.56, 0.24),
    (0.64, 0.30),
    (0.72, 0.36),
    (0.80, 0.42),
    (0.88, 0.48),
    (0.96, 0.54),
    (1.04, 0.60),
)


def _load_tts():
    global _tts_model
    if _tts_model is not None:
        return _tts_model
    with _tts_lock:
        if _tts_model is not None:
            return _tts_model
        with contextlib.redirect_stdout(sys.stderr):
            import torch
            from chatterbox.tts import ChatterboxTTS

            requested_device = os.environ.get("ARENA_LOCAL_TTS_DEVICE", "").strip().lower()
            device = requested_device or ("cuda" if torch.cuda.is_available() else "cpu")
            _tts_model = ChatterboxTTS.from_pretrained(device=device)
    return _tts_model


def _load_stt():
    global _stt_model
    if _stt_model is not None:
        return _stt_model
    with _stt_lock:
        if _stt_model is not None:
            return _stt_model
        with contextlib.redirect_stdout(sys.stderr):
            import torch
            from faster_whisper import WhisperModel

            model_name = os.environ.get("ARENA_LOCAL_STT_MODEL", "small.en").strip() or "small.en"
            requested_device = os.environ.get("ARENA_LOCAL_STT_DEVICE", "auto").strip().lower() or "auto"
            if requested_device not in {"auto", "cpu", "cuda"}:
                raise ValueError("ARENA_LOCAL_STT_DEVICE must be auto, cpu, or cuda")
            devices = ["cuda", "cpu"] if requested_device == "auto" and torch.cuda.is_available() else [
                "cpu" if requested_device == "auto" else requested_device
            ]
            configured_compute = os.environ.get("ARENA_LOCAL_STT_COMPUTE_TYPE", "").strip().lower()
            last_error = None
            for device in devices:
                compute_type = configured_compute or ("float16" if device == "cuda" else "int8")
                try:
                    _stt_model = WhisperModel(model_name, device=device, compute_type=compute_type)
                    break
                except Exception as error:
                    last_error = error
                    if requested_device != "auto" or device == devices[-1]:
                        raise
            if _stt_model is None and last_error is not None:
                raise last_error
    return _stt_model


def _warmup_model(name, loader):
    try:
        loader()
        return True
    except Exception as error:
        sys.stderr.write(f"Arena local speech {name} warmup failed: {type(error).__name__}: {error}\n")
        sys.stderr.flush()
        return False


def _warmup():
    global _warmup_started, _warmup_error
    with _warmup_lock:
        if _warmup_error is not None:
            raise RuntimeError(_warmup_error)
        if _warmup_started:
            return {"sttReady": _stt_model is not None, "ttsReady": _tts_model is not None}
        _warmup_started = True
    results = {}

    def warm(name, loader):
        results[name] = _warmup_model(name, loader)

    threads = [
        threading.Thread(target=warm, args=("STT", _load_stt), daemon=True),
        threading.Thread(target=warm, args=("TTS", _load_tts), daemon=True),
    ]
    for thread in threads:
        thread.start()
    for thread in threads:
        thread.join()
    if not results.get("stt", False) or not results.get("tts", False):
        failed = ", ".join(name for name in ("STT", "TTS") if not results.get(name, False))
        _warmup_error = f"Local speech model warmup failed for: {failed}"
        raise RuntimeError(_warmup_error)
    return {"sttReady": True, "ttsReady": True}


def _tts(request):
    text = request.get("text")
    voice_id = request.get("voiceId", "local.default.v1")
    speed = request.get("speed", 1)
    if not isinstance(text, str) or not text.strip() or len(text) > MAX_TTS_CODE_POINTS:
        raise ValueError("TTS text must contain 1 to 280 code points")
    if not isinstance(voice_id, str) or not voice_id.strip():
        raise ValueError("TTS voice ID is invalid")
    if not isinstance(speed, (int, float)) or not math.isfinite(speed) or speed < 0.5 or speed > 2:
        raise ValueError("TTS speed is invalid")
    model = _load_tts()
    exaggeration, cfg_weight = _voice_style(voice_id)
    with contextlib.redirect_stdout(sys.stderr):
        import torch

        with torch.inference_mode():
            waveform = model.generate(text, exaggeration=exaggeration, cfg_weight=cfg_weight)
            waveform = _apply_speed(waveform, speed, torch)
        pcm = (waveform.detach().float().cpu().flatten().clamp(-1, 1) * 32767).to(torch.int16).numpy().tobytes()
    if not pcm or len(pcm) % 2 or len(pcm) > MAX_TTS_PCM_BYTES:
        raise RuntimeError("Generated speech exceeded the 20 second PCM limit")
    return {
        "sampleRateHz": int(model.sr),
        "pcmBase64": base64.b64encode(pcm).decode("ascii"),
        "provider": "local-chatterbox",
        "voiceId": _local_voice_id(voice_id),
    }


def _voice_style(voice_id):
    digest = hashlib.sha256(voice_id.encode("utf-8")).digest()
    exaggeration, cfg_weight = _LOCAL_VOICE_STYLES[digest[0] % len(_LOCAL_VOICE_STYLES)]
    configured_exaggeration = os.environ.get("ARENA_LOCAL_TTS_EXAGGERATION")
    configured_cfg_weight = os.environ.get("ARENA_LOCAL_TTS_CFG_WEIGHT")
    if configured_exaggeration:
        exaggeration = float(configured_exaggeration)
    if configured_cfg_weight:
        cfg_weight = float(configured_cfg_weight)
    return exaggeration, cfg_weight


def _local_voice_id(voice_id):
    digest = hashlib.sha256(voice_id.encode("utf-8")).hexdigest()[:8]
    return f"local.chatterbox.v1.{digest}"


def _apply_speed(waveform, speed, torch):
    if speed == 1:
        return waveform
    flattened = waveform.detach().float().flatten()
    target_length = max(1, round(flattened.shape[0] / speed))
    if target_length == flattened.shape[0]:
        return flattened
    import torch.nn.functional as functional
    return functional.interpolate(
        flattened.view(1, 1, -1), size=target_length, mode="linear", align_corners=False,
    ).flatten()


def _stt(request):
    encoded = request.get("pcmBase64")
    if not isinstance(encoded, str):
        raise ValueError("STT audio is missing")
    try:
        pcm = base64.b64decode(encoded, validate=True)
    except Exception as error:
        raise ValueError("STT audio is invalid") from error
    if not pcm or len(pcm) % 2 or len(pcm) > MAX_STT_PCM_BYTES:
        raise ValueError("STT audio must be at most 20 seconds of 48 kHz mono PCM")
    with contextlib.redirect_stdout(sys.stderr):
        import numpy as np
        import torch
        import torchaudio.functional as audio_functional

        waveform = torch.from_numpy(np.frombuffer(pcm, dtype="<i2").copy()).float().div_(32768.0)
        audio_16khz = audio_functional.resample(waveform, 48_000, 16_000).numpy()
        segments, _ = _load_stt().transcribe(
            audio_16khz,
            language="en",
            beam_size=1,
            vad_filter=True,
            condition_on_previous_text=False,
        )
        completed = list(segments)
    transcript = " ".join(segment.text.strip() for segment in completed if segment.text.strip()).strip()
    if not completed:
        confidence = 0.0
    else:
        weighted_log_probability = sum(segment.avg_logprob * max(1, segment.end - segment.start) for segment in completed)
        duration = sum(max(1, segment.end - segment.start) for segment in completed)
        confidence = max(0.0, min(1.0, math.exp(weighted_log_probability / duration)))
    return {"transcript": transcript[:512], "confidence": confidence}


def _error_code(operation, error):
    if isinstance(error, ValueError):
        return "TTS_INVALID_REQUEST" if operation == "tts" else "STT_MALFORMED_AUDIO"
    return "LOCAL_TTS_ERROR" if operation == "tts" else "LOCAL_STT_ERROR"


def _respond(value):
    with _response_lock:
        _RPC_STDOUT.write(json.dumps(value, separators=(",", ":")) + "\n")
        _RPC_STDOUT.flush()


def _handle_request(line):
    operation = "unknown"
    request_id = None
    try:
        request = json.loads(line)
        request_id = request.get("id")
        operation = request.get("op")
        if not isinstance(request_id, int) or request_id < 1:
            raise ValueError("Request ID is invalid")
        if operation == "tts":
            result = _tts(request)
        elif operation == "stt":
            result = _stt(request)
        elif operation == "warmup":
            result = _warmup()
        else:
            raise ValueError("Speech operation is invalid")
        _respond({"id": request_id, "ok": True, **result})
    except Exception as error:
        message = f"{type(error).__name__}: {error}"[:256]
        _respond({"id": request_id, "ok": False, "code": _error_code(operation, error), "message": message})


def main():
    for line in sys.stdin:
        threading.Thread(target=_handle_request, args=(line,), daemon=True).start()


if __name__ == "__main__":
    main()
