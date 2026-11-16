import os
import re
import hashlib

SRC_DIR = "app/src/main/java"
STRINGS_FILE = "app/src/main/res/values/strings.xml"
PACKAGE_NAME = "il.co.israeltransit.offline"

HEBREW_CHAR_PATTERN = re.compile(r'[\u0590-\u05FF]')
STRING_LITERAL_PATTERN = re.compile(r'"((?:\\.|[^"\\])*)"')
INTERPOLATION_PATTERN = re.compile(
    r'\$(?:\{([A-Za-z0-9_.\(\)]+)\}|([A-Za-z_][A-Za-z0-9_]*))'
)

# כל בלוק פנימי או קריאה שאינם ציור ממשק UI ישיר
NON_COMPOSABLE_CONSTRUCTS = (
    "onClick", "onValueChange", "onCheckedChange", "onDismissRequest",
    "onLongClick", "onDoubleClick", "onClickLabel", "onAction",
    "LaunchedEffect", "SideEffect", "DisposableEffect",
    "remember", "rememberSaveable", "derivedStateOf", "rememberLauncherForActivityResult",
    "pointerInput", "clickable", "combinedClickable",
    "AndroidView", "Canvas", "drawScope", "launch", "withContext", "async",
    "Comparator", "sortedBy", "sortedWith", "filter", "map", "mapNotNull",
    "forEach", "forEachIndexed", "firstOrNull", "find", "any", "all", "none",
    "buildString", "apply", "also", "let", "run", "with"
)

FORBIDDEN_LINE_KEYWORDS = ("Toast", "Snackbar", "Log.", "const val", "throw ", "Exception(")

def escape_android_xml(text, has_placeholders=False):
    if has_placeholders:
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

def strip_comments_and_strings_for_braces(line):
    line = re.sub(r'//.*$', '', line)
    line = re.sub(r'"(?:\\.|[^"\\])*"', '""', line)
    return line

def parse_interpolated_string(raw_text):
    unescaped = raw_text.replace('\\"', '"')
    if "\\" in unescaped:
        return None, None

    if "$" not in unescaped:
        return escape_android_xml(unescaped, has_placeholders=False), []

    args = []
    pos = 0
    parts = []

    for m in INTERPOLATION_PATTERN.finditer(unescaped):
        before = unescaped[pos:m.start()]
        if "$" in before:
            return None, None
        parts.append(escape_android_xml(before, has_placeholders=True))
        expr = m.group(1) if m.group(1) is not None else m.group(2)
        args.append(expr)
        parts.append(f"%{len(args)}$s")
        pos = m.end()

    tail = unescaped[pos:]
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

            file_changed = False
            new_lines = []

            in_block_comment = False
            saw_composable_annotation = False
            pending_composable = False
            composable_depth = 0
            in_composable = False

            pending_non_composable = False
            non_composable_depth = 0
            in_non_composable = False

            for line in original_code.splitlines():
                stripped = line.strip()

                if "/*" in stripped and "*/" not in stripped:
                    in_block_comment = True
                    new_lines.append(line)
                    continue
                if in_block_comment:
                    if "*/" in stripped:
                        in_block_comment = False
                    new_lines.append(line)
                    continue

                if stripped.startswith("//") or '"""' in line:
                    new_lines.append(line)
                    continue

                clean_for_braces = strip_comments_and_strings_for_braces(line)
                opens = clean_for_braces.count("{")
                closes = clean_for_braces.count("}")

                # בדיקה אם השורה מתחילה פונקציה רגילה (לא @Composable) בתוך פונקציית @Composable
                is_local_non_composable_fun = (
                    in_composable
                    and not saw_composable_annotation
                    and re.search(r'\bfun\s+[A-Za-z0-9_]+', clean_for_braces) is not None
                )

                if "@Composable" in clean_for_braces:
                    saw_composable_annotation = True
                    if not in_composable:
                        pending_composable = True

                # זיהוי כניסה לבלוק לא-קומפוזבילי בתוך פונקציית מסך
                if in_composable and not in_non_composable:
                    has_blocked_construct = (
                        is_local_non_composable_fun
                        or any(re.search(rf'\b{kw}\b', clean_for_braces) for kw in NON_COMPOSABLE_CONSTRUCTS)
                        or ("->" in clean_for_braces and not saw_composable_annotation)
                    )
                    if has_blocked_construct:
                        if opens > closes:
                            in_non_composable = True
                            non_composable_depth = opens - closes
                        elif opens == 0:
                            pending_non_composable = True

                elif pending_non_composable and opens > 0:
                    in_non_composable = True
                    pending_non_composable = False
                    non_composable_depth = opens - closes

                elif in_non_composable:
                    non_composable_depth += opens - closes
                    if non_composable_depth <= 0:
                        in_non_composable = False
                        non_composable_depth = 0

                # ניהול עומק פונקציית ה-@Composable הראשית
                if pending_composable and opens > 0:
                    in_composable = True
                    pending_composable = False
                    saw_composable_annotation = False
                    composable_depth = opens - closes
                elif in_composable:
                    composable_depth += opens - closes
                    if opens > 0:
                        saw_composable_annotation = False

                can_replace = (
                    in_composable
                    and not in_non_composable
                    and not pending_non_composable
                    and not any(kw in clean_for_braces for kw in FORBIDDEN_LINE_KEYWORDS)
                )

                if can_replace:
                    def replace_literal(match):
                        nonlocal file_changed
                        raw_text = match.group(1)

                        if not HEBREW_CHAR_PATTERN.search(raw_text):
                            return match.group(0)

                        after = line[match.end():match.end() + 5].lstrip()
                        before = line[:match.start()].rstrip()
                        if after.startswith("+") or before.endswith("+"):
                            return match.group(0)

                        xml_safe_text, args = parse_interpolated_string(raw_text)
                        if xml_safe_text is None:
                            return match.group(0)

                        if xml_safe_text in text_to_id:
                            str_id = text_to_id[xml_safe_text]
                        else:
                            short_hash = hashlib.md5(xml_safe_text.encode("utf-8")).hexdigest()[:6]
                            str_id = f"ui_{short_hash}"
                            text_to_id[xml_safe_text] = str_id
                            new_entries.append(f'    <string name="{str_id}">{xml_safe_text}</string>')

                        file_changed = True
                        if args:
                            return f"stringResource(R.string.{str_id}, {', '.join(args)})"
                        return f"stringResource(R.string.{str_id})"

                    updated_line = STRING_LITERAL_PATTERN.sub(replace_literal, line)
                    new_lines.append(updated_line)
                else:
                    new_lines.append(line)

                if in_composable and composable_depth <= 0:
                    in_composable = False
                    composable_depth = 0
                    in_non_composable = False
                    pending_non_composable = False
                    saw_composable_annotation = False

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

    print(f"Stage 1 safe extraction complete: {len(new_entries)} new strings across {modified_files_count} files.")

if __name__ == "__main__":
    main()
