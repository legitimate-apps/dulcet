import Foundation
import Testing
@testable import DulcetKit

@MainActor
private final class ConnectionAccessProbe: DulcetLocalNetworkAccessProbing {
    final class Watch: DulcetLocalNetworkAccessWatch {
        var cancellations = 0
        func cancel() { cancellations += 1 }
    }
    var urls: [String] = []
    var callbacks: [@MainActor (DulcetLocalNetworkAccess) -> Void] = []
    var watches: [Watch] = []
    func watch(serverURL: String, onChange: @escaping @MainActor (DulcetLocalNetworkAccess) -> Void) -> any DulcetLocalNetworkAccessWatch {
        urls.append(serverURL); callbacks.append(onChange)
        let watch = Watch(); watches.append(watch); return watch
    }
}

private func accessState(owner: String = "fixture", address: String = "https://home.example.test/music",
    check: String? = "Unavailable", time: Date? = Date(timeIntervalSince1970: 1), removed: Bool = false) -> DulcetConnectionsState {
    DulcetConnectionsState(ownership: .init(accountID: owner, anchorURL: "http://legacy.example.test/music", username: "listener", serverIdentity: "lineage"),
        routes: [.init(id: "home", address: address, label: "Home", verifiedAtEnrollment: true, removed: removed, selected: false,
            checkResult: check, checkedAt: time)], enrolled: true, canUpgrade: true)
}

@MainActor @Suite struct ConnectionAccessPresentationTests {
@Test func privacyDenialRequiresAnActualAdapterAnswerAndGrantClearsOnlyTheDecoration() {
    let probe = ConnectionAccessProbe(); let presentation = DulcetConnectionAccessPresentation(probe: probe)
    let state = accessState(); var changes = 0
    let first = presentation.refresh(state) { changes += 1 }
    #expect(first.routes[0].statusText == DulcetStrings.connectionUnavailable)
    #expect(probe.urls == [state.routes[0].address])
    probe.callbacks[0](.denied)
    let denied = presentation.refresh(state) { changes += 1 }
    #expect(denied.routes[0].statusText == DulcetStrings.connectionLocalNetworkBlocked)
    #expect(denied.ownership == state.ownership)
    #expect(denied.routes[0].address == state.routes[0].address)
    #expect(probe.urls.count == 1, "A redraw cannot create another probe")
    probe.callbacks[0](.notDenied)
    #expect(presentation.refresh(state) {}.routes[0].statusText == DulcetStrings.connectionUnavailable)
    #expect(changes == 2)
    presentation.cancel()
    #expect(probe.watches[0].cancellations == 1)
}

@Test func permissionCallbacksCannotCrossOwnerAddressOrActualCheckReplacement() {
    let probe = ConnectionAccessProbe(); let presentation = DulcetConnectionAccessPresentation(probe: probe)
    var changes = 0
    var state = accessState()
    _ = presentation.refresh(state) { changes += 1 }
    let replacements = [accessState(owner: "replacement"),
        accessState(owner: "replacement", address: "https://new.example.test/music"),
        accessState(owner: "replacement", address: "https://new.example.test/music", time: Date(timeIntervalSince1970: 2))]
    for next in replacements {
        let old = probe.callbacks.count - 1
        state = next
        _ = presentation.refresh(state) { changes += 1 }
        probe.callbacks[old](.denied)
        #expect(changes == 0)
        #expect(!presentation.refresh(state) {}.routes[0].localNetworkAccessDenied)
        #expect(probe.watches[old].cancellations == 1)
    }
    probe.callbacks.last?(.denied)
    #expect(presentation.refresh(state) {}.routes[0].localNetworkAccessDenied)
    presentation.cancel()
    let before = changes
    probe.callbacks.last?(.notDenied)
    #expect(changes == before, "A cancelled surface cannot publish a late permission result")
}

@Test func healthyForeignRemovedAndUnobservedRoutesDoNotStartPermissionProbes() {
    let probe = ConnectionAccessProbe(); let presentation = DulcetConnectionAccessPresentation(probe: probe)
    for state in [accessState(check: "Ready"), accessState(check: "DifferentServer"), accessState(time: nil), accessState(removed: true)] {
        _ = presentation.refresh(state) {}
    }
    #expect(probe.urls.isEmpty)
    let foreign = DulcetConnectionRow(id: "home", address: "https://foreign.example.test", label: nil, verifiedAtEnrollment: true,
        removed: false, selected: false, checkResult: "DifferentServer", localNetworkAccessDenied: true)
    #expect(foreign.statusText == DulcetStrings.connectionDifferentServer)
}

}
