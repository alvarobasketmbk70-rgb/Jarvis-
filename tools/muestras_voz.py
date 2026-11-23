"""Genera muestras de las voces neuronales de Gemini con el estilo de Jarvis."""
import base64, json, os, sys, urllib.request, urllib.error, wave

KEY = os.environ.get("GEMINI_API_KEY", "").strip()
if not KEY:
    sys.exit("Falta el secreto GEMINI_API_KEY")

STYLE = ("Eres J.A.R.V.I.S., una inteligencia artificial mayordomo de película de superhéroes. Voz masculina de tono medio, nítida, refinada y extremadamente serena, con aire de mayordomo inglés culto. Habla con precisión casi digital, sin prisa pero con fluidez, cortés y con un humor seco muy sutil. Nunca exagera las emociones. Español de España.")
TEXT = ("Buenas tardes, señor. Todos los sistemas están operativos. He pausado su música y le he enviado el mensaje a "
        "su madre. ¿Desea algo más, o le dejo disfrutar de la tarde?")
VOICES = ["Iapetus", "Charon", "Orus", "Algenib", "Alnilam", "Rasalgethi", "Schedar", "Fenrir",
          "Enceladus", "Umbriel", "Sadaltager", "Puck", "Kore", "Aoede"]
MODELS = ["gemini-3.8-flash-tts", "gemini-3.1-flash-tts-preview"]


def call(model, voice, structured):
    part = {"text": TEXT, "speech_metadata": {"style": STYLE}} if structured else {
        "text": f"{STYLE}\nLee exactamente este texto: {TEXT}"}
    body = {"contents": [{"role": "user", "parts": [part]}],
            "generationConfig": {"responseModalities": ["AUDIO"],
                                 "speechConfig": {"languageCode": "es-ES",
                                                  "voiceConfig": {"prebuiltVoiceConfig": {"voiceName": voice}}}}}
    req = urllib.request.Request(
        f"https://generativelanguage.googleapis.com/v1beta/models/{model}:generateContent",
        data=json.dumps(body).encode(), headers={"Content-Type": "application/json", "x-goog-api-key": KEY})
    with urllib.request.urlopen(req, timeout=90) as r:
        res = json.load(r)
    for p in res["candidates"][0]["content"]["parts"]:
        d = p.get("inlineData") or p.get("inline_data")
        if d and d.get("data"):
            return base64.b64decode(d["data"])
    raise RuntimeError("sin audio")


def synth(model, voice):
    try:
        return call(model, voice, True)
    except urllib.error.HTTPError as e:
        if e.code != 400:
            raise
        print(f"  {voice}: 400 con speech_metadata ({e.read()[:200]!r}), reintento sin él")
        return call(model, voice, False)


os.makedirs("muestras", exist_ok=True)
model = None
for m in MODELS:
    try:
        pcm = synth(m, VOICES[0]); model = m; break
    except Exception as e:
        print(f"Modelo {m} no disponible: {e}")
if not model:
    sys.exit("Ningún modelo TTS funcionó con esta clave")
print("Modelo:", model)
for i, v in enumerate(VOICES, 1):
    try:
        if i > 1:
            pcm = synth(model, v)
        with wave.open(f"muestras/{i:02d}-{v}.wav", "wb") as w:
            w.setnchannels(1); w.setsampwidth(2); w.setframerate(24000); w.writeframes(pcm)
        print("OK", v)
    except Exception as e:
        print("FALLO", v, e)
