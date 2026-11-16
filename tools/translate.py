#!/usr/bin/env python3
"""Translation catalogues for the Android app (app/assets/i18n/<lang>.json).

    python3 tools/translate.py                 # report what each language lacks; drop texts the code no longer uses
    python3 tools/translate.py --fill          # also translate the missing texts (needs ANTHROPIC_API_KEY)
    python3 tools/translate.py --fill --lang fr --model claude-haiku-5-5
    python3 tools/translate.py --check         # validate everything without writing (exit 1 on any problem)
    python3 tools/translate.py --missing fr    # the texts French lacks, as JSON {Portuguese: English or null}
    python3 tools/translate.py --new de        # start a catalogue for a new language (add it to Ui.LANGUAGES too)

The code is written in Brazilian Portuguese, and that text is the key: every Ui.tr("...") / Ui.trf("...", ...) /
Ui.plural("...", n) / Ui.datePattern("...") call's first argument. Each <lang>.json maps those keys to its language.
A key "context|Text" is "Text" in a given context (a word that needs another translation there), "{name}" is a
placeholder filled at runtime, "plural|one:{n} temporada|other:{n} temporadas" is a count (each language gives one form
per plural category it uses, CLDR: zero, one, two, few, many, other), and "format|HH:mm" a SimpleDateFormat pattern.
Changing a Portuguese text changes its key: the old translations go, and the new text shows up as missing everywhere.

The app cleanup's reasons (app/assets/debloat.json, "why") are data with every language inside each entry: --check
reports the ones missing a language.

--fill sends only the texts (Portuguese, with the English as a reference when there is one, and the context word; no
code, keys or settings) to the Anthropic Messages API. Every translation it gets back is kept in
tools/.i18n-cache.json (not committed) under a hash of its key, so the same text is never sent twice. Standard library
only.
"""
import argparse
import hashlib
import json
import os
import re
import sys
import urllib.error
import urllib.request

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SRC = os.path.join(ROOT, "app", "src")
I18N = os.path.join(ROOT, "app", "assets", "i18n")
DEBLOAT = os.path.join(ROOT, "app", "assets", "debloat.json")
UI = os.path.join(SRC, "io", "freemytv", "Ui.java")
CACHE = os.path.join(ROOT, "tools", ".i18n-cache.json")
DEFAULT_MODEL = "claude-haiku-5-5"
SOURCE, REFERENCE = "pt", "en"
CALL = re.compile(r"\bUi\.(trf?|plural|datePattern)\(")
PLURAL_FORM = re.compile(r"^(zero|one|two|few|many|other):")
PLACEHOLDER = re.compile(r"\{[a-zA-Z_][a-zA-Z_0-9]*\}")
LITERAL = re.compile(r'"((?:[^"\\]|\\.)*)"')


# MARK: - Extraction

def java_args(text, start):
    """Splits the arguments of the call whose '(' ends at start; returns (list of raw args, end index)."""
    args, depth, i, cur = [], 0, start, []
    while i < len(text):
        ch = text[i]
        if ch in "\"'":
            j = i + 1
            while text[j] != ch:
                j += 2 if text[j] == "\\" else 1
            cur.append(text[i:j + 1])
            i = j + 1
            continue
        if ch in "([{":
            depth += 1
        elif ch in ")]}":
            if depth == 0:
                args.append("".join(cur).strip())
                return args, i
            depth -= 1
        elif ch == "," and depth == 0:
            args.append("".join(cur).strip())
            cur = []
            i += 1
            continue
        cur.append(ch)
        i += 1
    raise ValueError("unbalanced call")


def java_string(arg):
    """The value of an argument made only of string literals joined with +, else None."""
    rest, out = arg.strip(), []
    while rest:
        m = LITERAL.match(rest)
        if not m:
            return None
        out.append(m.group(1))
        rest = rest[m.end():].strip()
        if rest.startswith("+"):
            rest = rest[1:].strip()
        elif rest:
            return None
    raw = "".join(out)
    return re.sub(r"\\(u[0-9a-fA-F]{4}|.)", lambda m: chr(int(m.group(1)[1:], 16)) if m.group(1)[0] == "u"
                  else {"n": "\n", "t": "\t"}.get(m.group(1), m.group(1)), raw)


