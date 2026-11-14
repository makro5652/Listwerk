import SwiftUI
import ListwerkCore

struct RootView: View {
    @Environment(AppModel.self) private var model
    @Environment(\.colorScheme) private var scheme
    @Environment(\.scenePhase) private var phase

    var body: some View {
        let theme = model.theme(systemIsDark: scheme == .dark)
        Group {
            if model.account == nil {
                LoginView()
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
        .environment(\.theme, theme)
        .tint(theme.color(\.accent))
        .background(theme.color(\.background))
        .preferredColorScheme(model.themeID == ThemeCatalog.systemID ? nil : (theme.isDark ? .dark : .light))
        .alert("Fehler", isPresented: Binding(get: { model.errorMessage != nil }, set: { if !$0 { model.errorMessage = nil } })) {
            Button("OK", role: .cancel) {}
        } message: { Text(model.errorMessage ?? "") }
        .onChange(of: phase) { _, new in
            if new == .active { Task { await model.syncNow() } }
        }
    }
}
