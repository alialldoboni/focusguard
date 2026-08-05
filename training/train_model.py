"""
Train a tiny productivity-vs-slop text classifier and export it for FocusGuard.

The model is an INTEGER-INPUT model (not NLClassifier): the Android app tokenizes
the text and feeds a fixed-length sequence of vocab IDs, so the .tflite contains
only an Embedding + pooling + Dense — pure TFLite builtins, no flex ops.

Exports two files into app/src/main/assets:
  productivity_classifier.tflite   input "input" int32 [1, SEQ_LEN]; output "scores" [1, 2] (slop, productive)
  productivity_vocab.txt           token -> line index (id), line 0 = [UNK]

Run from the repo root (CPython 3.11):
    $env:PYTHONPATH="E:\\python_libs"; python training\\train_model.py
"""

import os
import random
import re

import numpy as np
import tensorflow as tf

SEED = 42
random.seed(SEED)
np.random.seed(SEED)
tf.random.set_seed(SEED)

ASSETS_DIR = os.path.join("app", "src", "main", "assets")
MODEL_PATH = os.path.join(ASSETS_DIR, "productivity_classifier.tflite")
VOCAB_PATH = os.path.join(ASSETS_DIR, "productivity_vocab.txt")

# Hyper-parameters (tiny model)
SEQ_LEN = 24        # max tokens per title  (must match LocalTfliteProductivityClassifier)
EMBED_DIM = 32
MAX_VOCAB = 10000
EPOCHS = 30
BATCH_SIZE = 32
QUANTIZE = True

# ---------------------------------------------------------------------------
# Keyword lists (mirrors FocusGuard's OnDeviceClassifier)
# ---------------------------------------------------------------------------
USEFUL_KEYWORDS = [
    "tutorial", "lecture", "course", "lesson", "learn", "study", "education",
    "explain", "explained", "teach", "class", "university", "college", "school",
    "math", "physics", "chemistry", "biology", "history", "science",
    "programming", "code", "coding", "python", "java", "kotlin", "javascript",
    "react", "flutter", "android", "ios", "swift", "rust", "golang",
    "how to", "beginner", "advanced", "guide", "walkthrough",
    "homework", "exam", "quiz", "assignment", "research", "paper",
    "github", "stackoverflow", "documentation", "wikipedia",
    "tedx", "khan academy", "coursera", "udemy", "edx",
    "seminar", "workshop", "training", "bootcamp",
    "documentary", "case study", "analysis", "audiobook", "full course",
    "masterclass", "conference", "keynote", "engineering", "design",
    "تعليم", "شرح", "دورة", "درس", "تعلم", "محاضرة", "برمجة",
    "علوم", "رياضيات", "وثائقي", "كيفية",
]

DISTRACTING_KEYWORDS = [
    "speedrun", "speed run", "minecraft", "gaming", "gameplay", "playthrough",
    "funny", "comedy", "prank", "challenge", "try not to laugh",
    "viral", "trending", "fyp", "memes", "meme", "fail", "fails",
    "compilation", "asmr", "reaction", "reacts", "vlog", "haul",
    "gossip", "drama", "tea", "beef", "exposed", "unboxing",
    "stream", "twitch", "fortnite", "roblox", "pubg", "valorant",
    "call of duty", "among us", "fall guys", "genshin", "apex", "overwatch",
    "oddly satisfying", "satisfying", "brain rot", "doomscroll",
    "cartoon", "full episode", "episode", "movie clip", "tv clip",
    "spongebob", "nickelodeon", "anime", "music video", "trailer",
    "celebrity", "highlights", "best moments",
    "مضحك", "مقلب", "تحدي", "ميمز", "ألعاب", "العاب", "كرتون",
    "حلقة كاملة", "سبونج بوب", "دراما",
]

SHORT_FORM_INDICATORS = [
    "#shorts", "shorts player", "youtube shorts", "swipe up for next video",
    "swipe for next", "use this sound", "reels", "reel player", "tiktok",
    "شورتس", "فيديوهات قصيرة", "ريلز",
]


def _is_arabic(text: str) -> bool:
    return any("\u0600" <= c <= "\u06FF" for c in text)


def _productive_titles(kw):
    return [
        f"how to {kw}", f"{kw} tutorial", f"learn {kw} for beginners",
        f"{kw} full course", f"complete {kw} guide", f"{kw} explained step by step",
        f"{kw} lesson for students", f"best {kw} tips", f"{kw} with examples",
        f"free {kw} training",
    ]


def _slop_titles(kw):
    return [
        f"funny {kw} compilation", f"{kw} fails", f"{kw} try not to laugh",
        f"{kw} gameplay", f"{kw} reaction", f"{kw} best moments",
        f"{kw} prank gone wrong", f"{kw} #shorts", f"{kw} twitch stream highlights",
        f"watch {kw} meme",
    ]


def _arabic_productive_titles(kw):
    return [kw, f"شرح {kw}", f"{kw} فيديو تعليمي", f"درس {kw}", f"كيفية {kw}"]


def _arabic_slop_titles(kw):
    return [kw, f"{kw} مضحك", f"{kw} فيديو مسلي", f"{kw} ميمز"]


