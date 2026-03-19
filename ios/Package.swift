// swift-tools-version: 6.0
// Package.swift — Freedom iOS dependencies
//
// NOTE: This Package.swift is for reference / SPM dependency resolution.
// The actual iOS app must be built as an Xcode project because:
//   1. It requires a Network Extension target (separate binary)
//   2. It needs entitlements for VPN, Keychain, and Network Extension
//   3. It uses UIKit bridging (AVFoundation camera) via UIViewControllerRepresentable
//
// To set up the Xcode project:
//   1. Open Xcode → File → New → Project → iOS App
//   2. Set Product Name: "Freedom", Bundle ID: "com.freedom.app"
//   3. Add all .swift files from ios/Freedom/ to the main target
//   4. File → Add Package Dependencies → add GRDB.swift
//   5. Add a "Network Extension" target for OpenVPNTunnelProvider
//   6. Configure signing & entitlements
//
// SPM Dependencies:
//   - GRDB.swift 7.0+ (https://github.com/groue/GRDB.swift)
//   - OpenVPNAdapter 0.8+ (https://github.com/ss-abramchuk/OpenVPNAdapter) [tunnel target only]
//

import PackageDescription

let package = Package(
    name: "Freedom",
    platforms: [
        .iOS(.v16)
    ],
    dependencies: [
        // Database (SQLite ORM — closest to Android Room)
        .package(url: "https://github.com/groue/GRDB.swift", from: "7.0.0"),

        // VPN (for Network Extension target)
        // .package(url: "https://github.com/ss-abramchuk/OpenVPNAdapter", from: "0.8.0"),
    ],
    targets: [
        .executableTarget(
            name: "Freedom",
            dependencies: [
                .product(name: "GRDB", package: "GRDB.swift"),
            ],
            path: "Freedom"
        ),
    ]
)
