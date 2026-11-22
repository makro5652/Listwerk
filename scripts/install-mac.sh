#!/bin/zsh
# Baut ToDo-Listwerk für macOS und installiert es nach /Applications (ersetzt eine ältere Listwerk.app).
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
  codesign --force --deep --options runtime --sign "$ID" build/dd/Build/Products/Release/ToDo-Listwerk.app
fi
for n in Listwerk ToDo-Listwerk; do osascript -e "quit app \"$n\"" 2>/dev/null || true; done
sleep 1
rm -rf /Applications/Listwerk.app /Applications/ToDo-Listwerk.app
ditto build/dd/Build/Products/Release/ToDo-Listwerk.app /Applications/ToDo-Listwerk.app
echo "Installiert: $(defaults read /Applications/ToDo-Listwerk.app/Contents/Info.plist CFBundleShortVersionString)"
