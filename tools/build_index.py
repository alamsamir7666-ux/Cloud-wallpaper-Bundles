#!/usr/bin/env python3
"""Builds the Cloud-wallpaper-Bundles extension repository index.

Unlike the main Cloudimage repository (which publishes every provider it
builds), this repository curates BUNDLES: named packs of extensions the
app (v1.2.2+) renders as bundle cards with one-tap bulk install. The
packages are the same sha256-verified .zip files the app's repo manager
downloads and installs; the bundles live in the index's top-level
"bundles" array:

    {
      "name": "Cloudimage Bundles",
      "packages": [ { "id": ..., "fileName": ..., "sha256": ..., ... } ],
      "bundles": [
        {
          "id": "wallpapers",
          "name": "Essential Wallpapers",
          "description": "The must-have wallpaper sources for Cloudimage.",
          "packageIds": ["cloudimage.wallpaperflare"]
        }
      ]
    }

Usage:
    python3 tools/build_index.py <packages-dir> [--output index.json]

The bundle definitions live in bundles.json next to this script — add a
pack there, drop its .zip files into the packages dir, run this, commit
to gh-pages.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import sys
import zipfile
from pathlib import Path

BUNDLES_FILE = Path(__file__).parent / "bundles.json"


def read_manifest(zip_path: Path) -> dict:
    with zipfile.ZipFile(zip_path) as archive:
        try:
            manifest = json.loads(archive.read("extension.json"))
        except KeyError:
            raise SystemExit(f"{zip_path.name}: missing extension.json — not an extension package")
    for key in ("id", "name", "versionName", "versionCode", "apiVersion"):
        if key not in manifest:
            raise SystemExit(f"{zip_path.name}: manifest is missing '{key}'")
    return manifest


def sha256_of(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for block in iter(lambda: handle.read(64 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def build_index(packages_dir: Path) -> dict:
    # Bundle definitions from bundles.json — the repo's curated packs.
    bundles = json.loads(BUNDLES_FILE.read_text())

    packages = []
    for zip_path in sorted(packages_dir.glob("*.zip")):
        manifest = read_manifest(zip_path)
        categories = [
            category.strip().lower()
            for category in manifest.get("categories", [])
            if isinstance(category, str) and category.strip()
        ]
        packages.append(
            {
                "id": manifest["id"],
                "fileName": zip_path.name,
                "sha256": sha256_of(zip_path),
                "sizeBytes": zip_path.stat().st_size,
                "name": str(manifest["name"]),
                "versionName": str(manifest["versionName"]),
                "versionCode": int(manifest["versionCode"]),
                "apiVersion": int(manifest["apiVersion"]),
                "author": str(manifest.get("author", "")),
                "description": str(manifest.get("description", "")),
                "categories": categories,
            }
        )

    package_ids = {p["id"] for p in packages}
    # Every bundle must resolve against the packages actually shipped —
    # an empty bundle is a build error, not a publishable state.
    for bundle in bundles:
        missing = [pid for pid in bundle.get("packageIds", []) if pid not in package_ids]
        if missing:
            raise SystemExit(f"bundle '{bundle['id']}' references unknown packages: {missing}")
    if not bundles:
        raise SystemExit("bundles.json declares no bundles — nothing to publish")

    return {"name": "Cloudimage Bundles", "packages": packages, "bundles": bundles}


def main() -> int:
    parser = argparse.ArgumentParser(description="Builds the bundles repository index.")
    parser.add_argument("packages_dir", type=Path, help="directory containing built *.zip packages")
    parser.add_argument("--output", type=Path, default=None, help="index.json location (default: <dir>/index.json)")
    args = parser.parse_args()

    if not args.packages_dir.is_dir():
        raise SystemExit(f"{args.packages_dir}: not a directory")

    index = build_index(args.packages_dir)
    output = args.output or args.packages_dir / "index.json"
    output.write_text(json.dumps(index, indent=2) + "\n")

    for package in index["packages"]:
        print(
            f"{package['id']:<28} {package['versionName']:<8} "
            f"{package['sizeBytes']:>8} bytes  {package['sha256'][:12]}..."
        )
    for bundle in index["bundles"]:
        members = ", ".join(bundle["packageIds"])
        print(f"bundle {bundle['id']:<20} \"{bundle['name']}\" -> {members}")
    print(f"\n{len(index['packages'])} package(s), {len(index['bundles'])} bundle(s) -> {output}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
