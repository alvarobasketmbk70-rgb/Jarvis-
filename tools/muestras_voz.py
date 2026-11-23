"""Diagnóstico con la clave real: qué modelos de voz y de IA funcionan, y muestras de la voz de JANUS."""
import base64, json, os, sys, time, urllib.request, urllib.error, wave

KEY = os.environ.get("GEMINI_API_KEY", "").strip()
if not KEY:
    sys.exit("Falta el secreto GEMINI_API_KEY")

STYLE = ("Eres JANUS, una inteligencia artificial con aire de mayordomo digital de película. Voz masculina de tono medio, "
         "nítida, refinada y extremadamente serena, con aire de mayordomo inglés culto. Habla con precisión casi digital, "
         "con fluidez y buen ritmo, cortés y con un humor seco muy sutil. Nunca exagera las emociones. Español de España.")
TEXT = "Buenas tardes, señor. Soy Yanus. Todos los sistemas están operativos. ¿En qué puedo ayudarle?"
TTS_MODELS = ["gemini-3.8-flash-tts", "gemini-3.8-flash-lite-tts", "gemini-3.1-flash-tts-preview",
              "gemini-2.5-flash-preview-tts", "gemini-2.5-flash-tts"]
LLM_MODELS = ["gemini-3.5-flash-lite", "gemini-flash-lite-latest", "gemini-flash-latest"]
BASE = "https://generativelanguage.googleapis.com/v1beta/models/"


def post(url, body):
    req = urllib.request.Request(url, data=json.dumps(body).encode(),
                                 headers={"Content-Type": "application/json", "x-goog-api-key": KEY})
    with urllib.request.urlopen(req, timeout=90) as r:
        return r.read().decode()


def tts_body(structured):
    part = {"text": TEXT, "speech_metadata": {"style": STYLE}} if structured else {
        "text": f"{STYLE}\nLee exactamente este texto: {TEXT}"}
    return {"contents": [{"role": "user", "parts": [part]}],
            "generationConfig": {"responseModalities": ["AUDIO"],
                                 "speechConfig": {"languageCode": "es-ES",
                                                  "voiceConfig": {"prebuiltVoiceConfig": {"voiceName": "Iapetus"}}}}}


def audio_from(obj):
    out = b""
    for c in obj.get("candidates", []):
        for p in c.get("content", {}).get("parts", []):
            d = p.get("inlineData") or p.get("inline_data")
            if d and d.get("data"):
                out += base64.b64decode(d["data"])
    return out


def try_tts(model, streaming, structured):
    if streaming:
        raw = post(BASE + model + ":streamGenerateContent?alt=sse", tts_body(structured))
        pcm = b""
        for line in raw.splitlines():
            if line.startswith("data:"):
                pcm += audio_from(json.loads(line[5:].strip()))
        return pcm
    return audio_from(json.loads(post(BASE + model + ":generateContent", tts_body(structured))))


os.makedirs("muestras", exist_ok=True)
print("=== VOZ ===")
found = None
for m in TTS_MODELS:
    for streaming in (True, False):
        for structured in (True, False):
            tag = f"{m} streaming={streaming} estructurado={structured}"
            try:
                pcm = try_tts(m, streaming, structured)
                print(("OK   " if pcm else "VACÍO ") + tag, len(pcm), "bytes")
                if pcm and not found:
                    found = tag
                    with wave.open("muestras/JANUS-voz.wav", "wb") as w:
                        w.setnchannels(1); w.setsampwidth(2); w.setframerate(24000); w.writeframes(pcm)
            except urllib.error.HTTPError as e:
                print(f"ERROR {e.code} {tag}: {e.read()[:200]!r}")
            except Exception as e:
                print(f"FALLO {tag}: {e}")
            time.sleep(1)
print("PRIMERA QUE FUNCIONA:", found)

print("=== IA ===")
for m in LLM_MODELS:
    for thinking in (True, False):
        gen = {"responseMimeType": "application/json", "maxOutputTokens": 400}
        if thinking:
            gen["thinkingConfig"] = {"thinkingLevel": "minimal"}
        body = {"contents": [{"role": "user", "parts": [{"text": "Di hola en JSON {\"say\": \"...\"}"}]}],
                "generationConfig": gen}
        t = time.time()
        try:
            r = json.loads(post(BASE + m + ":generateContent", body))
            print(f"OK   {m} thinking_minimal={thinking} {time.time()-t:.2f}s",
                  r["candidates"][0]["content"]["parts"][0].get("text", "")[:80])
        except urllib.error.HTTPError as e:
            print(f"ERROR {e.code} {m} thinking_minimal={thinking}: {e.read()[:200]!r}")
        except Exception as e:
            print(f"FALLO {m}: {e}")
if not found:
    sys.exit("Ninguna combinación de voz funcionó")
