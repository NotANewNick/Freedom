// ═════════════════════════════════════════════════════════════════════════════
//  SettingsView — Server controls, DDNS, key chain
// ═════════════════════════════════════════════════════════════════════════════

import SwiftUI
import CoreImage.CIFilterBuiltins

struct SettingsView: View {

    @StateObject private var viewModel = SettingsViewModel()
    @State private var showQR = false
    @State private var showAddDdns = false
    @State private var isGeneratingKeys = false

    var body: some View {
        NavigationStack {
            Form {
                // MARK: - Server
                Section("Server") {
                    HStack {
                        TextField("Port", text: $viewModel.serverPort)
                            .keyboardType(.numberPad)
                            .frame(width: 80)

                        Spacer()

                        if viewModel.isServerRunning {
                            Button("Stop") { viewModel.stopServer() }
                                .foregroundStyle(.red)
                        } else {
                            Button("Start") { viewModel.startServer() }
                                .foregroundStyle(.green)
                        }
                    }

                    HStack {
                        Circle()
                            .fill(viewModel.isServerRunning ? .green : .gray)
                            .frame(width: 10, height: 10)
                        Text(viewModel.isServerRunning ? "Running" : "Stopped")
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    }
                }

                // MARK: - My Info & QR
                Section("My Info") {
                    TextField("Name", text: $viewModel.myName)
                    TextField("DDNS Domains (comma-separated)", text: $viewModel.myDomains)
                    TextField("Ports (comma-separated)", text: $viewModel.myPorts)

                    Toggle("Discoverable", isOn: $viewModel.isSearchable)

                    Button("Save") { viewModel.saveMyInfo() }

                    Button("Show QR Code") { showQR = true }
                }

                // MARK: - Key Generation
                Section("Key Chain") {
                    Button {
                        isGeneratingKeys = true
                        Task {
                            await viewModel.generateKeys()
                            isGeneratingKeys = false
                        }
                    } label: {
                        HStack {
                            Text("Generate Keys")
                            if isGeneratingKeys { Spacer(); ProgressView() }
                        }
                    }
                    .disabled(isGeneratingKeys)

                    ForEach(0..<6, id: \.self) { i in
                        if !viewModel.keySegments[i].isEmpty {
                            HStack {
                                Text("Slot \(i)")
                                    .font(.caption.bold())
                                Spacer()
                                Text(FreedomCrypto.keyFingerprint(viewModel.keySegments[i]))
                                    .font(.caption.monospaced())
                                    .lineLimit(1)
                                Button {
                                    UIPasteboard.general.string = viewModel.keySegments[i]
                                } label: {
                                    Image(systemName: "doc.on.doc")
                                        .font(.caption)
                                }
                            }
                        }
                    }
                }

                // MARK: - DDNS
                Section("DDNS") {
                    ForEach(viewModel.ddnsConfigs) { config in
                        DdnsConfigRowView(config: config)
                    }
                    .onDelete { indexSet in
                        for i in indexSet { viewModel.removeDdnsConfig(at: i) }
                    }

                    Button("Add DDNS Service") { showAddDdns = true }

                    Button("Update All DDNS") {
                        Task { await viewModel.updateAllDdns() }
                    }
                }

                // MARK: - Status
                if !viewModel.statusMessage.isEmpty {
                    Section {
                        Text(viewModel.statusMessage)
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    }
                }
            }
            .navigationTitle("Settings")
            .sheet(isPresented: $showQR) {
                QRDisplaySheet(
                    name: viewModel.myName,
                    domains: viewModel.myDomains,
                    ports: viewModel.myPorts
                )
            }
            .sheet(isPresented: $showAddDdns) {
                DdnsConfigView { config in
                    viewModel.addDdnsConfig(config)
                }
            }
        }
    }
}

struct DdnsConfigRowView: View {
    let config: DdnsConfig

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            HStack {
                Text(config.serviceType.displayName)
                    .font(.headline)
                if config.serviceType.isAutomatable {
                    Image(systemName: "bolt.fill")
                        .font(.caption)
                        .foregroundStyle(.yellow)
                }
            }
            Text(config.publicHostname)
                .font(.caption)
                .foregroundStyle(.secondary)
        }
    }
}

struct QRDisplaySheet: View {
    let name: String
    let domains: String
    let ports: String
    @Environment(\.dismiss) private var dismiss
    @State private var qrImage: UIImage?

    var body: some View {
        NavigationStack {
            VStack(spacing: 20) {
                if let qrImage {
                    Image(uiImage: qrImage)
                        .interpolation(.none)
                        .resizable()
                        .scaledToFit()
                        .frame(width: 250, height: 250)
                        .padding()
                } else {
                    ProgressView()
                }

                Text("Scan this QR to add me as a contact")
                    .font(.callout)
                    .foregroundStyle(.secondary)
            }
            .navigationTitle("My QR Code")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Button("Done") { dismiss() }
                }
            }
            .onAppear { generateQR() }
        }
    }

    private func generateQR() {
        let bootstrapKey = FreedomCrypto.generateBootstrapKey()
        BootstrapKeyHolder.shared.activeBootstrapKey = bootstrapKey

        let firstDomain = domains.split(separator: ",").first.map(String.init) ?? ""
        let firstPort = ports.split(separator: ",").first.flatMap { Int($0.trimmingCharacters(in: .whitespaces)) } ?? 22176

        let qrData: [String: Any] = [
            "app": "freedom",
            "ddns": firstDomain,
            "port": firstPort,
            "key": bootstrapKey.base64EncodedString()
        ]

        guard let jsonData = try? JSONSerialization.data(withJSONObject: qrData),
              let jsonString = String(data: jsonData, encoding: .utf8) else { return }

        let context = CIContext()
        let filter = CIFilter.qrCodeGenerator()
        filter.message = Data(jsonString.utf8)
        filter.correctionLevel = "L"

        guard let output = filter.outputImage else { return }
        let scaled = output.transformed(by: CGAffineTransform(scaleX: 10, y: 10))
        guard let cgImage = context.createCGImage(scaled, from: scaled.extent) else { return }
        qrImage = UIImage(cgImage: cgImage)
    }
}
