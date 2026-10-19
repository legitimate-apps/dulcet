import Testing
@testable import DulcetKit

@MainActor
struct ListenNowTests {
    @Test func homeIsTheFirstDestinationAndQueriesUseTheCoreShelfSeam() {
        #expect(DulcetSidebarDestination.allCases.first == .listenNow)
        #expect(DulcetListenNowShelf.allCases.count == 10)
        for shelf in DulcetListenNowShelf.allCases {
            let request = DulcetLibraryQuery.listenNow(shelf).request
            #expect(request.kind == "listenNow")
            #expect(request.listType == shelf.rawValue)
        }
    }
}
