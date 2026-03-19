// ═════════════════════════════════════════════════════════════════════════════
//  ChatBubbleView — Message bubble with send/recv direction
// ═════════════════════════════════════════════════════════════════════════════

import SwiftUI

struct ChatBubbleView: View {

    let message: MessageData

    private var isSent: Bool { message.direction == MessageData.SENT }

    var body: some View {
        HStack {
            if isSent { Spacer(minLength: 60) }

            VStack(alignment: isSent ? .trailing : .leading, spacing: 4) {
                // File transfer indicator
                if message.messageType == "FILE_SENT" || message.messageType == "FILE_RECEIVED" {
                    HStack(spacing: 4) {
                        Image(systemName: "doc.fill")
                            .font(.caption)
                        Text(message.content ?? "File")
                            .font(.callout)
                    }
                } else {
                    Text(message.content ?? "")
                        .font(.callout)
                }

                if let timestamp = message.timestamp {
                    Text(timestamp)
                        .font(.caption2)
                        .foregroundStyle(.secondary)
                }
            }
            .padding(.horizontal, 14)
            .padding(.vertical, 8)
            .background(isSent ? Color.blue : Color(.systemGray5))
            .foregroundStyle(isSent ? .white : .primary)
            .clipShape(RoundedRectangle(cornerRadius: 16))

            if !isSent { Spacer(minLength: 60) }
        }
    }
}
