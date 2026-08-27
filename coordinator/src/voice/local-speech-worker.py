import base64
import contextlib
import json
import math
import os
import sys
import threading


MAX_TTS_CODE_POINTS = 280
MAX_TTS_PCM_BYTES = 24_000 * 2 * 20
MAX_STT_PCM_BYTES = 48_000 * 2 * 20

_tts_model = None
_stt_model = None
_tts_lock = threading.Lock()
_stt_lock = threading.Lock()
_warmup_lock = threading.Lock()
_warmup_started = False


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
    global _warmup_started
    with _warmup_lock:
        if _warmup_started:
            return {"sttReady": _stt_model is not None, "ttsReady": _tts_model is not None}
        _warmup_started = True
    stt_ready = _warmup_model("STT", _load_stt)
    threading.Thread(target=_warmup_model, args=("TTS", _load_tts), daemon=True).start()
    return {"sttReady": stt_ready, "ttsReady": _tts_model is not None}


def _tts(request):
    text = request.get("text")
    speed = request.get("speed", 1)
    if not isinstance(text, str) or not text.strip() or len(text) > MAX_TTS_CODE_POINTS:
        raise ValueError("TTS text must contain 1 to 280 code points")
    if not isinstance(speed, (int, float)) or not math.isfinite(speed) or speed < 0.5 or speed > 2:
        raise ValueError("TTS speed is invalid")
    model = _load_tts()
    exaggeration = float(os.environ.get("ARENA_LOCAL_TTS_EXAGGERATION", "0.7"))
    cfg_weight = float(os.environ.get("ARENA_LOCAL_TTS_CFG_WEIGHT", "0.3"))
    with contextlib.redirect_stdout(sys.stderr):
        import torch

        with torch.inference_mode():
            waveform = model.generate(text, exaggeration=exaggeration, cfg_weight=cfg_weight)
        pcm = (waveform.detach().float().cpu().flatten().clamp(-1, 1) * 32767).to(torch.int16).numpy().tobytes()
    if not pcm or len(pcm) % 2 or len(pcm) > MAX_TTS_PCM_BYTES:
        raise RuntimeError("Generated speech exceeded the 20 second PCM limit")
    return {
        "sampleRateHz": int(model.sr),
        "pcmBase64": base64.b64encode(pcm).decode("ascii"),
    }


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
    sys.stdout.write(json.dumps(value, separators=(",", ":")) + "\n")
    sys.stdout.flush()


def main():
    for line in sys.stdin:
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


if __name__ == "__main__":
    main()