def calls():
    """Every translatable call in the code: yields (file, line, kind, first argument's value or None)."""
    for dirpath, _, files in os.walk(SRC):
        for name in sorted(files):
            if not name.endswith(".java"):
                continue
            path = os.path.join(dirpath, name)
            text = open(path, encoding="utf-8").read()
            for m in CALL.finditer(text):
                prefix = text[text.rfind("\n", 0, m.start()) + 1:m.start()].lstrip()
                if prefix.startswith(("*", "//", "/*")):
                    continue   # an example in a comment
                args, _ = java_args(text, m.end())
                yield os.path.relpath(path, ROOT), text.count("\n", 0, m.start()) + 1, m.group(1), java_string(args[0])


def shown(key):
    """What a key shows: without its context ("plural|" and "format|" too)."""
    return key.split("|", 1)[1] if "|" in key else key


def extract(warn=True):
    """{key: Portuguese text shown} from the code."""
    keys = {}
    for path, line, kind, key in calls():
        if key is None:
            if warn:
                print(f"warning: {path}:{line}: Ui.{kind} with text that isn't a string literal (it can't be translated)",
                      file=sys.stderr)
            continue
        keys[key] = shown(key)
    return dict(sorted(keys.items()))


# MARK: - Catalogues

def load(lang):
    with open(os.path.join(I18N, lang + ".json"), encoding="utf-8") as f:
        return json.load(f)


def save(lang, data):
    with open(os.path.join(I18N, lang + ".json"), "w", encoding="utf-8", newline="\n") as f:
        json.dump(dict(sorted(data.items())), f, ensure_ascii=False, indent=1)
        f.write("\n")


def languages():
    return sorted(n[:-5] for n in os.listdir(I18N) if n.endswith(".json"))


def app_languages():
    """Ui.LANGUAGES, as the settings list them."""
    m = re.search(r"LANGUAGES = \{([^}]*)\}", open(UI, encoding="utf-8").read())
    return re.findall(r'"([^"]+)"', m.group(1)) if m else []


def digest(text):
    return hashlib.sha256(text.encode("utf-8")).hexdigest()[:16]


def placeholders(text):
    return sorted(set(PLACEHOLDER.findall(text)))


def valid_format(text):
    """SimpleDateFormat letters outside quotes; doubled quotes are literal quotes."""
    quoted, i = False, 0
    while i < len(text):
        ch = text[i]
        if ch == "'":
            if i + 1 < len(text) and text[i + 1] == "'":
                i += 2
                continue
            quoted = not quoted
        elif not quoted and ch.isascii() and ch.isalpha():
            if ch not in "GyYMLwWDdFEuaHkKhmsSzZX":
                return False
            if ch == "X" and text[i:i + 4] == "XXXX":
                return False
        i += 1
    return not quoted


def fits(key, text):
    """The translation keeps the Portuguese placeholders; a plural is forms "one:…|other:…", each with them."""
    if not isinstance(text, str) or not text.strip():
        return False
    source = shown(key)
    if key.startswith("format|"):
        return valid_format(text)
    if not key.startswith("plural|"):
        return placeholders(text) == placeholders(source)
    want = placeholders(source)
    forms = text.split("|")
    return all(PLURAL_FORM.match(f) and placeholders(f) == want for f in forms) \
        and all("{n}" in f for f in forms) and any(f.startswith("other:") for f in forms)


def problems(keys, cat):
    missing = [k for k in keys if k not in cat]
    obsolete = [k for k in cat if k not in keys]
    broken = [k for k in keys if k in cat and not fits(k, cat[k])]
    return missing, obsolete, broken


def debloat_gaps(langs):
    """Cleanup reasons without some language: ["pkg: fr", ...]."""
    gaps = []
    for entry in json.load(open(DEBLOAT, encoding="utf-8")).get("apps", []):
        why = entry.get("why") or {}
        gaps += [f"{entry.get('pkg')}: {l}" for l in langs if not str(why.get(l, "")).strip()]
    return gaps


