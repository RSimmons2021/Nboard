#!/usr/bin/env python3
"""Builds the word -> emoji table behind emoji suggestions in the prediction strip.

Sources (Unicode License v3, https://www.unicode.org/license.txt):
- CLDR English annotations, each emoji's short name and keywords:
    curl -LO https://raw.githubusercontent.com/unicode-org/cldr/main/common/annotations/en.xml
- emoji-test.txt, the fully-qualified spelling and Unicode version of each emoji:
    curl -LO https://unicode.org/Public/emoji/latest/emoji-test.txt

    python3 build_emoji_keywords.py en.xml ../../app/src/main/java/com/nboard/ime/NboardStaticData.kt \
        ../../app/src/main/assets/dictionaries/emoji_keywords_en.tsv emoji-test.txt

Output lines are `word<TAB>emoji emoji ...`, best first. CHAT_WORDS (texting words CLDR
lacks) lead, then emoji whose short name is the word ("pizza" -> 🍕). Emoji whose name
contains the word ("rain" -> 🌧️ "cloud with rain") come next, then emoji that only list it
as a keyword (🌈); ties follow the keyboard's own emoji order (ALL_EMOJIS, popular faces
and hearts first). After an
exact match, such loose matches are kept only when popular (in ALL_EMOJIS), so "pizza"
does not offer a mushroom. Emoji newer than MAX_EMOJI_VERSION are left out, since older
phones draw them as empty boxes.
"""
import re
import sys
import xml.etree.ElementTree as ET

MAX_EMOJI_PER_WORD = 4
MAX_EMOJI_VERSION = 14.0
# A keyword shared by this many emoji ("face", "person") says nothing about which one is meant.
MAX_SHARED_KEYWORD = 25
STOP_WORDS = set("""
a an the and or but if of to in on at by for with from into onto over under up down out off
is are was were be been being am do does did have has had can could will would should may might
i me my mine we us our you your he him his she her it its they them their this that these those
what which who whom when where why how all any each some no not only just so very too also than
then there here now like get got go going make made one two three new old other more most such
""".split())

# Texting words and replies, best emoji first. These lead the CLDR matches for the word.
CHAT_WORDS = {
    "lol": "😂 🤣", "lmao": "😂 🤣", "haha": "😂 😆", "hahaha": "😂 🤣", "funny": "😂 🤣",
    "love": "❤️ 😍", "thanks": "🙏 😊", "thank": "🙏 😊", "congrats": "🎉 👏",
    "congratulations": "🎉 👏", "sorry": "😔 🙏", "sad": "😢 😭", "cry": "😭 😢",
    "happy": "😊 😁", "birthday": "🎂 🥳", "night": "🌙 😴", "goodnight": "😴 🌙",
    "morning": "☀️ 😊", "ok": "👌 👍", "okay": "👌 👍", "yes": "👍 ✅", "wow": "😮 🤩",
    "omg": "😱 😮", "party": "🥳 🎉", "hello": "👋 😊", "hi": "👋 😊", "hey": "👋 😊",
    "bye": "👋 😘", "kiss": "😘 💋", "hug": "🤗 🫂", "cool": "😎 👍", "please": "🙏 🥺",
    "beautiful": "😍 ✨", "cute": "🥰 😍", "tired": "😴 😩", "sleep": "😴 💤",
    "hungry": "😋 🍕", "yummy": "😋 🤤", "delicious": "😋 🤤", "angry": "😡 😠",
    "mad": "😡 😤", "scared": "😱 😨", "excited": "🤩 🎉", "hot": "🔥 🥵", "cold": "🥶 ❄️",
    "money": "💰 💸", "cheers": "🍻 🥂", "yay": "🎉 🥳", "ugh": "😩 🙄", "think": "🤔 💭",
    "perfect": "💯 👌", "agree": "👍 💯", "nice": "👍 😊", "great": "👍 🙌", "awesome": "🙌 😎",
    "amazing": "🤩 🙌", "crazy": "🤪 😜", "kidding": "😜 😂", "dead": "💀 😂", "rip": "💀 😢",
    "miss": "🥺 💔", "proud": "🥹 👏", "good": "👍 😊", "welcome": "😊 🤗", "sick": "🤒 🤢",
    "gym": "💪 🏋️", "strong": "💪 🔥", "win": "🏆 🎉", "fun": "🥳 😄", "smile": "😊 😁",
    # CLDR names mislead here: 🚗 is "automobile", and "musical note" does not contain "music".
    "car": "🚗 🚙", "music": "🎵 🎶", "dog": "🐶 🐕", "cat": "🐱 🐈",
}


