// ═════════════════════════════════════════════════════════════════════════════
//  ContactBlobView — Contact avatar with health indicator dot
// ═════════════════════════════════════════════════════════════════════════════

import SwiftUI

struct ContactBlobView: View {

    let contact: ContactData
    let state: ConnectionState

    var body: some View {
        HStack(spacing: 12) {
            ZStack(alignment: .bottomTrailing) {
                Circle()
                    .fill(Color.blue.opacity(0.2))
                    .frame(width: 44, height: 44)
                    .overlay {
                        Text(String(contact.name.prefix(1)).uppercased())
                            .font(.headline)
                            .foregroundStyle(.blue)
                    }

                Circle()
                    .fill(dotColor)
                    .frame(width: 12, height: 12)
                    .overlay(Circle().stroke(.white, lineWidth: 2))
            }

            VStack(alignment: .leading, spacing: 2) {
                Text(contact.name)
                    .font(.body)
                    .lineLimit(1)

                Text(stateLabel)
                    .font(.caption2)
                    .foregroundStyle(.secondary)
            }

            Spacer()
        }
        .padding(.vertical, 4)
    }

    private var dotColor: Color {
        switch state {
        case .connected: return .green
        case .degraded:  return .orange
        case .offline:   return .gray
        }
    }

    private var stateLabel: String {
        switch state {
        case .connected: return "Connected"
        case .degraded:  return "Degraded"
        case .offline:   return "Offline"
        }
    }
}
