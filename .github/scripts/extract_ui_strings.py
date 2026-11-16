import os
import re
import hashlib

SRC_DIR = "app/src/main/java"
STRINGS_FILE = "app/src/main/res/values/strings.xml"
PACKAGE_NAME = "il.co.israeltransit.offline"

HEBREW_CHAR_PATTERN = re.compile(r'[\u0590-\u05FF]')

# מזהה קריאות Text(...) או contentDescription = "..." גם על פני כמה שורות
MULTI_LINE_UI_PATTERN = re.compile(
    r'((?<![A-Za-z0-9_])Text\s*\(\s*(?:text\s*=\s*)?|contentDescription\s*=\s*)"((?:\\.|[^"\\])*)"',
    re.DOTALL
)

# מזהה משתנים פשוטים ($var) או ביטויים פשוטים (${obj.prop}) ללא מירכאות פנימיות
INTERPOLATION_PATTERN = re.compile(
    r'\$(?:\{([A-Za-z0-9_.\(\)]+)\}|([A-Za-z_][A-Za-z0-9_]*))'
)

FORBIDDEN_CONTEXT_KEYWORDS = (
    "onClick", "LaunchedEffect", "SideEffect", "DisposableEffect",
    "Toast", "Snackbar", "Log."
)

def escape_android_xml(text, has_placeholders=False):
    if has_placeholders:
        # באנדרואיד, אם יש %1$s במחרוזת, כל סימן % רגיל חייב להיות מוכפל ל-%%
        text = text.replace("%", "%%")
    text = text.replace("&", "&amp;")
    text = text.replace("<", "&lt;").replace(">", "&gt;")
    text = text.replace("'", "\\'").replace('"', '\\"')
    if text.startswith("@") or text.startswith("?"):
        text = "\\" + text
    return text

def get_existing_strings():
    os.makedirs(os.path.dirname(STRINGS_FILE), exist_ok=True)
    if not os.path.exists(STRINGS_FILE):
        return {}, '<?xml version="1.0" encoding="utf-8"?>\n<resources>\n</resources>'
    with open(STRINGS_FILE, "r", encoding="utf-8") as f:
        content = f.read()
    existing = dict(re.findall(r'<string name="([^"]+)">([^<]+)</string>', content))
    return {v: k for k, v in existing.items()}, content

def ensure_imports(code):
    import_res = "import androidx.compose.ui.res.stringResource"
    import_r = f"import {PACKAGE_NAME}.R"
    
    lines = code.splitlines()
    insert_idx = 0
    is_root_package = False

    for i, line in enumerate(lines):
        stripped = line.strip()
        if stripped.startswith("package "):
            insert_idx = i + 1
            if stripped == f"package {PACKAGE_NAME}":
                is_root_package = True
            break

    additions = []
    if import_res not in code:
        additions.append(import_res)
    if not is_root_package and import_r not in code:
        additions.append(import_r)

    if additions:
        lines[insert_idx:insert_idx] = [""] + additions
    return "\n".join(lines)

def mask_comments(code):
    # מחליף הערות // ו-/* */ ברווחים באותו אורך כדי לשמור על אינדקסים מדויקים ולא לגעת בהערות
    def replacer(match):
        s = match.group(0)
        if s.startswith('"'):
            return s
        return re.sub(r'[^\n]', ' ', s)

    pattern = re.compile(r'//[^\n]*|/\*.*?\*/|"(?:\\.|[^"\\])*"', re.DOTALL)
    return pattern.sub(replacer, code)

def parse_interpolated_string(raw_text):
    # בודק אם יש תווי בריחה מורכבים (\n, \", \t) - מדלג עליהם לבטיחות
    raw_text = raw_text.replace('\\"', '"')
    if "\\" in raw_text:
        return None, None

    if "$" not in raw_text:
        return escape_android_xml(raw_text, has_placeholders=False), []

    # בודק שאין סימני $ לא תקניים (כמו מחרוזת עם $ שלא הצלחנו לפרק בבטחה)
    args = []
    pos = 0
    parts = []

    for m in INTERPOLATION_PATTERN.finditer(raw_text):
        before = raw_text[pos:m.start()]
        if "$" in before:
            return None, None
        parts.append(escape_android_xml(before, has_placeholders=True))
        expr = m.group(1) if m.group(1) is not None else m.group(2)
        args.append(expr)
        parts.append(f"%{len(args)}$s")
        pos = m.end()

    tail = raw_text[pos:]
    if "$" in tail:
        return None, None
    parts.append(escape_android_xml(tail, has_placeholders=True))

    if not args:
        return None, None

    return "".join(parts), args

def main():
    text_to_id, xml_content = get_existing_strings()
    new_entries = []
    modified_files_count = 0

    for root, _, files in os.walk(SRC_DIR):
        for file in files:
            if not file.endswith(".kt"):
                continue
            filepath = os.path.join(root, file)
            with open(filepath, "r", encoding="utf-8") as f:
                original_code = f.read()

            if "@Composable" not in original_code:
                continue

            masked_code = mask_comments(original_code)
            replacements = []

            for match in MULTI_LINE_UI_PATTERN.finditer(masked_code):
                prefix = match.group(1)
                raw_text = match.group(2)

                if not HEBREW_CHAR_PATTERN.search(raw_text):
                    continue

                # בדיקת הסביבה הקרובה (120 תווים אחורה) כדי לוודא שאנחנו לא בתוך onClick / Toast / Log
                context_before = masked_code[max(0, match.start() - 120):match.start()]
                last_line_context = context_before.splitlines()[-1] if context_before else ""
                if any(kw in last_line_context for kw in FORBIDDEN_CONTEXT_KEYWORDS):
                    continue

                # בדיקה שאין שרשור מחרוזות מיד אחרי (למשל "..." + var) שעלול לשבור טיפוסים
                after_char = masked_code[match.end():match.end() + 5].lstrip()
                if after_char.startswith("+"):
                    continue

                xml_safe_text, args = parse_interpolated_string(raw_text)
                if xml_safe_text is None:
                    continue

                if xml_safe_text in text_to_id:
                    str_id = text_to_id[xml_safe_text]
                else:
                    short_hash = hashlib.md5(xml_safe_text.encode("utf-8")).hexdigest()[:6]
                    str_id = f"ui_{short_hash}"
                    text_to_id[xml_safe_text] = str_id
                    new_entries.append(f'    <string name="{str_id}">{xml_safe_text}</string>')

                if args:
                    args_str = ", ".join(args)
                    replacement_call = f"{prefix}stringResource(R.string.{str_id}, {args_str})"
                else:
                    replacement_call = f"{prefix}stringResource(R.string.{str_id})"

                replacements.append((match.start(), match.end(), replacement_call))

            if replacements:
                # מחיל את ההחלפות מהסוף להתחלה כדי לשמור על מיקומי התווים
                updated_code = original_code
                for start, end, repl in reversed(replacements):
                    updated_code = updated_code[:start] + repl + updated_code[end:]

                updated_code = ensure_imports(updated_code)
                with open(filepath, "w", encoding="utf-8") as f:
                    f.write(updated_code)
                modified_files_count += 1

    if new_entries:
        insertion = "\n".join(new_entries) + "\n</resources>"
        xml_content = xml_content.replace("</resources>", insertion)
        with open(STRINGS_FILE, "w", encoding="utf-8") as f:
            f.write(xml_content)

    print(f"Extended extraction complete: {len(new_entries)} new strings across {modified_files_count} files.")

if __name__ == "__main__":
    main()
