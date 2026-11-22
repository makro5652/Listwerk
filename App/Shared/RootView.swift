import SwiftUI
import ListwerkCore

struct RootView: View {
    @Environment(AppModel.self) private var model
    @Environment(\.colorScheme) private var scheme
    @Environment(\.scenePhase) private var phase

    var body: some View {
        let theme = model.theme(systemIsDark: scheme == .dark)
        Group {
            if !model.onboarded || model.showLogin {
                LoginView()
            } else if model.layout == .todo {
                TodoRootView()
            } else {
                NavigationSplitView {
                    SidebarView()
                } content: {
                    TaskListView()
                } detail: {
                    TaskDetailPane()
                }
            }
        }
        .id(model.language)
        .environment(\.locale, L10n.locale)
        .environment(\.theme, theme)
        .tint(theme.color(\.accent))
        .background(ThemeBackground())
        .preferredColorScheme(model.themeID == ThemeCatalog.systemID ? nil : (theme.isDark ? .dark : .light))
        .alert(L("Fehler"), isPresented: Binding(get: { model.errorMessage != nil }, set: { if !$0 { model.errorMessage = nil } })) {
            Button("OK", role: .cancel) {}
        } message: { Text(model.errorMessage ?? "") }
        .onChange(of: phase) { _, new in
            if new == .active, model.account != nil { Task { await model.syncNow() } }
        }
    }
}
