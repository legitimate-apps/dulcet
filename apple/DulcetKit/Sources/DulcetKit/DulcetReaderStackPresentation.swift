/// SwiftUI's displayed path can reset while a split column changes its root. Keep that
/// acknowledgement separate from the store's requested path, then reapply an unpresented
/// request on the next UI turn. A request gets one replay; real Back never gets one.
struct DulcetReaderStackPresentation<Route: Hashable, Context: Hashable> {
    struct Request: Hashable {
        var context: Context
        var path: [Route]
    }
    private(set) var request: Request?
    private(set) var path: [Route] = []
    private(set) var pendingReplay: Request?
    private var replayed = false
    private var presented: Set<Route> = []

    mutating func request(_ updated: Request) {
        guard request != updated else { return }
        request = updated
        path = updated.path
        pendingReplay = nil
        replayed = false
        presented = []
    }

    mutating func appeared(_ route: Route) { presented.insert(route) }

    /// Returns true only for a pop from a page this presentation has displayed.
    mutating func wrote(_ updated: [Route]) -> Bool {
        path = updated
        guard let request, updated.count < request.path.count,
              Array(request.path.prefix(updated.count)) == updated else { return false }
        if request.path.last.map(presented.contains) == true { return true }
        if !replayed { pendingReplay = request }
        return false
    }

    mutating func replay(_ expected: Request) {
        guard request == expected, pendingReplay == expected else { return }
        path = expected.path
        pendingReplay = nil
        replayed = true
    }
}
