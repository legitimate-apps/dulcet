import XCTest

final class DulcetiOSUITests: XCTestCase {
    /// Proves the iPad renders the regular-width split: sidebar and detail visible at once, in
    /// separate columns. An iPhone cannot satisfy this, which is the point -- a test that passed on
    /// both would let the iPadOS cell claim evidence it does not have.
    @MainActor
    func testAccountConnectUsesRegularWidthSplitLayout() {
        let app = XCUIApplication()
        app.launchArguments.append("-dulcet-account-connect-layout-fixture")
        app.launch()

        // Guard on window width, not XCUIApplication.horizontalSizeClass: that attribute reports
        // .unspecified for the application element (OBSERVED on iPad Pro 13-inch, rawValue 0), so
        // asserting .regular against it fails on the very device it is meant to accept. A regular
        // split needs a window far wider than any iPhone.
        let window = app.windows.firstMatch
        XCTAssertTrue(window.waitForExistence(timeout: 10), "The app window must exist")
        XCTAssertGreaterThan(
            window.frame.width,
            700,
            "This proof requires a regular-width window; an iPhone destination is invalid evidence"
        )

        // staticTexts, not descendants(matching: .any): the row's identifier is carried by BOTH its
        // SF Symbol image and its label (OBSERVED in the captured hierarchy), so an .any query is
        // ambiguous and resolves against the decorative image, whose isHittable is false.
        let sidebar = app.staticTexts["dulcet.sidebar.library"].firstMatch
        let detail = app.staticTexts["dulcet.account-connect.title"].firstMatch

        XCTAssertTrue(sidebar.waitForExistence(timeout: 10), "The Library row must be in the sidebar")
        XCTAssertTrue(detail.waitForExistence(timeout: 10), "The account-connect heading must be in the detail column")
        XCTAssertTrue(sidebar.isHittable, "The sidebar must be visible, not retained offscreen")
        XCTAssertTrue(detail.isHittable, "The detail must be visible at the same time as the sidebar")
        XCTAssertLessThan(
            sidebar.frame.maxX,
            detail.frame.minX,
            "Regular-width layout must place the sidebar and detail in separate visible columns"
        )
    }
}
