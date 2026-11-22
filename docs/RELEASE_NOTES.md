## ToDo-Listwerk 0.5.0 (macOS)

- **Englische Oberfläche**, standardmäßig aktiv. Umschalten unter Einstellungen → Darstellung → „Sprache / Language“ (English, Deutsch). Texte wechseln sofort, die Systemmenüs nach einem Neustart.
- **Schnelleingabe versteht auch Englisch:** „Call parents tomorrow 8am !“, today, next Monday, at 3:30pm, every 2 weeks, weekdays, in 3 days.
- Angezeigter Programmname jetzt **ToDo-Listwerk** (Daten, Schlüsselbund und Nextcloud-Ordner bleiben unverändert).

### Installieren

1. `ToDo-Listwerk-…-macOS.dmg` laden, öffnen und die App in den Ordner „Programme“ ziehen. Eine ältere `Listwerk.app` dort löschen.
2. Die App ist nur ad-hoc signiert (kein Apple-Developer-Zertifikat, nicht notarisiert). Beim ersten Start meldet macOS deshalb, dass die App nicht geprüft werden kann:
   Systemeinstellungen → Datenschutz & Sicherheit → „Dennoch öffnen“. Alternativ im Terminal:
   `xattr -dr com.apple.quarantine /Applications/ToDo-Listwerk.app`
3. Da die Signatur bei jedem Build neu ist, fragt macOS beim ersten Zugriff erneut nach dem Schlüsselbund („Immer erlauben“).

Universal-Build (Apple Silicon und Intel), macOS 14 oder neuer.
