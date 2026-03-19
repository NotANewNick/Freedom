// ═════════════════════════════════════════════════════════════════════════════
//  DdnsServiceType — Enum for 10 DDNS providers with field labels
// ═════════════════════════════════════════════════════════════════════════════

import Foundation

enum DdnsServiceType: String, Codable, CaseIterable, Identifiable {
    case noip       = "NOIP"
    case duckdns    = "DUCKDNS"
    case dynu       = "DYNU"
    case cloudflare = "CLOUDFLARE"
    case namecheap  = "NAMECHEAP"
    case desec      = "DESEC"
    case dynv6      = "DYNV6"
    case ydns       = "YDNS"
    case ipv64      = "IPV64"
    case freedns    = "FREEDNS"

    var id: String { rawValue }

    var displayName: String {
        switch self {
        case .noip:       return "No-IP"
        case .duckdns:    return "DuckDNS"
        case .dynu:       return "Dynu"
        case .cloudflare: return "Cloudflare"
        case .namecheap:  return "Namecheap"
        case .desec:      return "deSEC"
        case .dynv6:      return "dynv6"
        case .ydns:       return "YDNS"
        case .ipv64:      return "IPv64"
        case .freedns:    return "FreeDNS"
        }
    }

    var field1Label: String {
        switch self {
        case .noip:       return "Hostname"
        case .duckdns:    return "Subdomain"
        case .dynu:       return "Hostname"
        case .cloudflare: return "API Token"
        case .namecheap:  return "Host"
        case .desec:      return "Hostname"
        case .dynv6:      return "Hostname"
        case .ydns:       return "Hostname"
        case .ipv64:      return "Hostname"
        case .freedns:    return "Hostname"
        }
    }

    var field2Label: String {
        switch self {
        case .noip:       return "Username"
        case .duckdns:    return "Token"
        case .dynu:       return "Username"
        case .cloudflare: return "Zone ID"
        case .namecheap:  return "Domain"
        case .desec:      return "Email"
        case .dynv6:      return "Token"
        case .ydns:       return "Email"
        case .ipv64:      return "API Key"
        case .freedns:    return "Update Token"
        }
    }

    var field3Label: String? {
        switch self {
        case .noip:       return "Password"
        case .dynu:       return "Password"
        case .cloudflare: return "Record ID"
        case .namecheap:  return "DDNS Password"
        case .desec:      return "Password"
        case .ydns:       return "Password"
        default:          return nil
        }
    }

    /// Whether this provider supports automated registration via API.
    var isAutomatable: Bool {
        switch self {
        case .duckdns, .desec, .dynv6, .ydns, .ipv64, .dynu: return true
        default: return false
        }
    }
}
