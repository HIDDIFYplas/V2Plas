# Applied automatically during the build (see the overlay step in build-v2plas.yml).
# It connects the new V2 Plas screens to the original v2rayNG code.
import sys

BASE = "V2rayNG/app/src/main/java/com/v2ray/ang/ui/main/"


def edit(path, old, new, what):
    s = open(path, encoding="utf-8").read()
    if old not in s:
        sys.exit("ERROR: patch anchor not found: " + what)
    open(path, "w", encoding="utf-8").write(s.replace(old, new, 1))
    print("patched:", what)


# 1) Start the new V2 Plas screen instead of the original main screen
edit(
    BASE + "MainActivity.kt",
    "        MainScreen(\n            mainViewModel = mainViewModel,",
    "        V2PlasApp(\n            mainViewModel = mainViewModel,",
    "MainActivity -> V2PlasApp",
)

# 2) The server list keeps working, but its own bottom bar is replaced by the new navigation
path = BASE + "MainScreen.kt"
s = open(path, encoding="utf-8").read()
start = s.find("            bottomBar = {\n                MainBottomBar(")
if start < 0:
    sys.exit("ERROR: patch anchor not found: MainScreen bottomBar")
end = s.find("            },\n", start)
if end < 0:
    sys.exit("ERROR: patch anchor not found: MainScreen bottomBar end")
end += len("            },\n")
s = s[:start] + "            bottomBar = {},\n" + s[end:]
open(path, "w", encoding="utf-8").write(s)
print("patched: MainScreen bottomBar")
