import WebKit
import XCTest
import shared

@MainActor
final class RealWebViewSmokeTests: XCTestCase {
    private var host: WebViewTestHost?
    private var latestSnapshot: RealWebViewSmokeSnapshot?

    override func tearDown() {
        if testRun?.failureCount ?? 0 > 0 {
            host?.attachFailureDiagnostics(to: self, snapshot: latestSnapshot)
        }
        host?.unmount()
        host = nil
        latestSnapshot = nil
        super.tearDown()
    }

    func testRealWKWebViewLoadsInlineHtmlAndEvaluatesJavaScript() throws {
        let host = WebViewTestHost()
        self.host = host
        host.mount { snapshot in
            self.latestSnapshot = snapshot
        }

        let completed = host.waitUntil(timeout: 15) {
            guard let snapshot = self.latestSnapshot else {
                return false
            }
            return snapshot.loadingState == "Finished"
                && snapshot.pageTitle == "CWM Real WebView Ready"
                && snapshot.completedLoadCount >= 1
                && self.normalizeProbe(snapshot.jsProbeResult) == "ready|42"
        }
        guard completed, let snapshot = latestSnapshot, let webView = host.findWebView() else {
            XCTFail("Timed out waiting for the real WKWebView smoke harness")
            return
        }

        XCTAssertEqual(snapshot.loadingState, "Finished")
        XCTAssertEqual(snapshot.pageTitle, "CWM Real WebView Ready")
        XCTAssertTrue(snapshot.errorsForCurrentRequest.isEmpty)
        XCTAssertEqual(normalizeProbe(snapshot.jsProbeResult), "ready|42")
        XCTAssertEqual(webView.title, "CWM Real WebView Ready")
        XCTAssertEqual(
            try host.evaluateJavaScript(
                "document.getElementById('smoke-status').textContent",
                in: webView
            ) as? String,
            "ready"
        )
        XCTAssertEqual(
            (try host.evaluateJavaScript("window.__cwmSmokeResult.arithmetic", in: webView) as? NSNumber)?.intValue,
            42
        )
    }

    func testRealWKWebViewLoadsCustomSchemeNavigationAndSubresources() throws {
        let host = WebViewTestHost()
        self.host = host
        var schemeSnapshot: SchemeWebViewSmokeSnapshot?
        host.mountScheme { snapshot in
            schemeSnapshot = snapshot
        }

        let completed = host.waitUntil(timeout: 20) {
            self.normalizeProbe(schemeSnapshot?.jsProbeResult) == "styled|script|image|frame|fetch|xhr|head-ok|0|404"
        }
        guard completed, let snapshot = schemeSnapshot else {
            XCTFail("Timed out waiting for the real WKWebView custom scheme harness: \(String(describing: schemeSnapshot))")
            return
        }

        XCTAssertEqual(snapshot.loadingState, "Finished")
        XCTAssertEqual(snapshot.pageTitle, "CWM Scheme Ready")
        XCTAssertEqual(normalizeProbe(snapshot.jsProbeResult), "styled|script|image|frame|fetch|xhr|head-ok|0|404")
        XCTAssertTrue(snapshot.errorsForCurrentRequest.isEmpty)
        XCTAssertTrue(snapshot.requestUrls.contains("cwmtest://host/index.html?source=smoke"))
        XCTAssertTrue(snapshot.requestUrls.contains { $0.hasSuffix("/fetch?source=js") })
        XCTAssertTrue(snapshot.requestMethods.contains("HEAD"))
        XCTAssertTrue(snapshot.completedStatuses.contains(404))
    }

