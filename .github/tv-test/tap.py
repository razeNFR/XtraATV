# Prints "x y", the center of a node of a uiautomator dump: by resource id, by text, or the n-th node of a class.
import re, sys
xml = open(sys.argv[1], encoding="utf-8", errors="ignore").read()
mode, value = sys.argv[2], sys.argv[3]
nodes = re.findall(r"<node [^>]*>", xml)
matches = []
for n in nodes:
    attrs = dict(re.findall(r'([\w-]+)="([^"]*)"', n))
    if mode == "id" and attrs.get("resource-id", "").endswith(":id/" + value):
        matches.append(attrs)
    elif mode == "text" and attrs.get("text", "").lower() == value.lower():
        matches.append(attrs)
    elif mode == "class" and attrs.get("class", "") == value:
        matches.append(attrs)
index = int(sys.argv[4]) if len(sys.argv) > 4 else 0
if len(matches) > index:
    x1, y1, x2, y2 = map(int, re.findall(r"\d+", matches[index]["bounds"]))
    print((x1 + x2) // 2, (y1 + y2) // 2)
