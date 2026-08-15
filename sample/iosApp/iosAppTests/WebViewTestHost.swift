import UIKit
import WebKit
import XCTest
import shared

@MainActor
final class WebViewTestHost {
    private(set) var window: UIWindow?
    private(set) var controller: UIViewController?

    func mount(onSnapshot: @escaping (RealWebViewSmokeSnapshot) -> Void) {
        let controller = RealWebViewSmokeHarness_iosKt.realWebViewSmokeViewController(
            onSnapshot: onSnapshot
        )
        let window = UIWindow(frame: UIScreen.main.bounds)
        window.rootViewController = controller
        window.makeKeyAndVisible()
        controller.view.setNeedsLayout()
        controller.view.layoutIfNeeded()

        self.controller = controller
        self.window = window
    }

    func findWebView() -> WKWebView? {
        guard let rootView = controller?.view else {
            return nil
        }
        return findWebView(in: rootView)
    }

    func waitUntil(
        timeout: TimeInterval,
        predicate: () -> Bool
    ) -> Bool {
        let deadline = Date().addingTimeInterval(timeout)
        while !predicate() && Date() < deadline {
            let nextCheck = min(Date().addingTimeInterval(0.05), deadline)
            RunLoop.current.run(mode: .default, before: nextCheck)
        }
        return predicate()
    }

    func evaluateJavaScript(
        _ script: String,
        in webView: WKWebView,
        timeout: TimeInterval = 5
    ) throws -> Any? {
        let expectation = XCTestExpectation(description: "Evaluate JavaScript: \(script)")
        var result: Any?
        var evaluationError: Error?
        webView.evaluateJavaScript(script) { value, error in
            result = value
            evaluationError = error
            expectation.fulfill()
        }

        guard XCTWaiter.wait(for: [expectation], timeout: timeout) == .completed else {
            throw WebViewTestHostError.javaScriptTimedOut(script)
        }
        if let evaluationError {
            throw evaluationError
        }
        return result
    }

    func attachFailureDiagnostics(
        to testCase: XCTestCase,
        snapshot: RealWebViewSmokeSnapshot?
    ) {
        let webView = findWebView()
        let html = try? webView.flatMap {
            try evaluateJavaScript("document.documentElement.outerHTML", in: $0) as? String
        }
        let nativeProbe = try? webView.flatMap {
            try evaluateJavaScript(
                "window.__cwmSmokeResult ? window.__cwmSmokeResult.dom + '|' + window.__cwmSmokeResult.arithmetic : null",
                in: $0
            ) as? String
        }
        let diagnostics = """
        loadingState=\(snapshot?.loadingState ?? "nil")
        lastLoadedUrl=\(snapshot?.lastLoadedUrl ?? "nil")
        pageTitle=\(snapshot?.pageTitle ?? "nil")
        errorsForCurrentRequest=\(snapshot?.errorsForCurrentRequest ?? [])
        harnessJsProbe=\(snapshot?.jsProbeResult ?? "nil")
        nativeTitle=\(webView?.title ?? "nil")
        nativeUrl=\(webView?.url?.absoluteString ?? "nil")
        nativeProbe=\(nativeProbe ?? "nil")
        html=\(html ?? "nil")
        """
        let textAttachment = XCTAttachment(
            data: Data(diagnostics.utf8),
            uniformTypeIdentifier: "public.plain-text"
        )
        textAttachment.name = "Real WKWebView diagnostics"
        textAttachment.lifetime = .keepAlways
        testCase.add(textAttachment)

        if let window {
            let renderer = UIGraphicsImageRenderer(bounds: window.bounds)
            let image = renderer.image { _ in
                window.drawHierarchy(in: window.bounds, afterScreenUpdates: true)
            }
            let screenshot = XCTAttachment(image: image)
            screenshot.name = "Real WKWebView screenshot"
            screenshot.lifetime = .keepAlways
            testCase.add(screenshot)
        }
    }

    func unmount() {
        controller?.view.removeFromSuperview()
        window?.rootViewController = nil
        window?.isHidden = true
        controller = nil
        window = nil
    }

    private func findWebView(in view: UIView) -> WKWebView? {
        if let webView = view as? WKWebView {
            return webView
        }
        for subview in view.subviews {
            if let webView = findWebView(in: subview) {
                return webView
            }
        }
        return nil
    }
}

private enum WebViewTestHostError: Error {
    case javaScriptTimedOut(String)
}
