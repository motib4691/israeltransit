import os
import re
import hashlib

SRC_DIR = "app/src/main/java"
STRINGS_FILE = "app/src/main/res/values/strings.xml"
PACKAGE_NAME = "il.co.israeltransit.offline"

HEBREW_STRING_PATTERN = re.compile(r'"([^"\n]*[\u0590-\u05FF][^"\n]*)"')

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

            if "@Composable" not in original_code:
                continue

            file_changed = False
            pending_composable = False
            in_composable_body = False
            brace_depth = 0
            new_lines = []

            for line in original_code.splitlines():
                stripped = line.strip()

                if stripped.startswith("//") or stripped.startswith("/*") or stripped.startswith("*") or "Log." in stripped:
                    new_lines.append(line)
                    continue

                if "@Composable" in stripped:
                    pending_composable = True

                open_braces = line.count("{")
                close_braces = line.count("}")

                if pending_composable and open_braces > 0:
                    in_composable_body = True
                    pending_composable = False
                    brace_depth = open_braces - close_braces
                elif in_composable_body:
                    brace_depth += open_braces - close_braces

                def replace_match(match):
                    nonlocal file_changed
                    heb_text = match.group(1)

                    if "$" in heb_text or "\\n" in heb_text:
                        return match.group(0)

                    # מחליף אך ורק בתוך גוף של פונקציית @Composable
                    if not in_composable_body:
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

                if in_composable_body and brace_depth <= 0:
                    in_composable_body = False

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
