import SwiftUI
import ListwerkCore

struct SettingsView: View {
    @Environment(AppModel.self) private var model
    @Environment(\.colorScheme) private var scheme
    @State private var token = ""

    var body: some View {
        @Bindable var model = model
        Form {
            Section("Darstellung") {
                Picker("Design", selection: $model.themeID) {
                    Text("System (Papier / Graphit)").tag(ThemeCatalog.systemID)
                    ForEach(ThemeCatalog.all) { Text($0.name).tag($0.id) }
                }
                ThemePreview(theme: model.theme(systemIsDark: scheme == .dark))
            }
            Section("Nextcloud") {
                if let a = model.account {
                    LabeledContent("Server", value: a.serverURL.host ?? a.serverURL.absoluteString)
                    LabeledContent("Konto", value: a.loginName)
                    Button("Jetzt abgleichen") { Task { await model.syncNow() } }
                    Button("Abmelden und lokale Daten löschen", role: .destructive) { model.logout() }
                }
            }
            Section("Planwerk (optional)") {
                TextField("Adresse, z. B. https://planwerk.beispiel.de", text: $model.planwerkURL)
                    .autocorrectionDisabled()
                SecureField("API-Token (pwk_…)", text: $token)
                    .onSubmit { model.planwerkToken = token; Task { await model.refreshPlanwerk() } }
                Button("Token speichern und prüfen") {
                    model.planwerkToken = token
                    Task { await model.refreshPlanwerk() }
                }
            }
            Section {
                Text("Listwerk \(ListwerkCoreInfo.version)").font(.footnote)
            }
        }
        .formStyle(.grouped)
        .frame(minWidth: 420, minHeight: 480)
        .onAppear { token = model.planwerkToken }
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
