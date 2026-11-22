import SwiftUI
import UniformTypeIdentifiers
import ListwerkCore

struct SettingsView: View {
    @Environment(AppModel.self) private var model
    @Environment(\.colorScheme) private var scheme
    @State private var token = ""

    @State private var planwerkResult: String?
    @State private var msClientID = ""
    @State private var msResult: String?
    @State private var exportFile: ExportFile?
    @State private var exportType = UTType.json
    @State private var exportName = ""
    @State private var importing = false
    @State private var backupResult: String?

    private func export(_ data: Data, _ type: UTType, _ name: String) {
        exportType = type; exportName = name; exportFile = ExportFile(data)
    }

    private func save() {
        model.planwerkToken = token
        planwerkResult = L("Prüfe …")
        Task { planwerkResult = await model.refreshPlanwerk() }
    }

    var body: some View {
        @Bindable var model = model
        Form {
            Section(L("Darstellung")) {
                Picker(L("Sprache") + " / Language", selection: $model.language) {
                    ForEach(AppLanguage.allCases) { Text($0.nativeName).tag($0) }
                }
                Picker(L("Ansicht"), selection: $model.layout) {
                    ForEach(AppLayout.allCases) { Text($0.label).tag($0) }
                }
                .pickerStyle(.segmented)
                Picker(L("Design"), selection: $model.themeID) {
                    Text(L("System (Papier / Graphit)")).tag(ThemeCatalog.systemID)
                    ForEach(ThemeCatalog.all) { Text(L($0.name)).tag($0.id) }
                }
                ThemePreview(theme: model.theme(systemIsDark: scheme == .dark))
            }
            Section(L("Nextcloud")) {
                if model.account == nil {
                    Text(L("Ohne Konto: Listen liegen nur auf diesem Mac.")).foregroundStyle(.secondary)
                    Button(L("Mit Nextcloud anmelden und abgleichen")) { model.showLogin = true }
                }
                if let a = model.account {
                    LabeledContent("Server", value: a.serverURL.host ?? a.serverURL.absoluteString)
                    LabeledContent(L("Konto"), value: a.loginName)
                    Button(L("Jetzt abgleichen")) { Task { await model.syncNow() } }
                    Button(L("Abmelden und lokale Daten löschen"), role: .destructive) { model.logout() }
                }
            }
            Section(L("Planwerk (optional)")) {
                TextField(L("Adresse, z. B. https://planwerk.beispiel.de"), text: $model.planwerkURL)
                    .autocorrectionDisabled()
                SecureField(L("API-Token (pwk_…)"), text: $token)
                    .onSubmit { save() }
                Button(L("Token speichern und prüfen")) { save() }
                if let planwerkResult { Text(planwerkResult).font(.footnote).textSelection(.enabled) }
            }
            Section(L("Microsoft To Do (Import)")) {
                TextField(L("App-ID (Client-ID aus Azure)"), text: $msClientID)
                    .autocorrectionDisabled()
                    .onChange(of: msClientID) { model.msClientID = msClientID }
                if let c = model.msLogin {
                    Text(L("Code {0} auf {1} eingeben und bestätigen.", c.userCode, c.verificationURL.absoluteString))
                        .textSelection(.enabled)
                    Link(L("Anmeldeseite öffnen"), destination: c.verificationURL)
                }
                Button(model.msConnected ? L("Jetzt importieren") : L("Verbinden und importieren")) {
                    msResult = L("Läuft …")
                    Task { msResult = await model.importMSTodo() }
                }
                if model.msConnected { Button(L("Verbindung trennen"), role: .destructive) { model.disconnectMSTodo() } }
                if let msResult { Text(msResult).font(.footnote).textSelection(.enabled) }
                Text(L("Einseitig: To Do → ToDo-Listwerk. Änderungen in To Do überschreiben beim nächsten Import die ToDo-Listwerk-Einträge."))
                    .font(.footnote).foregroundStyle(.secondary)
            }
            Section(L("Sichern und Drucken")) {
                Button(L("Alle Listen sichern (JSON) …")) {
                    do { export(try model.store.exportBackup(), .json, L("ToDo-Listwerk-Sicherung {0}", Date().formatted(.iso8601.year().month().day()))) }
                    catch { backupResult = L("Fehler: {0}", error.localizedDescription) }
                }
                Button(L("Sicherung importieren …")) { importing = true }
                Button(L("Alle Listen als PDF zum Ausdrucken …")) { export(model.pdfAllLists(), .pdf, "ToDo-Listwerk") }
                if let backupResult { Text(backupResult).font(.footnote).textSelection(.enabled) }
                Text(L("Die Sicherung ist unverschlüsselt. Beim Import werden gleichnamige Listen ergänzt und Einträge mit gleicher ID aktualisiert; gelöscht wird nichts."))
                    .font(.footnote).foregroundStyle(.secondary)
            }
            Section {
                Text("ToDo-Listwerk \(ListwerkCoreInfo.version)").font(.footnote)
            }
        }
        .formStyle(.grouped)
        .id(model.language)
        .environment(\.locale, L10n.locale)
        .fileExporter(isPresented: Binding { exportFile != nil } set: { if !$0 { exportFile = nil } },
                      document: exportFile, contentType: exportType, defaultFilename: exportName) { result in
            switch result {
            case .success(let url): backupResult = L("Gespeichert: {0}", url.lastPathComponent)
            case .failure(let e): backupResult = L("Fehler: {0}", e.localizedDescription)
            }
        }
        .fileImporter(isPresented: $importing, allowedContentTypes: [.json]) { result in
            switch result {
            case .success(let url): backupResult = model.importBackup(from: url)
            case .failure(let e): backupResult = L("Fehler: {0}", e.localizedDescription)
            }
        }
        .frame(minWidth: 420, minHeight: 480)
        .onAppear { token = model.planwerkToken; msClientID = model.msClientID }
    }
}

struct ThemePreview: View {
    let theme: Theme
    var body: some View {
        HStack(spacing: 0) {
            ForEach([\ThemeColors.background, \.sidebar, \.surface, \.accent, \.success, \.warning, \.danger], id: \.self) { kp in
                Rectangle().fill(theme.color(kp))
            }
        }
        .frame(height: 24)
        .clipShape(RoundedRectangle(cornerRadius: 6))
    }
}
