// ═════════════════════════════════════════════════════════════════════════════
//  TunnelsView — VPN tunnel profile list
// ═════════════════════════════════════════════════════════════════════════════

import SwiftUI

struct TunnelsView: View {

    @StateObject private var viewModel = TunnelProfileViewModel()
    @State private var showAddSheet = false

    var body: some View {
        NavigationStack {
            List {
                if viewModel.profiles.isEmpty {
                    ContentUnavailableView(
                        "No Tunnel Profiles",
                        systemImage: "network.slash",
                        description: Text("Add a tunnel profile to get started.")
                    )
                } else {
                    ForEach(viewModel.profiles) { profile in
                        TunnelRowView(profile: profile)
                    }
                    .onDelete { indexSet in
                        for index in indexSet {
                            viewModel.deleteProfile(viewModel.profiles[index])
                        }
                    }
                }
            }
            .navigationTitle("Tunnels")
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Button { showAddSheet = true } label: {
                        Image(systemName: "plus")
                    }
                }
            }
            .sheet(isPresented: $showAddSheet) {
                AddTunnelSheet { profile in
                    viewModel.addProfile(profile)
                }
            }
        }
    }
}
