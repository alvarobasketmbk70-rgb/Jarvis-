"""Muestras de voces Kokoro (más naturales) en español para elegir la voz de JANUS."""
import glob, os, sys, time, wave
import numpy as np
import sherpa_onnx

d = sys.argv[1]
print("Archivos:", sorted(os.listdir(d))[:20])
onnx = sorted(glob.glob(f"{d}/*.onnx"))[0]
lex = ",".join(sorted(glob.glob(f"{d}/lexicon*.txt")))
kw = dict(model=onnx, voices=f"{d}/voices.bin", tokens=f"{d}/tokens.txt", data_dir=f"{d}/espeak-ng-data")
if os.path.isdir(f"{d}/dict"):
    kw["dict_dir"] = f"{d}/dict"
if lex:
    kw["lexicon"] = lex
try:
    kc = sherpa_onnx.OfflineTtsKokoroModelConfig(lang="es", **kw); print("con lang=es")
except TypeError:
    kc = sherpa_onnx.OfflineTtsKokoroModelConfig(**kw); print("sin parámetro lang")
tts = sherpa_onnx.OfflineTts(sherpa_onnx.OfflineTtsConfig(
    model=sherpa_onnx.OfflineTtsModelConfig(kokoro=kc, num_threads=2, provider="cpu")))
print("voces disponibles:", tts.num_speakers)
text = "Buenas noches, señor. Soy Yanus. Todos los sistemas están operativos. ¿En qué puedo ayudarle?"
voices = {24: "bm_daniel", 25: "bm_fable", 26: "bm_george", 27: "bm_lewis", 29: "em_alex", 30: "em_santa"}
os.makedirs("muestras", exist_ok=True)
for sid, name in voices.items():
    if sid >= tts.num_speakers:
        continue
    t = time.time()
    a = tts.generate(text, sid=sid, speed=1.0)
    x = np.array(a.samples, dtype=np.float32)
    dt = time.time() - t
    if len(x) == 0:
        print("VACÍO", name); continue
    print(f"OK {name}: {len(x)/a.sample_rate:.1f}s de audio en {dt:.1f}s")
    with wave.open(f"muestras/{sid:02d}-{name}.wav", "wb") as w:
        w.setnchannels(1); w.setsampwidth(2); w.setframerate(a.sample_rate)
        w.writeframes((np.clip(x, -1, 1) * 32767).astype("<i2").tobytes())
