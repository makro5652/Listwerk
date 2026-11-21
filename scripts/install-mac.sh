#!/bin/zsh
# Baut Listwerk für macOS und installiert es nach /Applications.
# Ohne Sandbox (wie bisher installiert): Daten bleiben in ~/Library/Application Support/Listwerk.
# Mit Sandbox läge die Datenbank im Container und die App startete leer.
set -e
cd "${0:A:h}/.."
xcodegen -q
xcodebuild -project Listwerk.xcodeproj -scheme Listwerk-macOS -configuration Release -derivedDataPath build/dd \
  -quiet build CODE_SIGN_ENTITLEMENTS=""
osascript -e 'quit app "Listwerk"' 2>/dev/null || true
sleep 1
rm -rf /Applications/Listwerk.app
ditto build/dd/Build/Products/Release/Listwerk.app /Applications/Listwerk.app
echo "Installiert: $(defaults read /Applications/Listwerk.app/Contents/Info.plist CFBundleShortVersionString)"
