// ═════════════════════════════════════════════════════════════════════════════
//  MainTabView — 3-tab container matching Android layout
// ═════════════════════════════════════════════════════════════════════════════

import SwiftUI

struct MainTabView: View {

    @State private var selectedTab = 1 // Messages is default

    init() {
        // Start VPN monitoring and DDNS
        DdnsVpnMonitor.shared.startMonitoring()
    }

    var body: some View {
        TabView(selection: $selectedTab) {
            TunnelsView()
                .tabItem {
                    Label("Tunnels", systemImage: "network")
                }
                .tag(0)

            MessagesView()
                .tabItem {
                    Label("Messages", systemImage: "message.fill")
                }
                .tag(1)

            SettingsView()
                .tabItem {
                    Label("Settings", systemImage: "gearshape.fill")
                }
                .tag(2)
        }
    }
}