def check(keys):
    errors = []
    want = app_languages()
    if not want or want[0] != SOURCE:
        errors.append(f"Ui.LANGUAGES should start with {SOURCE!r}: {want}")
    for lang in want[1:]:
        if lang not in languages():
            errors.append(f"{lang}: in Ui.LANGUAGES without app/assets/i18n/{lang}.json")
    for lang in languages():
        if lang not in want:
            errors.append(f"{lang}: has a catalogue but isn't in Ui.LANGUAGES")
        try:
            cat = load(lang)
        except (ValueError, OSError) as e:
            errors.append(f"{lang}: invalid JSON: {e}")
            continue
        if not isinstance(cat, dict):
            errors.append(f"{lang}: catalogue must be a JSON object")
            continue
        missing, obsolete, broken = problems(keys, cat)
        for label, ks in (("missing", missing), ("obsolete", obsolete), ("broken (placeholders/forms)", broken)):
            errors += [f"{lang}: {label}: {k!r}" for k in ks]
    errors += [f"debloat.json reason missing: {g}" for g in debloat_gaps(want)]
    if errors:
        print("\n".join(errors))
        return 1
    print(f"OK: {len(languages())} catalogues match the code ({len(keys)} texts).")
    return 0


# MARK: - Translation (Anthropic Messages API, raw HTTP)

NAMES = {"en": "English", "es": "Spanish", "fr": "French", "it": "Italian", "de": "German", "nl": "Dutch",
         "pl": "Polish", "ru": "Russian", "ja": "Japanese", "zh-CN": "Simplified Chinese", "pt-PT": "European Portuguese"}

PROMPT = """Translate the user interface texts of a TV home screen app ("Free My TV") from Brazilian Portuguese into {name} ({lang}).
Each item has the Portuguese "text" and, when there is one, the app's English version ("en") as a reference for meaning.
Rules:
- Keep each translation short, about as long as the Portuguese: it is drawn on a TV screen in fixed places.
- Friendly, plain, everyday words, like the TV's own menus in {name}. Address the viewer informally if the language usually does on TV.
- Keep every placeholder like {{n}} or {{app}} exactly as written (they are filled in at runtime); move them where the grammar needs them.
- Keep symbols, arrows and separators (·, →, ←, ↑, ↓, …) and the remote's button names in the language's usual words (OK stays OK).
- A "context" says where the text appears. Context "format" means the text is a java.text.SimpleDateFormat pattern: give the usual pattern for {name}.
- Context "plural" means a count in plural forms, "one:…|other:…": answer with one form per Unicode CLDR plural category {name} uses for whole numbers, joined by "|", always including "other" and {{n}} in each form.
- Keep product and brand names (Free My TV, Netflix, YouTube, TMDB, HDMI, Android, Google, Nuvio) as they are.
Answer with a JSON object only, mapping each id to its translation."""


def translate(lang, items, model, key):
    """items: {id: {"text", "en"?, "context"?}} -> {id: translation}."""
    body = {
        "model": model,
        "max_tokens": 16000,
        "system": PROMPT.format(name=NAMES.get(lang, lang), lang=lang),
        "messages": [{"role": "user", "content": json.dumps(items, ensure_ascii=False, indent=1)}],
    }
    req = urllib.request.Request("https://api.anthropic.com/v1/messages", data=json.dumps(body).encode("utf-8"),
                                 headers={"x-api-key": key, "anthropic-version": "2023-06-01",
                                          "content-type": "application/json"})
    try:
        with urllib.request.urlopen(req, timeout=300) as r:
            reply = json.load(r)
    except urllib.error.HTTPError as e:
        raise SystemExit(f"Anthropic API: HTTP {e.code}: {e.read().decode('utf-8', 'replace')[:300]}")
    if reply.get("stop_reason") not in ("end_turn", "stop_sequence"):
        raise SystemExit(f"Anthropic API: stopped with {reply.get('stop_reason')}")
    text = "".join(b.get("text", "") for b in reply.get("content", []) if b.get("type") == "text").strip()
    text = re.sub(r"^```(?:json)?\s*|\s*```$", "", text)
    return json.loads(text)


