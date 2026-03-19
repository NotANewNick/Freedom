// ═════════════════════════════════════════════════════════════════════════════
//  TunnelRowView — Individual tunnel profile card
// ═════════════════════════════════════════════════════════════════════════════

import SwiftUI

struct TunnelRowView: View {

    let profile: TunnelProfile
    @State private var isActive = false

    var body: some View {
        HStack(spacing: 12) {
            Image(systemName: iconName)
                .font(.title2)
                .foregroundStyle(isActive ? .green : .secondary)
                .frame(width: 40)

            VStack(alignment: .leading, spacing: 4) {
                Text(profile.name.isEmpty ? profile.type.uppercased() : profile.name)
                    .font(.headline)

                if !profile.publicAddress.isEmpty {
                    Text(profile.publicAddress)
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }

                Text(profile.type.uppercased())
                    .font(.caption2)
                    .padding(.horizontal, 8)
                    .padding(.vertical, 2)
                    .background(.blue.opacity(0.15))
                    .clipShape(Capsule())
            }

            Spacer()

            Toggle("", isOn: $isActive)
                .labelsHidden()
                .onChange(of: isActive) { _, newValue in
                    if newValue {
                        // Start tunnel
                        VPNManager.shared.startTunnel(profile: profile)
                    } else {
                        VPNManager.shared.stopTunnel()
                    }
                }
        }
        .padding(.vertical, 4)
    }

    private var iconName: String {
        switch profile.type {
        case TunnelProfile.TYPE_PLAYIT: return "play.circle.fill"
        case TunnelProfile.TYPE_NGROK:  return "arrow.triangle.branch"
        case TunnelProfile.TYPE_OVPN:   return "lock.shield.fill"
        default: return "network"
        }
    }
}