    func testNavigatorLoadsInitialCustomSchemeNavigationAndSubresources() throws {
        let host = WebViewTestHost()
        self.host = host
        var schemeSnapshot: SchemeWebViewSmokeSnapshot?
        host.mountNavigatorScheme { snapshot in
            schemeSnapshot = snapshot
        }

        let completed = host.waitUntil(timeout: 20) {
            self.normalizeProbe(schemeSnapshot?.jsProbeResult) == "styled|script|image|frame|fetch|xhr|head-ok|0|404"
        }
        guard completed, let snapshot = schemeSnapshot else {
            XCTFail("Timed out waiting for navigator custom scheme load: \(String(describing: schemeSnapshot))")
            return
        }

        XCTAssertEqual(snapshot.loadingState, "Finished")
        XCTAssertEqual(snapshot.pageTitle, "CWM Scheme Ready")
        XCTAssertTrue(snapshot.requestUrls.contains("cwmtest://host/index.html?source=smoke"))
    }

    func testStoppingSchemeTaskCancelsCoordinatorWithoutCompletingWebKitLoad() {
        let host = WebViewTestHost()
        self.host = host
        var cancellationSnapshot: SchemeCancellationSnapshot?
        host.mountSchemeCancellation { snapshot in
            cancellationSnapshot = snapshot
        }

        guard host.waitUntil(timeout: 5, predicate: { cancellationSnapshot?.received == true }),
              let webView = host.findWebView() else {
            XCTFail("Timed out waiting for the custom scheme request to start")
            return
        }
        webView.stopLoading()

        XCTAssertTrue(
            host.waitUntil(timeout: 5) { cancellationSnapshot?.cancelledCompletions == 1 },
            "Expected exactly one Cancelled observer completion: \(String(describing: cancellationSnapshot))"
        )
        RunLoop.current.run(until: Date().addingTimeInterval(0.2))
        XCTAssertEqual(cancellationSnapshot?.cancelledCompletions, 1)
        XCTAssertTrue(webView.title?.isEmpty != false)
    }

    func testNavigationHandlerCancelsCurrentAndNewWindowNavigationsExactlyOnce() throws {
        let host = WebViewTestHost()
        self.host = host
        var navigationSnapshot: NavigationWebViewSmokeSnapshot?
        host.mountNavigation { snapshot in
            navigationSnapshot = snapshot
        }
        XCTAssertTrue(host.waitUntil(timeout: 10) { navigationSnapshot?.pageTitle == "CWM Navigation Source" })
        guard let webView = host.findWebView() else {
            XCTFail("Expected navigation WKWebView")
            return
        }
        _ = try host.evaluateJavaScript("document.getElementById('current-link').click()", in: webView)
        XCTAssertTrue(host.waitUntil(timeout: 10) { navigationSnapshot?.requests.count == 1 })
        _ = try host.evaluateJavaScript("document.getElementById('blank-link').click()", in: webView)
        XCTAssertTrue(host.waitUntil(timeout: 10) { navigationSnapshot?.requests.count == 2 })
        _ = try host.evaluateJavaScript("document.getElementById('popup-button').click()", in: webView)
        XCTAssertTrue(host.waitUntil(timeout: 10) { navigationSnapshot?.requests.count == 3 })
        XCTAssertEqual(navigationSnapshot?.currentMainFrameCount(url: "https://example.test/current"), 1)
        XCTAssertEqual(navigationSnapshot?.popupCount(url: "https://example.test/blank"), 1)
        XCTAssertEqual(navigationSnapshot?.popupCount(url: "https://example.test/popup"), 1)
    }

    func testBlankNavigationWithoutHandlerKeepsDefaultWKWebViewBehavior() throws {
        let host = WebViewTestHost()
        self.host = host
        host.mountNavigationWithoutHandler()
        guard host.waitUntil(timeout: 10, predicate: { host.findWebView()?.title == "CWM No Handler Source" }),
              let webView = host.findWebView() else {
            XCTFail("Expected WKWebView without a navigation handler")
            return
        }

        _ = try host.evaluateJavaScript("document.getElementById('blank-link').click()", in: webView)
        RunLoop.current.run(until: Date().addingTimeInterval(0.5))
        XCTAssertEqual(webView.title, "CWM No Handler Source")
    }

    private func normalizeProbe(_ value: String?) -> String? {
        value?.trimmingCharacters(in: CharacterSet(charactersIn: "\""))
    }
}
