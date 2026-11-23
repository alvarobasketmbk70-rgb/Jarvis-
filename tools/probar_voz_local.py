"""Comprueba la voz incluida (sherpa-onnx + Piper davefx) y genera una muestra con el efecto de JANUS."""
import math, sys, wave
import numpy as np
import sherpa_onnx

d = "app/src/main/assets/tts-es"
import os
onnx = [f for f in os.listdir(d) if f.endswith(".onnx")][0]
cfg = sherpa_onnx.OfflineTtsConfig(
    model=sherpa_onnx.OfflineTtsModelConfig(
        vits=sherpa_onnx.OfflineTtsVitsModelConfig(model=f"{d}/{onnx}", tokens=f"{d}/tokens.txt", data_dir=f"{d}/espeak-ng-data"),
        num_threads=2, provider="cpu"))
tts = sherpa_onnx.OfflineTts(cfg)
text = "Buenas noches, señor. Soy Yanus. Todos los sistemas están operativos. ¿En qué puedo ayudarle?"
a = tts.generate(text, sid=0, speed=1.0)
x = np.array(a.samples, dtype=np.float32); sr = a.sample_rate
print("muestras", len(x), "frecuencia", sr)
if len(x) == 0:
    sys.exit("EL MODELO NO GENERA AUDIO")

def save(name, y, rate):
    y = np.clip(y, -1, 1)
    with wave.open(name, "wb") as w:
        w.setnchannels(1); w.setsampwidth(2); w.setframerate(rate)
        w.writeframes((y * 32767).astype("<i2").tobytes())

save("muestras/JANUS-voz-incluida-sin-efecto.wav", x, sr)
# Mismo efecto que la app: coro corto + eco metálico suave, y tono 0,92 (aprox. reproduciendo más lento)
line = np.zeros(int(sr * 0.05) + 4); comb = np.zeros(int(sr * 0.027)); w = 0; ci = 0; lp = 0.0
y = np.zeros_like(x)
for n, s in enumerate(x):
    line[w] = s
    delay = sr * (0.012 + 0.0025 * math.sin(2 * math.pi * 0.35 * n / sr))
    rp = w - delay
    while rp < 0: rp += len(line)
    i0 = int(rp) % len(line); i1 = (i0 + 1) % len(line); fr = rp - int(rp)
    ch = line[i0] * (1 - fr) + line[i1] * fr
    c = comb[ci]; comb[ci] = s + c * 0.28; ci = (ci + 1) % len(comb); w = (w + 1) % len(line)
    lp += (c - lp) * 0.35
    y[n] = s * 0.82 + ch * 0.30 + lp * 0.16
save("muestras/JANUS-voz-incluida.wav", y, int(sr * 0.92))
print("OK")
