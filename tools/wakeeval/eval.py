"""Runs the Vosk "Hey Muse" rule over a WAV and scores it against labelled attempt times.

The grammar and thresholds come from the same file the Kotlin matcher reads, so the two cannot drift.

  python tools/wakeeval/eval.py --model path/to/vosk-model-en-us-0.22-lgraph
  python tools/wakeeval/eval.py --model ... --wav x.wav --labels x.labels --dump results.jsonl

After changing the rule, refresh the fixture the Kotlin tests compare against with
--dump android/wake/src/test/resources/vosk/wake1.results.jsonl.
"""

import argparse
import json
import os
import sys
import wave

HERE = os.path.dirname(os.path.abspath(__file__))
RULE = os.path.join(HERE, "..", "..", "android", "wake", "src", "main", "resources", "vosk", "hey_muse.json")
CHUNK_SAMPLES = 1600


def decide(rule, result):
    """Mirrors VoskWake.decide in :wake. Returns (kind, reason)."""
    words = result.get("result", [])
    heard = [w["word"] for w in words]
    phrase = rule["phrase"]
    if heard == [p["word"] for p in phrase]:
        weak = ["%s %.2f < %.2f" % (p["word"], w.get("conf", 0.0), p["minConf"])
                for w, p in zip(words, phrase) if w.get("conf", 0.0) < p["minConf"]]
        return ("near_miss", "unsure: " + ", ".join(weak)) if weak else ("wake", "")
    if phrase[-1]["word"] in heard:
        return "near_miss", "not alone"
    return "ignore", ""


def recognize(model_dir, wav_path, grammar):
    from vosk import KaldiRecognizer, Model, SetLogLevel

    SetLogLevel(-1)
    w = wave.open(wav_path)
    if (w.getframerate(), w.getnchannels(), w.getsampwidth()) != (16000, 1, 2):
        sys.exit("%s: want 16 kHz mono 16-bit" % wav_path)
    rec = KaldiRecognizer(Model(model_dir), 16000, json.dumps(grammar))
    rec.SetWords(True)
    while True:
        data = w.readframes(CHUNK_SAMPLES)
        if not data:
            break
        if rec.AcceptWaveform(data):
            yield json.loads(rec.Result())
    yield json.loads(rec.FinalResult())


def read_labels(path):
    with open(path) as f:
        return [float(line) for line in (l.split("#")[0].strip() for l in f) if line]


def main():
    p = argparse.ArgumentParser()
    p.add_argument("--model", default=os.environ.get("VOSK_MODEL"), help="unpacked Vosk model dir (or $VOSK_MODEL)")
    p.add_argument("--wav", default=os.path.join(HERE, "wake1.wav"))
    p.add_argument("--labels", help="attempt times in seconds, one per line (default: the WAV's .labels)")
    p.add_argument("--tolerance", type=float, default=1.0, help="seconds between an attempt and the first word")
    p.add_argument("--rule", default=RULE)
    p.add_argument("--dump", help="write each final result and its decision as JSON lines")
    a = p.parse_args()
    if not a.model:
        sys.exit("--model or $VOSK_MODEL is required")
    labels = read_labels(a.labels or os.path.splitext(a.wav)[0] + ".labels")
    with open(a.rule) as f:
        rule = json.load(f)

    dump = open(a.dump, "w", newline="\n") if a.dump else None
    woken = set()
    false_alarms = near_misses = 0
    for result in recognize(a.model, a.wav, rule["grammar"]):
        words = result.get("result", [])
        if not words:
            continue
        kind, reason = decide(rule, result)
        if dump:
            dump.write(json.dumps({"result": result, "decision": kind}, sort_keys=True) + "\n")
        start = words[0]["start"]
        attempt = next((t for t in labels if abs(t - start) <= a.tolerance), None)
        if kind == "wake" and attempt is not None:
            label = "HIT"
            woken.add(attempt)
        elif kind == "wake":
            label, false_alarms = "FALSE ALARM", false_alarms + 1
        elif kind == "near_miss":
            label, near_misses = "near miss", near_misses + 1
        else:
            label = ""
        heard = " ".join("%s(%.2f)" % (w["word"], w.get("conf", 0.0)) for w in words)
        where = "attempt %gs" % attempt if attempt is not None else "no attempt"
        print("%7.2fs  %-12s %-12s %s %s" % (start, label, where, heard, reason))
    if dump:
        dump.close()

    missed = [t for t in labels if t not in woken]
    print()
    print("hits %d/%d, false alarms %d, near misses %d" % (len(woken), len(labels), false_alarms, near_misses))
    print("missed attempts: %s" % (", ".join("%gs" % t for t in missed) or "none"))


if __name__ == "__main__":
    main()
