"""Apply Android TV launcher metadata and V2 Plas-only legacy/TV artwork."""
from pathlib import Path
import re
import sys

ROOT = Path("V2rayNG/app/src/main")
MANIFEST = ROOT / "AndroidManifest.xml"
def fail(message: str) -> None:
    raise SystemExit("ERROR: Android TV setup: " + message)


def set_android_attribute(tag: str, name: str, value: str) -> str:
    pattern = re.compile(rf'\bandroid:{re.escape(name)}\s*=\s*(["\']).*?\1', re.DOTALL)
    replacement = f'android:{name}="{value}"'
    if pattern.search(tag):
        return pattern.sub(replacement, tag, count=1)
    stripped = tag.rstrip()
    if stripped.endswith("/>"):
        return stripped[:-2].rstrip() + f' {replacement} />'
    return stripped[:-1].rstrip() + f' {replacement}>'


if not MANIFEST.is_file():
    fail(f"manifest not found: {MANIFEST}")

manifest = MANIFEST.read_text(encoding="utf-8")
app_match = re.search(r"<application\b", manifest)
if not app_match:
    fail("<application> tag not found in AndroidManifest.xml")
for feature_name in ("android.software.leanback", "android.hardware.touchscreen"):
    feature_match = re.search(
        rf'<uses-feature\b(?=[^>]*android:name\s*=\s*["\']{re.escape(feature_name)}["\'])[^>]*/?>',
        manifest,
    )
    if feature_match:
        feature_tag = set_android_attribute(feature_match.group(0), "required", "false")
        manifest = manifest[:feature_match.start()] + feature_tag + manifest[feature_match.end():]
        app_match = re.search(r"<application\b", manifest)
    else:
        line_start = manifest.rfind("\n", 0, app_match.start()) + 1
        whitespace = manifest[line_start:app_match.start()]
        insert_at = line_start if not whitespace.strip() else app_match.start()
        manifest = (
            manifest[:insert_at]
            + f'    <uses-feature android:name="{feature_name}" android:required="false" />\n'
            + manifest[insert_at:]
        )
        app_match = re.search(r"<application\b", manifest)

app_match = re.search(r"<application\b[^>]*>", manifest)
if not app_match:
    fail("opening <application> tag not found")
app_tag = app_match.group(0)
app_tag = set_android_attribute(app_tag, "icon", "@mipmap/ic_launcher")
app_tag = set_android_attribute(app_tag, "roundIcon", "@mipmap/ic_launcher_round")
app_tag = set_android_attribute(app_tag, "banner", "@drawable/v2plas_tv_banner")
manifest = manifest[:app_match.start()] + app_tag + manifest[app_match.end():]

activity_match = re.search(
    r'<activity\b(?=[^>]*android:name\s*=\s*["\'][^"\']*MainActivity[^"\']*["\'])[^>]*>.*?</activity>',
    manifest,
    flags=re.DOTALL,
)
if not activity_match:
    fail("could not find the launcher MainActivity declaration")
activity = activity_match.group(0)
if "android.intent.category.LEANBACK_LAUNCHER" not in activity:
    leanback_filter = '''\n            <intent-filter>\n                <action android:name="android.intent.action.MAIN" />\n                <category android:name="android.intent.category.LEANBACK_LAUNCHER" />\n            </intent-filter>'''
    activity = activity.replace("</activity>", leanback_filter + "\n        </activity>", 1)
manifest = manifest[:activity_match.start()] + activity + manifest[activity_match.end():]
MANIFEST.write_text(manifest, encoding="utf-8")