def popularity_order(static_data_path):
    """The emoji order of ALL_EMOJIS in NboardStaticData.kt."""
    text = open(static_data_path, encoding="utf-8").read()
    block = text[text.index("val ALL_EMOJIS"):]
    block = block[:block.index(")")]
    return {emoji: index for index, emoji in enumerate(re.findall(r'"([^"]+)"', block))}


def qualified_forms(emoji_test_path):
    """Maps each emoji, with or without variation selectors, to its fully-qualified form and version."""
    forms = {}
    for line in open(emoji_test_path, encoding="utf-8"):
        match = re.match(r"^([0-9A-F ]+);\s*fully-qualified\s*#\s*\S+\s+E(\d+\.\d+)", line)
        if match:
            emoji = "".join(chr(int(code, 16)) for code in match.group(1).split())
            forms[emoji.replace("\ufe0f", "")] = (emoji, float(match.group(2)))
    return forms


def is_flag_or_keycap(emoji):
    return any(0x1F1E6 <= ord(c) <= 0x1F1FF for c in emoji) or "⃣" in emoji


def main(annotations_path, static_data_path, output_path, emoji_test_path):
    order = popularity_order(static_data_path)
    forms = qualified_forms(emoji_test_path)
    names, keywords, file_order = {}, {}, {}
    for node in ET.parse(annotations_path).getroot().iter("annotation"):
        emoji, version = forms.get(node.get("cp").replace("\ufe0f", ""), (None, 99.0))
        if emoji is None or version > MAX_EMOJI_VERSION or is_flag_or_keycap(emoji):
            continue
        file_order.setdefault(emoji, len(file_order))
        values = [v.strip().lower() for v in (node.text or "").split("|") if v.strip()]
        if node.get("type") == "tts":
            names[emoji] = values[0] if values else ""
        else:
            keywords[emoji] = values

    scores = {}
    def add(word, emoji, score):
        if not re.fullmatch(r"[a-z]+", word) or word in STOP_WORDS or len(word) < 2:
            return
        entry = scores.setdefault(word, {})
        entry[emoji] = max(entry.get(emoji, 0), score)

    for emoji, name in names.items():
        tokens = re.findall(r"[a-z]+", name)
        if len(tokens) == 1:
            add(tokens[0], emoji, 2)
        for token in tokens:
            add(token, emoji, 1.5)
    for emoji, values in keywords.items():
        for value in values:
            if " " not in value:
                add(value, emoji, 1)

    def tie_break(emoji):
        return (order.get(emoji, len(order)), file_order.get(emoji, 0))

    lines = []
    for word in sorted(set(scores) | set(CHAT_WORDS)):
        found = scores.get(word, {})
        if len(found) > MAX_SHARED_KEYWORD and word not in CHAT_WORDS:
            continue
        ranked = [forms.get(e.replace("\ufe0f", ""), (e,))[0] for e in CHAT_WORDS.get(word, "").split()]
        exact = any(score == 2 for score in found.values()) or bool(ranked)
        for emoji in sorted(found, key=lambda e: (-found[e], tie_break(e))):
            loose = found[emoji] < 2
            if emoji not in ranked and not (exact and loose and emoji not in order):
                ranked.append(emoji)
        lines.append(word + "\t" + " ".join(ranked[:MAX_EMOJI_PER_WORD]))

    with open(output_path, "w", encoding="utf-8") as out:
        out.write("# Emoji keywords derived from Unicode CLDR annotations (en). "
                  "Copyright (c) Unicode, Inc. Unicode License v3: https://www.unicode.org/license.txt\n")
        out.write("\n".join(lines) + "\n")
    print(f"{len(lines)} words -> {output_path}")


if __name__ == "__main__":
    main(*sys.argv[1:5])
