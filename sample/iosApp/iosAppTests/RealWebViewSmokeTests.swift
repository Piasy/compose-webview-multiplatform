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

    private func normalizeProbe(_ value: String?) -> String? {
        value?.trimmingCharacters(in: CharacterSet(charactersIn: "\""))
    }
}
