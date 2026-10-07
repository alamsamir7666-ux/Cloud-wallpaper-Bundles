# Cloud-wallpaper-Bundles

Curated **extension bundles** for [Cloudimage](https://github.com/alamsamir7666-ux/Cloud-Wallpaper) (v1.2.2+):
named packs of wallpaper sources the app renders as bundle cards with
per-extension selection and one-tap bulk install.

The publishable artifacts live on this repository's **`gh-pages` branch**:
an `index.json` (with its `bundles` array) plus the sha256-verified
extension packages it references.

## Add this repository to the app

Open **Cloudimage → Extensions → Add repository** and paste either:

```
https://github.com/alamsamir7666-ux/Cloud-wallpaper-Bundles
```

or the raw index directly:

```
https://raw.githubusercontent.com/alamsamir7666-ux/Cloud-wallpaper-Bundles/gh-pages/index.json
```

The repo browser shows the **Bundles** section above the raw catalog:
each card carries its name, member count and how many are already
installed. Tapping one opens the bundle screen — checkboxes pre-checked
on everything not yet installed, one **Install selected** button, and
per-member uninstall/update afterwards.

## Bundles

| Bundle | What's inside |
|---|---|
| **Essential Wallpapers** | WallpaperFlare — HD, 2K, 4K, 5K wallpapers from wallpaperflare.com, keyless scraper |

## Repository layout

- `tools/bundles.json` — the bundle definitions (id, name, description, packageIds)
- `tools/build_index.py` — regenerates `gh-pages/index.json` from a directory of built `.zip` packages plus `bundles.json`
- `gh-pages/` (branch) — `index.json` + `cloudimage.*.zip` packages; what the app fetches

### Publishing an update

1. Build the provider packages from the
   [main app repository](https://github.com/alamsamir7666-ux/Cloud-Wallpaper):
   `./gradlew :providers:<name>:packageExtension`
2. Copy `providers/<name>/build/outputs/extension/*.zip` into a staging dir
3. Edit `tools/bundles.json` if pack membership changed
4. Regenerate and commit:

```bash
git checkout gh-pages
python3 tools/build_index.py <staging-dir-with-zips> --output index.json
cp <staging-dir-with-zips>/*.zip .
git add index.json *.zip
git commit -m "publish: <what changed>"
git push origin gh-pages
```

The index builder refuses to publish a bundle whose `packageIds`
reference packages not present in the staging directory, so a bundle can
never ship half-resolved.

## Package format

Each package is the app's standard extension zip — `extension.json`
manifest (id, apiVersion 1, entryClass) plus a dexed provider compiled
against the [provider API](https://github.com/alamsamir7666-ux/Cloud-Wallpaper/tree/main/provider/api).
The app verifies every download against the index's sha256 before
installing.
