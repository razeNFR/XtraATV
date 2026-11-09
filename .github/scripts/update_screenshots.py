#!/usr/bin/env python3
import glob
import os
import subprocess
import sys

MAPPING = {
    "readme_menu": "menu.jpg",
    "readme_home": "home.jpg",
    "readme_search": "search.jpg",
    "readme_games": "games.jpg",
    "readme_category": "category.jpg",
    "readme_sort": "sort.jpg",
    "readme_sort_panel": "sort.jpg",
    "readme_player": "player.jpg",
    "readme_player_hud": "player.jpg",
    "readme_multistream": "multistream.jpg",
}


def convert_png_to_jpg(src_png: str, dst_jpg: str) -> bool:
    try:
        from PIL import Image

        with Image.open(src_png) as im:
            rgb = im.convert("RGB").resize((1280, 720), Image.Resampling.LANCZOS)
            rgb.save(dst_jpg, "JPEG", quality=88, optimize=True)
        return True
    except Exception:
        pass

    res = subprocess.run(
        ["ffmpeg", "-y", "-i", src_png, "-vf", "scale=1280:720", "-q:v", "3", dst_jpg],
        stdout=subprocess.DEVNULL,
        stderr=subprocess.DEVNULL,
    )
    return res.returncode == 0


def main() -> int:
    src_dir = sys.argv[1] if len(sys.argv) > 1 else "tv-out"
    dst_dir = sys.argv[2] if len(sys.argv) > 2 else "docs/screenshots"
    os.makedirs(dst_dir, exist_ok=True)
    updated = 0
    for png in sorted(glob.glob(os.path.join(src_dir, "*.png"))):
        base = os.path.splitext(os.path.basename(png))[0]
        parts = base.split("_", 1)
        key = parts[1] if len(parts) == 2 and parts[0].isdigit() else base
        target_name = MAPPING.get(key)
        if not target_name or os.path.getsize(png) < 10_000:
            continue
        dst_path = os.path.join(dst_dir, target_name)
        if convert_png_to_jpg(png, dst_path):
            print(f"Updated {dst_path} from {png}")
            updated += 1
    print(f"{updated} screenshot(s) updated.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
