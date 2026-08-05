"""
Train a tiny productivity-vs-slop text classifier and export it as
`productivity_classifier.tflite` (NLClassifier format) for FocusGuard.

Requirements:
  - CPython 3.11 (the native extensions in E:\\python_libs are built for cp311)
  - TensorFlow available on PYTHONPATH (e.g. E:\\python_libs)

Run from the repo root (E:\\focusguard):
    $env:PYTHONPATH="E:\\python_libs"; python training\\train_model.py

Output:
    app/src/main/assets/productivity_classifier.tflite

The exported model matches what LocalTfliteProductivityClassifier expects:
  input tensor  : "input"  (STRING, shape [1])
  output tensor : "scores" (FLOAT32, shape [1, 2])  class order [slop, productive]
"""

import os
import random

import numpy as np
import tensorflow as tf

SEED = 42
random.seed(SEED)
np.random.seed(SEED)
tf.random.set_seed(SEED)

OUTPUT_PATH = os.path.join("app", "src", "main", "assets", "productivity_classifier.tflite")

# Hyper-parameters (tiny model)
VOCAB_SIZE = 8000      # max tokens in vocabulary
SEQ_LEN = 24           # max tokens per title
EMBED_DIM = 32         # embedding size
EPOCHS = 30
BATCH_SIZE = 32
QUANTIZE = True        # dynamic-range quantization (smaller); falls back to float32

# ---------------------------------------------------------------------------
# 1) Data: auto-generate labeled titles from FocusGuard's own keyword lists
# ---------------------------------------------------------------------------
USEFUL_KEYWORDS = [
    # English
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
    # Arabic
    "تعليم", "شرح", "دورة", "درس", "تعلم", "محاضرة", "برمجة",
    "علوم", "رياضيات", "وثائقي", "كيفية",
]

DISTRACTING_KEYWORDS = [
    # English
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
    # Arabic
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


def _productive_titles(kw: str):
    return [
        f"how to {kw}",
        f"{kw} tutorial",
        f"learn {kw} for beginners",
        f"{kw} full course",
        f"complete {kw} guide",
        f"{kw} explained step by step",
        f"{kw} lesson for students",
        f"best {kw} tips",
        f"{kw} with examples",
        f"free {kw} training",
    ]


def _slop_titles(kw: str):
    return [
        f"funny {kw} compilation",
        f"{kw} fails",
        f"{kw} try not to laugh",
        f"{kw} gameplay",
        f"{kw} reaction",
        f"{kw} best moments",
        f"{kw} prank gone wrong",
        f"{kw} #shorts",
        f"{kw} twitch stream highlights",
        f"watch {kw} meme",
    ]


def _arabic_productive_titles(kw: str):
    return [kw, f"شرح {kw}", f"{kw} فيديو تعليمي", f"درس {kw}", f"كيفية {kw}"]


def _arabic_slop_titles(kw: str):
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

    # Combined-keyword variety
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


def main():
    print("[1/5] Generating dataset...")
    examples = build_dataset()
    texts = [t for t, _ in examples]
    labels = np.array([l for _, l in examples], dtype=np.int64)
    n_slop = int((labels == 0).sum())
    n_prod = int((labels == 1).sum())
    print(f"       {len(examples)} examples ({n_prod} productive / {n_slop} slop)")

    print("[2/5] Building vectorizer + model...")
    vectorizer = tf.keras.layers.TextVectorization(
        max_tokens=VOCAB_SIZE,
        output_sequence_length=SEQ_LEN,
        standardize="lower_and_strip_punctuation",
        name="text_vectorizer",
    )
    vectorizer.adapt(texts)
    vocab_size = len(vectorizer.get_vocabulary())
    print(f"       vocab size = {vocab_size}")

    inputs = tf.keras.Input(shape=(), dtype=tf.string, name="input")
    x = vectorizer(inputs)
    x = tf.keras.layers.Embedding(vocab_size, EMBED_DIM, name="embedding")(x)
    x = tf.keras.layers.GlobalAveragePooling1D(name="pooling")(x)
    x = tf.keras.layers.Dropout(0.2)(x)
    outputs = tf.keras.layers.Dense(2, activation="softmax", name="scores")(x)
    model = tf.keras.Model(inputs, outputs)

    model.compile(
        optimizer="adam",
        loss="sparse_categorical_crossentropy",
        metrics=["accuracy"],
    )

    print("[3/5] Training...")
    model.fit(
        tf.constant(texts),  # tf.string tensor (np.array(str) would create a bad strNNNN dtype)
        labels,
        epochs=EPOCHS,
        batch_size=BATCH_SIZE,
        validation_split=0.1,
        verbose=1,
    )

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

    print("[5/5] Verifying + saving...")
    os.makedirs(os.path.dirname(OUTPUT_PATH), exist_ok=True)
    with open(OUTPUT_PATH, "wb") as f:
        f.write(tflite_model)
    size_kb = os.path.getsize(OUTPUT_PATH) // 1024
    print(f"       saved {OUTPUT_PATH} ({size_kb} KB)")

    interp = tf.lite.Interpreter(model_content=tflite_model)
    interp.allocate_tensors()
    in_det = interp.get_input_details()[0]
    out_det = interp.get_output_details()[0]
    print(f"       input  : name={in_det['name']!r} shape={in_det['shape'].tolist()} dtype={in_det['dtype']}")
    print(f"       output : name={out_det['name']!r} shape={out_det['shape'].tolist()} dtype={out_det['dtype']}")

    # Best-effort sanity predictions through the TFLite interpreter.
    try:
        for title in [
            "advanced kotlin programming tutorial for beginners",
            "funny minecraft gameplay compilation",
            "كيفية البرمجة في جافا",
            "مقلب مضحك مع الأصدقاء",
        ]:
            interp.set_tensor(in_det["index"], np.array([title], dtype=np.bytes_))
            interp.invoke()
            scores = interp.get_tensor(out_det["index"]).tolist()[0]
            print(f"       {title!r:60} -> slop={scores[0]:.3f} productive={scores[1]:.3f}")
    except Exception as e:  # noqa: BLE001
        print(f"       (interpreter sanity check skipped: {e})")

    assert in_det["name"] == "input", f"unexpected input name {in_det['name']!r}"
    assert out_det["name"] == "scores", f"unexpected output name {out_det['name']!r}"
    print("       OK: tensor names match LocalTfliteProductivityClassifier expectations")


if __name__ == "__main__":
    main()
