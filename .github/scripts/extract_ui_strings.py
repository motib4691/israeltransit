import os
import re
import hashlib

SRC_DIR = "app/src/main/java"
STRINGS_FILE = "app/src/main/res/values/strings.xml"
PACKAGE_NAME = "il.co.israeltransit.offline"

# מחפש אך ורק קריאות ישירות לרכיב Text( "..." ) או Text( text = "..." )
# כולל תמיכה במחרוזות עם \" כדי לזהות אותן ולדלג עליהן בבטחה
SAFE_TEXT_CALL_PATTERN = re.compile(
    r'(?<![A-Za-z0-9_])(Text\s*\(\s*(?:text\s*=\s*)?)"((?:\\.|[^"\\])*)"'
)

HEBREW_CHAR_PATTERN = re.compile(r'[\u0590-\u05FF]')

# מילות מפתח שאם הן מופיעות באותה שורה, לא מבצעים החלפה כדי למנוע הקשר לא-קומפוזבילי
FORBIDDEN_LINE_KEYWORDS = (
    "onClick", "LaunchedEffect", "SideEffect", "DisposableEffect",
    "Toast", "Snackbar", "Log.", "//", "/*", "*"
)

def escape_android_xml(text):
    # המרת תווים מיוחדים לפי תקן AAPT2 של אנדרואיד
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

            # מעבד רק קבצים שבהם מוגדר Compose Text
            if "@Composable" not in original_code:
                continue

            file_changed = False
            new_lines = []

            for line in original_code.splitlines():
                stripped = line.strip()

                # דילוג מוחלט על שורות מסוכנות או הערות
                if any(kw in stripped for kw in FORBIDDEN_LINE_KEYWORDS) or '"""' in line:
                    new_lines.append(line)
                    continue

                def replace_safe_text(match):
                    nonlocal file_changed
                    prefix = match.group(1)   # למשל: Text( או Text(text = 
                    heb_text = match.group(2) # התוכן שבתוך המירכאות

                    # חייב להכיל לפחות אות עברית אחת
                    if not HEBREW_CHAR_PATTERN.search(heb_text):
                        return match.group(0)

                    # דילוג על משתנים דינמיים ($), ירידות שורה (\n) או תווי בריחה מורכבים (\)
                    if "$" in heb_text or "\\" in heb_text:
                        return match.group(0)

                    xml_safe_text = escape_android_xml(heb_text)

                    if xml_safe_text in text_to_id:
                        str_id = text_to_id[xml_safe_text]
                    else:
                        short_hash = hashlib.md5(heb_text.encode("utf-8")).hexdigest()[:6]
                        str_id = f"ui_{short_hash}"
                        text_to_id[xml_safe_text] = str_id
                        new_entries.append(f'    <string name="{str_id}">{xml_safe_text}</string>')

                    file_changed = True
                    return f"{prefix}stringResource(R.string.{str_id})"

                updated_line = SAFE_TEXT_CALL_PATTERN.sub(replace_safe_text, line)
                new_lines.append(updated_line)

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

    print(f"Safe extraction complete: {len(new_entries)} strings extracted across {modified_files_count} files.")

if __name__ == "__main__":
    main()
