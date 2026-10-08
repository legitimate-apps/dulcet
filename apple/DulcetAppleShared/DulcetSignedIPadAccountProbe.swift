#if DEBUG && DULCET_SIGNED_IPAD_PROOF
import DulcetKit
import Foundation
import Observation
import Security
import UIKit

/// Compiled only into the isolated physical-iPad proof host. Network and persistence
/// remain the production composition; the marker observes them without supplying an outcome.
@MainActor @Observable
final class DulcetSignedIPadAccountProbe {
    static let markerID = "dulcet.signed-ipad.proof"
    private static let ownerKey = "dulcet.signed-ipad.proof-owner"
    private static let itemKey = "dulcet.signed-ipad.proof-item"
    private static let pidKey = "dulcet.signed-ipad.proof-pid"
    var text = "signed-ipad=PENDING"
    private let phase: String
    private let nonce: String
    private let keychain = DulcetKeychainCredentialStore()

    init() {
        let args = ProcessInfo.processInfo.arguments
        func value(_ flag: String) -> String? {
            guard let i = args.firstIndex(of: flag), args.indices.contains(i + 1) else { return nil }
            return args[i + 1]
        }
        guard Bundle.main.bundleIdentifier == "com.legitimateapps.dulcet.signed-ipad",
              UIDevice.current.userInterfaceIdiom == .pad,
              let phase = value("-dulcet-signed-ipad-phase"),
              ["connect", "read", "missing", "cleanup"].contains(phase),
              let nonce = value("-dulcet-signed-ipad-nonce"), UUID(uuidString: nonce) != nil else {
            fatalError("Signed iPad proof requires its isolated host and phase")
        }
#if targetEnvironment(simulator)
        fatalError("Signed iPad proof requires physical hardware")
#endif
        self.phase = phase
        self.nonce = nonce
        let defaults = UserDefaults.standard
        if phase == "connect" {
            guard keychain.activeAccountID == nil, defaults.string(forKey: Self.ownerKey) == nil,
                  let url = value("-dulcet-debug-account-server-url"), Self.validFixture(url),
                  value("-dulcet-debug-account-username") == "dulcet-admin",
                  value("-dulcet-debug-account-password") == "dulcet-ci-canary-password",
                  args.contains("-dulcet-debug-connect-account") else {
                fatalError("Signed iPad proof refuses inherited state or an invalid fixture")
            }
            defaults.set(nonce, forKey: Self.ownerKey)
            defaults.set(ProcessInfo.processInfo.processIdentifier, forKey: Self.pidKey)
            do { try wrongValueControl() } catch { fatalError("Keychain wrong-value control failed") }
        } else {
            guard defaults.string(forKey: Self.ownerKey) == nonce,
                  !args.contains("-dulcet-debug-connect-account"),
                  defaults.integer(forKey: Self.pidKey) != Int(ProcessInfo.processInfo.processIdentifier) else {
                fatalError("Signed iPad relaunch requires a fresh process without an account hook")
            }
            defaults.set(ProcessInfo.processInfo.processIdentifier, forKey: Self.pidKey)
            if phase == "missing" {
                guard let item = defaults.string(forKey: Self.itemKey), keychain.activeAccountID == item,
                      SecItemDelete(query(service: DulcetKeychainCredentialStore.productionService,
                                          account: item) as CFDictionary) == errSecSuccess else {
                    fatalError("Missing-item control could not delete the owned credential")
                }
                // Retain the actual production pointer. The ensuing production load must fail.
            } else if phase == "cleanup" {
                let control = query(service: "com.legitimateapps.dulcet.signed-ipad.control.\(nonce)",
                                    account: "attribute-control")
                let controlStatus = SecItemDelete(control as CFDictionary)
                guard (controlStatus == errSecSuccess || controlStatus == errSecItemNotFound),
                      SecItemCopyMatching(control as CFDictionary, nil) == errSecItemNotFound else {
                    fatalError("Signed iPad attribute-control cleanup failed")
                }
                // A failed UI wait may end before the observer records a successful save.
                // This nonce began with an empty isolated host namespace.
                if defaults.string(forKey: Self.itemKey) == nil, let item = keychain.activeAccountID {
                    defaults.set(item, forKey: Self.itemKey)
                }
                guard keychain.activeAccountID == nil
                        || keychain.activeAccountID == defaults.string(forKey: Self.itemKey) else {
                    fatalError("Signed iPad cleanup refuses an unowned pointer")
                }
                do { try keychain.delete() } catch { fatalError("Signed iPad Keychain cleanup failed") }
            }
        }
    }

