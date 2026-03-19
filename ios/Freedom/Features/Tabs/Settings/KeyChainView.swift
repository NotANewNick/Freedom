// ═════════════════════════════════════════════════════════════════════════════
//  KeyChainView — Key fingerprints and entropy generation
// ═════════════════════════════════════════════════════════════════════════════

import SwiftUI

struct KeyChainView: View {

    let segments: [String]

    var body: some View {
        VStack(spacing: 12) {
            ForEach(0..<segments.count, id: \.self) { i in
                if !segments[i].isEmpty {
                    HStack {
                        Text("Key \(i)")
                            .font(.caption.bold())
                            .frame(width: 50, alignment: .leading)

                        Text(FreedomCrypto.keyFingerprint(segments[i]))
                            .font(.system(.caption, design: .monospaced))
                            .lineLimit(1)

                        Spacer()

                        let entropy = FreedomCrypto.entropyBitsPerByte(segments[i])
                        Text(String(format: "%.2f b/B", entropy))
                            .font(.caption2)
                            .foregroundStyle(entropy > 7.9 ? .green : .orange)

                        Button {
                            UIPasteboard.general.string = segments[i]
                        } label: {
                            Image(systemName: "doc.on.doc")
                                .font(.caption)
                        }
                    }
                    .padding(.horizontal)
                }
            }
        }
    }
}
