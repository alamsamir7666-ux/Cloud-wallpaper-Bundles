# Cloud-wallpaper-Bundles

Curated **extension bundles** and the extension **catalog** for
[Cloudimage](https://github.com/alamsamir7666-ux/Cloud-Wallpaper) (v1.2.2+):
named packs of wallpaper sources the app renders as bundle cards with
per-extension selection and one-tap bulk install, plus standalone
packages installable individually.

This is the app's **sole extension distribution channel** — the app
repository ships only the app itself with Wallhaven built in, and every
other wallpaper source lives here.

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
| **Essential Wallpapers** | WallpaperFlare — HD, 2K, 4K, 5K wallpapers from wallpaperflare.com, keyless scraper; WallpapersDen — HD, 4K, 5K, 8K wallpapers from wallpapersden.com, keyless scraper; WallHere — free HD community wallpapers from wallhere.com, keyless scraper with tag-search (605 baked tags) and the site's SFW purity switch; Zastavki — browse-first shelves (36 categories) from zastavki.com, keyless scraper |

The catalog also carries **standalone packages** outside any bundle:
Unsplash and Pixabay (free API key required), Pexels (free API key
required), WallpaperCave and HDQWalls (keyless scrapers).

## Repository layout

- `providers/` — the provider extension sources maintained here (build happens in an app-repo checkout)
- `tools/bundles.json` — the bundle definitions (id, name, description, packageIds)
- `tools/build_index.py` — regenerates `gh-pages/index.json` from a directory of built `.zip` packages plus `bundles.json`
- `gh-pages/` (branch) — `index.json` + `cloudimage.*.zip` packages; what the app fetches

### Publishing an update

The provider sources live in `providers/` in this repository (the app
repository ships only the built-in Wallhaven provider). The older eight
are also recoverable from the app repository's git history (any commit
before the removal). To build one, copy its `providers/<name>` module
into a checkout of the app repository, add the matching
`include(":providers:<name>")` line to `settings.gradle.kts`, and run
`./gradlew :providers:<name>:packageExtension` there:

1. Copy `providers/<name>/build/outputs/extension/*.zip` into a staging dir
2. Edit `tools/bundles.json` if pack membership changed
3. Regenerate and commit:

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
