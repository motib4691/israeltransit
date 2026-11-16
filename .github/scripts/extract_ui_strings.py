import os
import re
import hashlib

SRC_DIR = "app/src/main/java"
STRINGS_FILE = "app/src/main/res/values/strings.xml"
PACKAGE_NAME = "il.co.israeltransit.offline"

# מחפש מחרוזות במרכאות המכילות לפחות אות אחת בעברית
HEBREW_STRING_PATTERN = re.compile(r'"([^"\n]*[\u0590-\u05FF][^"\n]*)"')

# מילות מפתח של רכיבי ממשק ב-Jetpack Compose כדי להבטיח שמחליפים רק טקסט תצוגה
UI_CONTEXT_KEYWORDS = (
    "Text(", "text =", "text=", "title =", "title=", "label =", "label=",
    "placeholder =", "placeholder=", "contentDescription =", "contentDescription=",
    "HomeMenuItemData(", "Snackbar", "Toast"
)

def escape_xml(text):
    text = text.replace("&", "&amp;")
    text = text.replace("<", "&lt;").replace(">", "&gt;")
    text = text.replace("'", "\\'").replace('"', '\\"')
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
    for i, line in enumerate(lines):
        if line.startswith("package "):
            insert_idx = i + 1
            break

    additions = []
    if import_res not in code:
        additions.append(import_res)
    if import_r not in code and f"package {PACKAGE_NAME}" not in code:
        additions.append(import_r)

    if additions:
        lines[insert_idx:insert_idx] = [""] + additions
    return "\n".join(lines)

def is_ui_file(filepath, code):
    # מעבד רק קבצי מסכים או קבצים שמכילים רכיבי Composable
    filename = os.path.basename(filepath)
    if filename == "MainActivity.kt" or filename.endswith("Screen.kt"):
        return True
    return "@Composable" in code

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

            if not is_ui_file(filepath, original_code):
                continue

            file_changed = False
            in_composable_block = False
            brace_depth = 0
            new_lines = []

            for line in original_code.splitlines():
                stripped = line.strip()

                # דילוג על הערות קוד ולוגים
                if stripped.startswith("//") or stripped.startswith("/*") or stripped.startswith("*") or "Log." in stripped:
                    new_lines.append(line)
                    continue

                if "@Composable" in stripped:
                    in_composable_block = True
                    brace_depth = 0

                if in_composable_block:
                    brace_depth += line.count("{") - line.count("}")

                def replace_match(match):
                    nonlocal file_changed
                    heb_text = match.group(1)

                    # דילוג על מחרוזות עם משתנים דינמיים ($) או ירידות שורה
                    if "$" in heb_text or "\\n" in heb_text:
                        return match.group(0)

                    # מוודא שאנחנו בתוך בלוק ממשק או שורת UI מובהקת
                    if not in_composable_block and not any(kw in line for kw in UI_CONTEXT_KEYWORDS):
                        return match.group(0)

                    xml_safe_text = escape_xml(heb_text)

                    if xml_safe_text in text_to_id:
                        str_id = text_to_id[xml_safe_text]
                    else:
                        short_hash = hashlib.md5(heb_text.encode("utf-8")).hexdigest()[:6]
                        str_id = f"ui_{short_hash}"
                        text_to_id[xml_safe_text] = str_id
                        new_entries.append(f'    <string name="{str_id}">{xml_safe_text}</string>')

                    file_changed = True
                    return f"stringResource(R.string.{str_id})"

                updated_line = HEBREW_STRING_PATTERN.sub(replace_match, line)
                new_lines.append(updated_line)

                if in_composable_block and brace_depth <= 0 and "}" in line:
                    in_composable_block = False

            if file_changed:
                updated_code = "\n".join(new_lines)
                updated_code = ensure_imports(updated_code)
                with open(filepath, "w", encoding="utf-8") as f:
                    f.write(updated_code)
                modified_files_count += 1

    if new_entries:
        insertion = "\n".join(new_entries) + "\n</resources>"
        xml_content = xml_content.replace("</resources>", insertion)
        with open(STRINGS_FILE, "w", encoding="utf-8") as f:
            f.write(xml_content)

    print(f"Extraction complete: {len(new_entries)} new strings across {modified_files_count} files.")

if __name__ == "__main__":
    main()