def build_dataset():
    examples = []  # (text, label)  label: 0 = slop, 1 = productive
    for kw in USEFUL_KEYWORDS:
        for title in _productive_titles(kw):
            examples.append((title, 1))
        if _is_arabic(kw):
            for title in _arabic_productive_titles(kw):
                examples.append((title, 1))
    for kw in DISTRACTING_KEYWORDS:
        for title in _slop_titles(kw):
            examples.append((title, 0))
        if _is_arabic(kw):
            for title in _arabic_slop_titles(kw):
                examples.append((title, 0))
    for ind in SHORT_FORM_INDICATORS:
        examples.append((ind, 0))
        examples.append((f"watch {ind} compilation", 0))
        examples.append((f"{ind} for you", 0))
    for _ in range(500):
        k1 = random.choice(USEFUL_KEYWORDS)
        k2 = random.choice(USEFUL_KEYWORDS)
        examples.append((f"{k1} and {k2} tutorial for beginners", 1))
    for _ in range(500):
        k1 = random.choice(DISTRACTING_KEYWORDS)
        k2 = random.choice(DISTRACTING_KEYWORDS)
        examples.append((f"funny {k1} {k2} compilation", 0))
    random.shuffle(examples)
    return examples


def tokenize(text: str):
    # Must match the Android tokenizer: lowercased runs of letters/digits/underscore.
    return re.findall(r"[\w]+", text.lower())


def main():
    print("[1/5] Generating dataset...")
    examples = build_dataset()
    texts = [t for t, _ in examples]
    labels = np.array([l for _, l in examples], dtype=np.int64)
    print(f"       {len(examples)} examples "
          f"({int((labels == 1).sum())} productive / {int((labels == 0).sum())} slop)")

    print("[2/5] Building vocab + encoding...")
    tokens = [tok for t in texts for tok in tokenize(t)]
    vocab = ["[UNK]"] + sorted(set(tokens))[: MAX_VOCAB - 1]
    token_to_id = {tok: i for i, tok in enumerate(vocab)}
    print(f"       vocab size = {len(vocab)}")

    def encode(text):
        ids = [token_to_id.get(tok, 0) for tok in tokenize(text)][:SEQ_LEN]
        return ids + [0] * (SEQ_LEN - len(ids))

    x = np.array([encode(t) for t in texts], dtype=np.int32)

    print("[3/5] Building + training model...")
    inputs = tf.keras.Input(shape=(SEQ_LEN,), dtype=tf.int32, name="input")
    e = tf.keras.layers.Embedding(len(vocab), EMBED_DIM, mask_zero=True, name="embedding")(inputs)
    p = tf.keras.layers.GlobalAveragePooling1D(name="pooling")(e)
    d = tf.keras.layers.Dropout(0.2)(p)
    outputs = tf.keras.layers.Dense(2, activation="softmax", name="scores")(d)
    model = tf.keras.Model(inputs, outputs)
    model.compile(optimizer="adam", loss="sparse_categorical_crossentropy", metrics=["accuracy"])
    model.fit(x, labels, epochs=EPOCHS, batch_size=BATCH_SIZE, validation_split=0.1, verbose=1)

    print("[4/5] Converting to TFLite...")
    tflite_model = None
    if QUANTIZE:
        try:
            q = tf.lite.TFLiteConverter.from_keras_model(model)
            q.optimizations = [tf.lite.Optimize.DEFAULT]
            q.target_spec.supported_ops = [tf.lite.OpsSet.TFLITE_BUILTINS]
            tflite_model = q.convert()
            print("       converted (dynamic-range quantized)")
        except Exception as e:  # noqa: BLE001
            print(f"       quantized conversion failed ({e}); falling back to float32")
    if tflite_model is None:
        c = tf.lite.TFLiteConverter.from_keras_model(model)
        c.target_spec.supported_ops = [tf.lite.OpsSet.TFLITE_BUILTINS]
        tflite_model = c.convert()
        print("       converted (float32)")

    print("[5/5] Saving + verifying...")
    os.makedirs(ASSETS_DIR, exist_ok=True)
    with open(MODEL_PATH, "wb") as f:
        f.write(tflite_model)
    with open(VOCAB_PATH, "w", encoding="utf-8") as f:
        f.write("\n".join(vocab))
    print(f"       saved {MODEL_PATH} ({os.path.getsize(MODEL_PATH) // 1024} KB)")
    print(f"       saved {VOCAB_PATH}")

    interp = tf.lite.Interpreter(model_content=tflite_model)
    interp.allocate_tensors()
    d_in = interp.get_input_details()[0]
    d_out = interp.get_output_details()[0]
    print(f"       input  : name={d_in['name']!r} shape={d_in['shape'].tolist()} dtype={d_in['dtype']}")
    print(f"       output : name={d_out['name']!r} shape={d_out['shape'].tolist()} dtype={d_out['dtype']}")

    for title in [
        "advanced kotlin programming tutorial for beginners",
        "funny minecraft gameplay compilation",
        "كيفية البرمجة في جافا",
        "مقلب مضحك مع الأصدقاء",
    ]:
        ids = np.array([encode(title)], dtype=np.int32)
        interp.set_tensor(d_in["index"], ids)
        interp.invoke()
        scores = interp.get_tensor(d_out["index"]).tolist()[0]
        print(f"       {title!r:52} -> slop={scores[0]:.3f} productive={scores[1]:.3f}")

    assert d_in["shape"].size == 2 and d_in["shape"][1] == SEQ_LEN, d_in["shape"]
    assert d_out["shape"].size == 2 and d_out["shape"][1] == 2, d_out["shape"]
    # Note: tensor NAMES are irrelevant — the app uses the raw Interpreter by index.
    print("       OK: tensor shapes match the Android interpreter (index-based, names ignored)")


if __name__ == "__main__":
    main()