# Vector fallback icons ensure older TV launchers do not fall back to the
# upstream v2rayNG density PNGs. Android 8+ continues to use the adaptive icon.
(ROOT / "res/mipmap-anydpi").mkdir(parents=True, exist_ok=True)
legacy_icon = '''<?xml version="1.0" encoding="utf-8"?>
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="108dp" android:height="108dp"
    android:viewportWidth="108" android:viewportHeight="108">
    <path android:fillColor="#070B1F" android:pathData="M0,0h108v108h-108z" />
    <path android:fillColor="#101A4A" android:strokeColor="#4C8DFF"
        android:strokeWidth="2.6" android:strokeLineJoin="round"
        android:pathData="M54,22 L81,32 L81,55 C81,71 69,81 54,87 C39,81 27,71 27,55 L27,32 Z" />
    <path android:strokeColor="#6C5BD6" android:strokeWidth="1"
        android:strokeLineJoin="round"
        android:pathData="M54,28 L75,36 L75,55 C75,71 69,74 54,80 C39,74 33,71 33,55 L33,36 Z" />
    <path android:fillColor="#FFFFFF" android:pathData="M35,40 L44,40 L50,58 L56,40 L65,40 L54.5,70 L45.5,70 Z" />
    <path android:strokeColor="#7FB0FF" android:strokeWidth="2.4"
        android:strokeLineCap="round" android:strokeLineJoin="round"
        android:pathData="M66,37 C66,32.5 75,32.5 75,37 C75,41 66,45 66,48.5 L75.5,48.5" />
</vector>
'''
(ROOT / "res/mipmap-anydpi/ic_launcher.xml").write_text(legacy_icon, encoding="utf-8")
(ROOT / "res/mipmap-anydpi/ic_launcher_round.xml").write_text(legacy_icon, encoding="utf-8")

# Android TV home-screen banner. The mark is intentionally the same shield as
# the app icon; no upstream app artwork or branding is reused.
tv_banner = '''<?xml version="1.0" encoding="utf-8"?>
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="320dp" android:height="180dp"
    android:viewportWidth="320" android:viewportHeight="180">
    <path android:fillColor="#070B1F" android:pathData="M0,0h320v180h-320z" />
    <path android:fillColor="#101A3A" android:pathData="M18,14h284a14,14 0,0 1 14,14v124a14,14 0,0 1 -14,14h-284a14,14 0,0 1 -14,-14v-124a14,14 0,0 1 14,-14z" />
    <path android:fillColor="#172857" android:pathData="M36,24h248a10,10 0,0 1 10,10v112a10,10 0,0 1 -10,10h-248a10,10 0,0 1 -10,-10v-112a10,10 0,0 1 10,-10z" />
    <path android:fillColor="#101A4A" android:strokeColor="#4C8DFF"
        android:strokeWidth="3" android:strokeLineJoin="round"
        android:pathData="M160,36 L198,49 L198,81 C198,105 181,122 160,132 C139,122 122,105 122,81 L122,49 Z" />
    <path android:strokeColor="#6C5BD6" android:strokeWidth="1.5"
        android:strokeLineJoin="round"
        android:pathData="M160,45 L190,55 L190,81 C190,101 179,114 160,124 C141,114 130,101 130,81 L130,55 Z" />
    <path android:fillColor="#FFFFFF" android:pathData="M137,66 L150,66 L159,91 L168,66 L181,66 L164,108 L154,108 Z" />
    <path android:strokeColor="#7FB0FF" android:strokeWidth="3"
        android:strokeLineCap="round" android:strokeLineJoin="round"
        android:pathData="M180,61 C180,54 194,54 194,61 C194,68 180,73 180,78 L195,78" />
</vector>
'''
(ROOT / "res/drawable/v2plas_tv_banner.xml").parent.mkdir(parents=True, exist_ok=True)
(ROOT / "res/drawable/v2plas_tv_banner.xml").write_text(tv_banner, encoding="utf-8")

# Build-time verification: TV metadata and all three branding resources must exist.
written = MANIFEST.read_text(encoding="utf-8")
for token in (
    "android.software.leanback",
    "android.hardware.touchscreen",
    "android.intent.category.LEANBACK_LAUNCHER",
    'android:icon="@mipmap/ic_launcher"',
    'android:banner="@drawable/v2plas_tv_banner"',
):
    if token not in written:
        fail("manifest verification failed for " + token)
for resource in (
    ROOT / "res/mipmap-anydpi/ic_launcher.xml",
    ROOT / "res/mipmap-anydpi/ic_launcher_round.xml",
    ROOT / "res/drawable/v2plas_tv_banner.xml",
):
    if not resource.is_file() or resource.stat().st_size < 100:
        fail("resource missing or incomplete: " + str(resource))
print("Android TV launcher metadata, V2 Plas icon fallbacks and banner applied.")
