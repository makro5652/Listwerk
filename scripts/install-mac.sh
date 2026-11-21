#!/bin/zsh
# Baut Listwerk für macOS und installiert es nach /Applications.
# Ohne Sandbox (wie bisher installiert): Daten bleiben in ~/Library/Application Support/Listwerk.
# Mit Sandbox läge die Datenbank im Container und die App startete leer.
set -e
cd "${0:A:h}/.."
xcodegen -q
xcodebuild -project Listwerk.xcodeproj -scheme Listwerk-macOS -configuration Release -derivedDataPath build/dd \
  -quiet build CODE_SIGN_ENTITLEMENTS=""
# Mit dem eigenen Entwicklerzertifikat signieren: dann gilt „Immer erlauben“ im Schlüsselbund auch nach Updates.
# Ad-hoc signiert fragt macOS nach jedem Neubau erneut nach dem Schlüsselbund-Passwort.
ID=$(security find-identity -v -p codesigning | awk -F'"' '/Apple Development/ {print $2; exit}')
if [[ -n "$ID" ]]; then
  codesign --force --deep --options runtime --sign "$ID" build/dd/Build/Products/Release/Listwerk.app
fi
osascript -e 'quit app "Listwerk"' 2>/dev/null || true
sleep 1
rm -rf /Applications/Listwerk.app
ditto build/dd/Build/Products/Release/Listwerk.app /Applications/Listwerk.app
echo "Installiert: $(defaults read /Applications/Listwerk.app/Contents/Info.plist CFBundleShortVersionString)"