def item(k, english):
    it = {"text": shown(k)}
    if english.get(k):
        it["en"] = english[k]
    if "|" in k:
        it["context"] = k.split("|", 1)[0]
    return it


def fill(lang, keys, cat, english, cache, model, api_key):
    todo = [k for k in keys if k not in cat or not fits(k, cat[k])]
    known = cache.setdefault(lang, {})
    for k in todo:   # translated before (same text, same context): no need to ask again
        if digest(k) in known and fits(k, known[digest(k)]):
            cat[k] = known[digest(k)]
    ask = [k for k in todo if k not in cat or not fits(k, cat[k])]
    for start in range(0, len(ask), 80):
        chunk = ask[start:start + 80]
        reply = translate(lang, {str(i): item(k, english) for i, k in enumerate(chunk)}, model, api_key)
        for i, text in reply.items():
            k = chunk[int(i)] if i.isdigit() and int(i) < len(chunk) else None
            if k and fits(k, text):
                cat[k] = text
                known[digest(k)] = text
    print(f"{lang}: {len(todo) - len(ask)} from the cache, {len(ask)} sent to {model}")


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--check", action="store_true", help="validate everything without writing anything")
    ap.add_argument("--fill", action="store_true", help="translate the missing texts (needs ANTHROPIC_API_KEY)")
    ap.add_argument("--lang", action="append", help="only these languages (repeatable)")
    ap.add_argument("--missing", metavar="LANG", help="print what LANG lacks as JSON {Portuguese key: English or null}")
    ap.add_argument("--new", metavar="LANG", help="create an empty catalogue for LANG (e.g. de, pt-PT)")
    ap.add_argument("--model", default=os.environ.get("TRANSLATE_MODEL", DEFAULT_MODEL))
    a = ap.parse_args()

    keys = extract(warn=not a.missing)
    if a.check:
        return check(keys)
    english = load(REFERENCE) if os.path.exists(os.path.join(I18N, REFERENCE + ".json")) else {}
    if a.missing:
        cat = load(a.missing)
        lacking = [k for k in keys if k not in cat or not fits(k, cat[k])]
        print(json.dumps({k: english.get(k) for k in lacking}, ensure_ascii=False, indent=1))
        return 0
    if a.new and not os.path.exists(os.path.join(I18N, a.new + ".json")):
        save(a.new, {})
        print(f"{a.new}.json created: add \"{a.new}\" to Ui.LANGUAGES, then --fill --lang {a.new}")
    cache = json.load(open(CACHE, encoding="utf-8")) if os.path.exists(CACHE) else {}
    api_key = os.environ.get("ANTHROPIC_API_KEY", "").strip()
    if a.fill and not api_key:
        print("ANTHROPIC_API_KEY isn't set: reporting only.")
    print(f"code: {len(keys)} texts in Portuguese")
    # English first: it's the other languages' reference when they're translated.
    order = sorted(a.lang or languages(), key=lambda l: (l != REFERENCE, l))
    incomplete = 0
    for lang in order:
        cat = load(lang)
        if a.fill and api_key:
            fill(lang, keys, cat, english if lang != REFERENCE else {}, cache, a.model, api_key)
        missing, obsolete, broken = problems(keys, cat)
        for k in obsolete:   # its Portuguese changed or went away
            cat.pop(k)
        save(lang, cat)
        if lang == REFERENCE:
            english = cat
        status = "complete" if not (missing or broken) else "INCOMPLETE"
        incomplete += status != "complete"
        print(f"{lang:6} {len(cat):4}/{len(keys)}  {status}"
              + (f"  missing {len(missing)}" if missing else "")
              + (f"  broken {len(broken)}" if broken else "")
              + (f"  removed {len(obsolete)} unused" if obsolete else ""))
        for k in (missing + broken)[:8]:
            print(f"         {k!r}")
    gaps = debloat_gaps(app_languages())
    if gaps:
        print(f"debloat.json: {len(gaps)} reasons missing a language (--check lists them)")
    if cache:
        with open(CACHE, "w", encoding="utf-8") as f:
            json.dump(cache, f, ensure_ascii=False, indent=1, sort_keys=True)
    return 1 if incomplete or gaps else 0


if __name__ == "__main__":
    sys.exit(main())
