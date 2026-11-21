import SwiftUI
import ListwerkCore

struct LoginView: View {
    @Environment(AppModel.self) private var model
    @Environment(\.openURL) private var openURL
    @Environment(\.theme) private var theme
    @State private var server = ""
    @State private var busy = false

    var body: some View {
        VStack(spacing: 20) {
            Image(systemName: "checklist").font(.system(size: 48)).foregroundStyle(theme.color(\.accent))
            Text("Listwerk").font(.largeTitle.bold())
            Text("Mit Nextcloud verbinden. Das Passwort geben Sie nie in dieser App ein: Die Anmeldung läuft im Browser, die App erhält ein eigenes App-Passwort.")
                .multilineTextAlignment(.center)
                .foregroundStyle(theme.color(\.textSecondary))
                .frame(maxWidth: 420)
            TextField("cloud.beispiel.de", text: $server)
                .textFieldStyle(.roundedBorder)
                .autocorrectionDisabled()
                #if os(iOS)
                .textInputAutocapitalization(.never)
                .keyboardType(.URL)
                #endif
                .frame(maxWidth: 420)
            Button {
                busy = true
                Task { await model.login(server: server); busy = false }
            } label: {
                if busy { ProgressView() } else { Text("Anmelden") }
            }
            .buttonStyle(.borderedProminent)
            .disabled(server.trimmingCharacters(in: .whitespaces).isEmpty || busy)
            if !model.onboarded {
                // Erststart: auch ganz ohne Konto nutzbar; eine spätere Anmeldung lädt alles hoch.
                Button("Ohne Anmeldung nutzen") { model.skipLogin() }.buttonStyle(.borderless)
                Text("Alles bleibt auf diesem Mac. Meldest du dich später an, werden deine Listen hochgeladen und abgeglichen.")
                    .font(.footnote).multilineTextAlignment(.center).frame(maxWidth: 420)
                    .foregroundStyle(theme.color(\.textSecondary))
            } else {
                Button("Abbrechen") { model.showLogin = false }.buttonStyle(.borderless)
                Text("Deine bisherigen Listen und Aufgaben werden nach der Anmeldung in die Nextcloud hochgeladen.")
                    .font(.footnote).multilineTextAlignment(.center).frame(maxWidth: 420)
                    .foregroundStyle(theme.color(\.textSecondary))
            }
            if let status = model.statusMessage {
                Text(status).font(.footnote).foregroundStyle(theme.color(\.textSecondary))
            }
        }
        .padding(32)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .onChange(of: model.loginURL) { _, url in
            if let url { openURL(url) }
        }
    }
}