    func observe(_ presentation: DulcetPresentationStore) {
        do {
            if phase == "read" {
                guard case .saved = presentation.snapshot.accountConnection else { throw ProbeError.failed }
                try verifyCredential(presentation)
                text = "signed-ipad=PASS saved=PASS keychain=PASS fresh-process=PASS"
            } else if phase == "missing" {
                guard presentation.snapshot.state == .accountErrorPersistence,
                      let item = UserDefaults.standard.string(forKey: Self.itemKey),
                      keychain.activeAccountID == item, missing(item) else { throw ProbeError.failed }
                text = "signed-ipad=PASS missing-item=PASS"
            } else if phase == "cleanup" {
                guard keychain.activeAccountID == nil, try keychain.load() == nil,
                      UserDefaults.standard.string(forKey: Self.itemKey).map(missing) ?? true else {
                    throw ProbeError.failed
                }
                let folder = try FileManager.default.url(for: .applicationSupportDirectory,
                    in: .userDomainMask, appropriateFor: nil, create: true)
                let receipt = try JSONSerialization.data(withJSONObject: ["nonce": nonce, "cleanup": "PASS"])
                try receipt.write(to: folder.appendingPathComponent("signed-ipad-cleanup.json"), options: .atomic)
                [Self.ownerKey, Self.itemKey, Self.pidKey].forEach { UserDefaults.standard.removeObject(forKey: $0) }
                text = "signed-ipad=PASS cleanup=PASS"
            }
        } catch { text = "signed-ipad=FAIL"; return }
        if phase == "connect" || phase == "read" {
            Task { [weak self] in
                let deadline = ContinuousClock.now.advanced(by: .seconds(60))
                while ContinuousClock.now < deadline {
                    guard let self else { return }
                    if presentation.snapshot.accountConnected {
                        do {
                            try self.verifyCredential(presentation)
                            self.text = "signed-ipad=PASS connected=PASS keychain=PASS attributes=PASS"
                        } catch { self.text = "signed-ipad=FAIL" }
                        return
                    }
                    try? await Task.sleep(for: .milliseconds(100))
                }
                if self?.phase == "connect" { self?.text = "signed-ipad=FAIL" }
            }
        }
    }

    private func verifyCredential(_ presentation: DulcetPresentationStore) throws {
        guard let item = keychain.activeAccountID, UUID(uuidString: item) != nil,
              let request = try keychain.load(), Self.validFixture(request.serverURL),
              request.serverURL == presentation.accountServerURL,
              request.username == "dulcet-admin", request.password == "dulcet-ci-canary-password",
              request.allowLocalHTTP else { throw ProbeError.failed }
        if phase == "connect" { UserDefaults.standard.set(item, forKey: Self.itemKey) }
        guard UserDefaults.standard.string(forKey: Self.itemKey) == item else { throw ProbeError.failed }
        let saved = try attributes(service: DulcetKeychainCredentialStore.productionService, account: item)
        guard saved[kSecAttrAccessible as String] as? String == kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly as String,
              (saved[kSecAttrSynchronizable as String] as? NSNumber)?.boolValue == false else { throw ProbeError.failed }
    }

    private func wrongValueControl() throws {
        let service = "com.legitimateapps.dulcet.signed-ipad.control.\(nonce)"
        let account = "attribute-control"
        var wrong = query(service: service, account: account)
        defer { SecItemDelete(query(service: service, account: account) as CFDictionary) }
        wrong[kSecAttrSynchronizable as String] = kCFBooleanFalse
        wrong[kSecAttrAccessible as String] = kSecAttrAccessibleWhenUnlockedThisDeviceOnly
        wrong[kSecValueData as String] = Data("wrong-value-control".utf8)
        guard SecItemAdd(wrong as CFDictionary, nil) == errSecSuccess else { throw ProbeError.failed }
        let read = try attributes(service: service, account: account)
        guard read[kSecAttrAccessible as String] as? String == kSecAttrAccessibleWhenUnlockedThisDeviceOnly as String,
              read[kSecAttrAccessible as String] as? String != kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly as String,
              (read[kSecAttrSynchronizable as String] as? NSNumber)?.boolValue == false else { throw ProbeError.failed }
    }
    private func query(service: String, account: String) -> [String: Any] {
        [kSecClass as String: kSecClassGenericPassword, kSecAttrService as String: service,
         kSecAttrAccount as String: account, kSecUseDataProtectionKeychain as String: kCFBooleanTrue as Any,
         kSecAttrSynchronizable as String: kSecAttrSynchronizableAny]
    }
    private func attributes(service: String, account: String) throws -> [String: Any] {
        var q = query(service: service, account: account)
        q[kSecReturnAttributes as String] = kCFBooleanTrue
        q[kSecMatchLimit as String] = kSecMatchLimitOne
        // No expected-accessibility filter; either synchronizable value may be returned.
        var result: CFTypeRef?
        guard SecItemCopyMatching(q as CFDictionary, &result) == errSecSuccess,
              let attributes = result as? [String: Any] else { throw ProbeError.failed }
        return attributes
    }
    private func missing(_ item: String) -> Bool {
        SecItemCopyMatching(query(service: DulcetKeychainCredentialStore.productionService,
                                 account: item) as CFDictionary, nil) == errSecItemNotFound
    }
    static func validFixture(_ value: String) -> Bool {
        guard let url = URLComponents(string: value), url.scheme == "http", url.user == nil,
              url.password == nil, url.query == nil, url.fragment == nil,
              url.path.isEmpty || url.path == "/", (15781...15789).contains(url.port ?? 0),
              let host = url.host else { return false }
        let pieces = host.split(separator: ".").compactMap { Int($0) }
        guard pieces.count == 4, pieces.allSatisfy({ (0...255).contains($0) }) else { return false }
        return pieces[0] == 10 || (pieces[0] == 172 && (16...31).contains(pieces[1]))
            || (pieces[0] == 192 && pieces[1] == 168)
    }
    private enum ProbeError: Error { case failed }
}
#endif
