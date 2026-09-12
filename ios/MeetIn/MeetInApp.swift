import SwiftUI
import WebKit
import AVFoundation
import UserNotifications

private let meetInURL = URL(string: "https://meetinapp-bj2ib4p7.manus.space")!

@main
struct MeetInApp: App {
    var body: some Scene {
        WindowGroup {
            MeetInWebView()
                .ignoresSafeArea()
        }
    }
}

struct MeetInWebView: UIViewRepresentable {
    func makeCoordinator() -> Coordinator { Coordinator() }

    func makeUIView(context: Context) -> WKWebView {
        let contentController = WKUserContentController()
        contentController.add(context.coordinator, name: "meetin")
        let bridge = """
        (() => {
          const call = (method, args) => window.webkit.messageHandlers.meetin.postMessage({method, args});
          window.Android = {
            showNotification: (title, message) => call('showNotification', [title, message]),
            startRecording: () => call('startRecording', []),
            stopRecording: () => call('stopRecording', []),
            canRecordOgg: () => false,
            canRecordNative: () => true,
            downloadFile: (url, filename) => call('downloadFile', [url, filename]),
            toast: (message) => call('toast', [message])
          };
        })();
        """
        contentController.addUserScript(WKUserScript(source: bridge, injectionTime: .atDocumentStart, forMainFrameOnly: true))

        let configuration = WKWebViewConfiguration()
        configuration.userContentController = contentController
        configuration.allowsInlineMediaPlayback = true
        configuration.mediaTypesRequiringUserActionForPlayback = []

        let webView = WKWebView(frame: .zero, configuration: configuration)
        webView.navigationDelegate = context.coordinator
        webView.uiDelegate = context.coordinator
        webView.allowsBackForwardNavigationGestures = true
        webView.load(URLRequest(url: meetInURL))
        context.coordinator.webView = webView
        return webView
    }

    func updateUIView(_ webView: WKWebView, context: Context) {}

    final class Coordinator: NSObject, WKNavigationDelegate, WKUIDelegate, WKScriptMessageHandler {
        weak var webView: WKWebView?
        private var recorder: AVAudioRecorder?
        private var recordingURL: URL?

        func userContentController(_ userContentController: WKUserContentController, didReceive message: WKScriptMessage) {
            guard let payload = message.body as? [String: Any], let method = payload["method"] as? String,
                  let args = payload["args"] as? [Any] else { return }
            switch method {
            case "showNotification":
                requestNotification(title: args[safe: 0] as? String ?? "MeetIn", body: args[safe: 1] as? String ?? "")
            case "startRecording": startRecording()
            case "stopRecording": stopRecording()
            case "downloadFile":
                guard let urlString = args[safe: 0] as? String, let url = URL(string: urlString) else { return }
                download(url: url, filename: args[safe: 1] as? String)
            case "toast": showToast(args[safe: 0] as? String ?? "")
            default: break
            }
        }

        func webView(_ webView: WKWebView, decidePolicyFor navigationAction: WKNavigationAction,
                     decisionHandler: @escaping (WKNavigationActionPolicy) -> Void) {
            decisionHandler(.allow)
        }

        private func startRecording() {
            AVAudioSession.sharedInstance().requestRecordPermission { [weak self] granted in
                DispatchQueue.main.async {
                    guard granted else { self?.showToast("Microphone access is required"); return }
                    do {
                        let session = AVAudioSession.sharedInstance()
                        try session.setCategory(.record, mode: .default, options: [.allowBluetooth])
                        try session.setActive(true)
                        let url = FileManager.default.temporaryDirectory.appendingPathComponent("voice-note-\(Int(Date().timeIntervalSince1970)).m4a")
                        self?.recordingURL = url
                        self?.recorder = try AVAudioRecorder(url: url, settings: [AVFormatIDKey: kAudioFormatMPEG4AAC, AVSampleRateKey: 44100, AVNumberOfChannelsKey: 1])
                        self?.recorder?.record()
                        self?.showToast("Recording started")
                    } catch { self?.showToast("Recording failed") }
                }
            }
        }

        private func stopRecording() {
            guard let recorder, recorder.isRecording, let url = recordingURL else { showToast("Not recording"); return }
            recorder.stop(); self.recorder = nil
            guard let data = try? Data(contentsOf: url), data.count > 512 else { showToast("The recording was empty"); return }
            let dataURL = "data:audio/mp4;base64,\(data.base64EncodedString())"
            let name = url.lastPathComponent.replacingOccurrences(of: ".m4a", with: "") + ".m4a"
            evaluate("if(typeof onNativeVoiceNote === 'function'){onNativeVoiceNote(\(json(dataURL)),\(json(name)),\"audio/mp4\");}")
            showToast("Voice note ready")
        }

        private func requestNotification(title: String, body: String) {
            UNUserNotificationCenter.current().requestAuthorization(options: [.alert, .sound, .badge]) { granted, _ in
                guard granted else { return }
                let content = UNMutableNotificationContent(); content.title = title; content.body = body; content.sound = .default
                UNUserNotificationCenter.current().add(UNNotificationRequest(identifier: UUID().uuidString, content: content, trigger: nil))
            }
        }

        private func download(url: URL, filename: String?) {
            URLSession.shared.downloadTask(with: url) { [weak self] temporaryURL, _, error in
                guard let temporaryURL, error == nil else { DispatchQueue.main.async { self?.showToast("Download failed") }; return }
                let safeName = (filename?.isEmpty == false ? filename! : url.lastPathComponent).replacingOccurrences(of: "/", with: "_")
                let destination = FileManager.default.temporaryDirectory.appendingPathComponent(safeName)
                try? FileManager.default.removeItem(at: destination)
                do { try FileManager.default.moveItem(at: temporaryURL, to: destination) } catch { DispatchQueue.main.async { self?.showToast("Download failed") }; return }
                DispatchQueue.main.async { self?.topViewController()?.present(UIActivityViewController(activityItems: [destination], applicationActivities: nil), animated: true) }
            }.resume()
        }

        private func evaluate(_ script: String) { webView?.evaluateJavaScript(script, completionHandler: nil) }
        private func json(_ value: String) -> String { (try? String(data: JSONSerialization.data(withJSONObject: [value]), encoding: .utf8))?.dropFirst().dropLast().description ?? "\"\"" }
        private func showToast(_ message: String) { let alert = UIAlertController(title: nil, message: message, preferredStyle: .alert); topViewController()?.present(alert, animated: true); DispatchQueue.main.asyncAfter(deadline: .now() + 1.4) { alert.dismiss(animated: true) } }
        private func topViewController() -> UIViewController? { var controller = UIApplication.shared.connectedScenes.compactMap { ($0 as? UIWindowScene)?.keyWindow?.rootViewController }.first; while let presented = controller?.presentedViewController { controller = presented }; return controller }
    }
}

private extension Array { subscript(safe index: Index) -> Element? { indices.contains(index) ? self[index] : nil } }
private extension UIWindowScene { var keyWindow: UIWindow? { windows.first(where: \.isKeyWindow) } }
