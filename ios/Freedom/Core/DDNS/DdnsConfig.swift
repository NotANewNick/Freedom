// ═════════════════════════════════════════════════════════════════════════════
//  DdnsConfig — DDNS provider configuration
// ═════════════════════════════════════════════════════════════════════════════

import Foundation

struct DdnsConfig: Codable, Identifiable, Equatable {
    let id: String
    let serviceType: DdnsServiceType
    var field1: String  // varies by provider
    var field2: String  // varies by provider
    var field3: String  // varies by provider (empty if not needed)

    init(id: String = UUID().uuidString, serviceType: DdnsServiceType,
         field1: String, field2: String, field3: String = "") {
        self.id = id
        self.serviceType = serviceType
        self.field1 = field1
        self.field2 = field2
        self.field3 = field3
    }

    /// Returns the FQDN for use in QR configs.
    var publicHostname: String {
        switch serviceType {
        case .duckdns:    return "\(field1).duckdns.org"
        case .desec:      return field1.contains(".") ? field1 : "\(field1).dedyn.io"
        case .dynv6:      return field1.contains(".") ? field1 : "\(field1).dynv6.net"
        case .ydns:       return field1.contains(".") ? field1 : "\(field1).ydns.eu"
        case .ipv64:      return field1
        case .freedns:    return field1
        case .noip:       return field1
        case .dynu:       return field1
        case .cloudflare: return field1
        case .namecheap:  return "\(field1).\(field2)"
        }
    }
}
