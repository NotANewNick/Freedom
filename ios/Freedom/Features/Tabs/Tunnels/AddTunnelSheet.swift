// ═════════════════════════════════════════════════════════════════════════════
//  AddTunnelSheet — Add tunnel profile (playit/ngrok/ovpn)
// ═════════════════════════════════════════════════════════════════════════════

import SwiftUI
import UniformTypeIdentifiers

struct AddTunnelSheet: View {

    let onAdd: (TunnelProfile) -> Void
    @Environment(\.dismiss) private var dismiss

    @State private var selectedType = TunnelProfile.TYPE_OVPN
    @State private var name = ""
    @State private var publicHost = ""
    @State private var publicPort = ""
    @State private var secretKey = ""
    @State private var tunnelId = ""
    @State private var showFilePicker = false
    @State private var ovpnPath = ""

    private let types = [TunnelProfile.TYPE_OVPN, TunnelProfile.TYPE_PLAYIT, TunnelProfile.TYPE_NGROK]

    var body: some View {
        NavigationStack {
            Form {
                Picker("Type", selection: $selectedType) {
                    ForEach(types, id: \.self) { type in
                        Text(type.uppercased()).tag(type)
                    }
                }
                .pickerStyle(.segmented)

                TextField("Name", text: $name)

                switch selectedType {
                case TunnelProfile.TYPE_OVPN:
                    Button("Select .ovpn File") {
                        showFilePicker = true
                    }
                    if !ovpnPath.isEmpty {
                        Text(ovpnPath)
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    }

                case TunnelProfile.TYPE_PLAYIT:
                    TextField("Secret Key", text: $secretKey)
                    TextField("Tunnel ID", text: $tunnelId)

                case TunnelProfile.TYPE_NGROK:
                    TextField("Auth Token", text: $secretKey)
                    TextField("Public Host", text: $publicHost)
                    TextField("Public Port", text: $publicPort)
                        .keyboardType(.numberPad)

                default:
                    EmptyView()
                }
            }
            .navigationTitle("Add Tunnel")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarLeading) {
                    Button("Cancel") { dismiss() }
                }
                ToolbarItem(placement: .topBarTrailing) {
                    Button("Add") {
                        let profile = TunnelProfile(
                            name: name,
                            type: selectedType,
                            publicHost: publicHost,
                            publicPort: Int(publicPort) ?? 0,
                            secretKey: secretKey,
                            tunnelId: tunnelId,
                            ovpnPath: ovpnPath
                        )
                        onAdd(profile)
                        dismiss()
                    }
                }
            }
            .fileImporter(isPresented: $showFilePicker, allowedContentTypes: [.data]) { result in
                if let url = try? result.get() {
                    // Copy to app documents
                    let dest = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask).first!
                        .appendingPathComponent("vpn_profiles", isDirectory: true)
                    try? FileManager.default.createDirectory(at: dest, withIntermediateDirectories: true)
                    let target = dest.appendingPathComponent(url.lastPathComponent)
                    try? FileManager.default.copyItem(at: url, to: target)
                    ovpnPath = target.path
                }
            }
        }
    }
}
