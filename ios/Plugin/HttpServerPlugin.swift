import Foundation
import Capacitor
import GCDWebServer
import UIKit
import Darwin

@objc(HttpServerPlugin)
public class HttpServerPlugin: CAPPlugin {

    private let lock = NSLock()
    private var webServer: GCDWebServer?
    private var currentPort: Int = 0
    private var maxBodyBytes: Int = 50 * 1024 * 1024
    private var fileBodyThresholdBytes: Int = 1 * 1024 * 1024
    private let bridge = HttpRequestBridge()
    private var backgroundTask: UIBackgroundTaskIdentifier = .invalid

    private static let requestTimeout: TimeInterval = 60

    // MARK: - Lifecycle

    @objc public override func load() {
        NotificationCenter.default.addObserver(
            self,
            selector: #selector(appWillResignActive),
            name: UIApplication.willResignActiveNotification,
            object: nil
        )
        NotificationCenter.default.addObserver(
            self,
            selector: #selector(appDidEnterBackground),
            name: UIApplication.didEnterBackgroundNotification,
            object: nil
        )
    }

    deinit {
        NotificationCenter.default.removeObserver(self)
    }

    // MARK: - Plugin methods

    @objc func start(_ call: CAPPluginCall) {
        lock.lock()
        if webServer != nil {
            let port = currentPort
            let ip = localIPv4()
            lock.unlock()
            call.resolve([
                "port": port,
                "url": "http://\(ip):\(port)",
                "localIp": ip
            ])
            return
        }
        lock.unlock()

        let requestedPort = call.getInt("port") ?? 0
        if let v = call.getInt("maxBodyBytes"), v > 0 {
            self.maxBodyBytes = v
        } else if let v = call.getDouble("maxBodyBytes"), v > 0 {
            self.maxBodyBytes = Int(v)
        }
        if let v = call.getInt("fileBodyThresholdBytes"), v > 0 {
            self.fileBodyThresholdBytes = v
        } else if let v = call.getDouble("fileBodyThresholdBytes"), v > 0 {
            self.fileBodyThresholdBytes = Int(v)
        }

        do {
            let port: UInt16
            if requestedPort > 0 {
                port = UInt16(requestedPort)
            } else {
                port = try pickFreePort()
            }

            let server = GCDWebServer()
            installHandler(on: server)

            let options: [String: Any] = [
                GCDWebServerOption_Port: NSNumber(value: port),
                GCDWebServerOption_BindToLocalhost: false,
                GCDWebServerOption_AutomaticallySuspendInBackground: false
            ]
            try server.start(options: options)

            lock.lock()
            self.webServer = server
            self.currentPort = Int(port)
            lock.unlock()

            let ip = localIPv4()
            call.resolve([
                "port": Int(port),
                "url": "http://\(ip):\(port)",
                "localIp": ip
            ])
        } catch {
            call.reject("Failed to start server: \(error.localizedDescription)", nil, error)
        }
    }

    @objc func stop(_ call: CAPPluginCall) {
        lock.lock()
        let server = webServer
        webServer = nil
        currentPort = 0
        lock.unlock()

        server?.stop()
        server?.removeAllHandlers()

        // Drain pending requests with 503 so any still-blocked handler finishes.
        let draining = GCDWebServerDataResponse(text: "Server stopping")
        draining?.statusCode = 503
        if let resp = draining {
            bridge.drainAll(with: resp)
        }

        cleanupTempDir()
        endBackgroundTask()
        call.resolve()
    }

    @objc func respond(_ call: CAPPluginCall) {
        guard let requestId = call.getString("requestId"), !requestId.isEmpty else {
            call.reject("requestId is required")
            return
        }
        let status = call.getInt("status") ?? 200
        let headersDict: [String: String] = {
            guard let raw = call.getObject("headers") else { return [:] }
            var result: [String: String] = [:]
            for (k, v) in raw {
                if let s = v as? String {
                    result[k] = s
                } else if let n = v as? NSNumber {
                    result[k] = n.stringValue
                }
            }
            return result
        }()
        let bodyText = call.getString("bodyText")
        let bodyBase64 = call.getString("bodyBase64")
        let bodyFilePath = call.getString("bodyFilePath")

        let contentType = headersDict.first { $0.key.lowercased() == "content-type" }?.value
            ?? "application/octet-stream"

        let response: GCDWebServerResponse
        if let path = bodyFilePath {
            if !FileManager.default.fileExists(atPath: path) {
                let err = GCDWebServerDataResponse(text: "Response file not found: \(path)")
                err?.statusCode = 500
                if let e = err {
                    bridge.complete(requestId, with: e)
                }
                call.resolve()
                return
            }
            guard let fileResp = GCDWebServerFileResponse(file: path) else {
                let err = GCDWebServerDataResponse(text: "Failed to open response file")
                err?.statusCode = 500
                if let e = err {
                    bridge.complete(requestId, with: e)
                }
                call.resolve()
                return
            }
            fileResp.contentType = contentType
            response = fileResp
        } else if let text = bodyText {
            guard let data = text.data(using: .utf8) else {
                call.reject("Unable to encode bodyText as UTF-8")
                return
            }
            response = GCDWebServerDataResponse(data: data, contentType: contentType)
        } else if let b64 = bodyBase64 {
            guard let data = Data(base64Encoded: b64) else {
                call.reject("Invalid base64 in bodyBase64")
                return
            }
            response = GCDWebServerDataResponse(data: data, contentType: contentType)
        } else {
            response = GCDWebServerResponse()
        }

        response.statusCode = status
        for (k, v) in headersDict {
            let lower = k.lowercased()
            if lower == "content-type" || lower == "content-length" || lower == "transfer-encoding" {
                continue
            }
            response.setValue(v, forAdditionalHeader: k)
        }

        bridge.complete(requestId, with: response)
        call.resolve()
    }

    // MARK: - Server handler

    private func installHandler(on server: GCDWebServer) {
        let matchBlock: GCDWebServerMatchBlock = { [weak self] method, url, headers, path, query in
            guard let self = self else { return nil }
            let cl = Int(headers["Content-Length"] ?? "0") ?? 0
            if cl > self.fileBodyThresholdBytes {
                return GCDWebServerFileRequest(
                    method: method,
                    url: url,
                    headers: headers,
                    path: path,
                    query: query
                )
            } else {
                return GCDWebServerDataRequest(
                    method: method,
                    url: url,
                    headers: headers,
                    path: path,
                    query: query
                )
            }
        }

        let asyncBlock: GCDWebServerAsyncProcessBlock = { [weak self] request, completion in
            guard let self = self else {
                let resp = GCDWebServerDataResponse(text: "Server gone")
                resp?.statusCode = 503
                completion(resp)
                return
            }
            self.handle(request: request, completion: completion)
        }

        server.addHandler(match: matchBlock, asyncProcessBlock: asyncBlock)
    }

    private func handle(request: GCDWebServerRequest, completion: @escaping (GCDWebServerResponse?) -> Void) {
        // Enforce the max body size declared at start(). GCDWebServer already
        // streams large uploads to disk for GCDWebServerFileRequest, so this
        // guard mainly rejects oversized payloads early.
        if Int(request.contentLength) > self.maxBodyBytes {
            let r = GCDWebServerDataResponse(text: "Payload exceeds maxBodyBytes")
            r?.statusCode = 413
            completion(r)
            return
        }

        let requestId = UUID().uuidString
        let headers = lowerCasedHeaders(request.headers)
        let contentType = headers["content-type"] ?? ""
        var tempFile: URL?
        var bodyText: String?
        var bodyBase64: String?
        var bodyFilePath: String?

        if let fileRequest = request as? GCDWebServerFileRequest {
            // GCDWebServer stored the upload in a temporary file we now own.
            let src = URL(fileURLWithPath: fileRequest.temporaryPath)
            let dst = self.tempDir().appendingPathComponent("req-\(requestId)")
            do {
                try? FileManager.default.removeItem(at: dst)
                try FileManager.default.moveItem(at: src, to: dst)
                tempFile = dst
                bodyFilePath = dst.path
            } catch {
                // Fall back to reading from the original path (GCDWebServer will clean it up later).
                tempFile = src
                bodyFilePath = src.path
            }
        } else if let dataRequest = request as? GCDWebServerDataRequest, dataRequest.data.count > 0 {
            let data = dataRequest.data
            if self.isTextLike(contentType) {
                if let str = String(data: data, encoding: .utf8) {
                    bodyText = str
                } else {
                    bodyBase64 = data.base64EncodedString()
                }
            } else {
                bodyBase64 = data.base64EncodedString()
            }
        }

        var event: [String: Any] = [
            "requestId": requestId,
            "method": request.method.uppercased(),
            "path": request.path,
            "query": request.query ?? [:],
            "headers": headers
        ]
        if let v = bodyText { event["bodyText"] = v }
        if let v = bodyBase64 { event["bodyBase64"] = v }
        if let v = bodyFilePath { event["bodyFilePath"] = v }

        let timeoutWork = DispatchWorkItem { [weak self] in
            guard let self = self else { return }
            guard self.bridge.consume(requestId) != nil else { return }
            if let f = tempFile { try? FileManager.default.removeItem(at: f) }
            let r = GCDWebServerDataResponse(text: "JS handler did not respond in time")
            r?.statusCode = 504
            completion(r)
        }
        DispatchQueue.global(qos: .utility).asyncAfter(
            deadline: .now() + HttpServerPlugin.requestTimeout,
            execute: timeoutWork
        )

        bridge.register(
            requestId,
            entry: HttpRequestBridge.Pending(
                completion: { response in completion(response) },
                tempFile: tempFile,
                timeoutWork: timeoutWork
            )
        )

        self.notifyListeners("request", data: event)
    }

    // MARK: - Lifecycle observers

    @objc private func appWillResignActive() {
        // Ask the system for a short background execution window so we can
        // honor in-flight requests before iOS suspends the process. This is
        // best-effort only; iOS does not permit long-running HTTP servers in
        // background. The consumer is expected to treat background as
        // "server is dead" and restart on foreground.
        beginBackgroundTask()
    }

    @objc private func appDidEnterBackground() {
        // Give the app ~25 s to finish pending requests, then notify JS that
        // the server has been stopped by the system.
        DispatchQueue.global(qos: .utility).asyncAfter(deadline: .now() + 25) { [weak self] in
            guard let self = self else { return }
            self.lock.lock()
            let hasServer = self.webServer != nil
            self.lock.unlock()
            if hasServer {
                self.notifyListeners(
                    "server-error",
                    data: [
                        "message": "App moved to background; iOS will suspend the HTTP server.",
                        "fatal": true
                    ]
                )
            }
            self.endBackgroundTask()
        }
    }

    private func beginBackgroundTask() {
        if backgroundTask != .invalid { return }
        backgroundTask = UIApplication.shared.beginBackgroundTask(withName: "CapacitorHttpServer") { [weak self] in
            self?.endBackgroundTask()
        }
    }

    private func endBackgroundTask() {
        if backgroundTask != .invalid {
            UIApplication.shared.endBackgroundTask(backgroundTask)
            backgroundTask = .invalid
        }
    }

    // MARK: - Helpers

    private func tempDir() -> URL {
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent("httpserver", isDirectory: true)
        if !FileManager.default.fileExists(atPath: dir.path) {
            try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        }
        return dir
    }

    private func cleanupTempDir() {
        let dir = tempDir()
        guard let files = try? FileManager.default.contentsOfDirectory(at: dir, includingPropertiesForKeys: nil) else {
            return
        }
        for file in files {
            try? FileManager.default.removeItem(at: file)
        }
    }

    private func isTextLike(_ contentType: String) -> Bool {
        let ct = contentType.lowercased()
        return ct.hasPrefix("text/") ||
            ct.hasPrefix("application/json") ||
            ct.hasPrefix("application/x-www-form-urlencoded") ||
            ct.hasPrefix("application/xml") ||
            ct.hasPrefix("application/javascript")
    }

    private func lowerCasedHeaders(_ headers: [String: String]) -> [String: String] {
        var result: [String: String] = [:]
        for (k, v) in headers {
            result[k.lowercased()] = v
        }
        return result
    }

    private func parseQuery(_ raw: String?) -> [String: String] {
        guard let raw = raw, !raw.isEmpty else { return [:] }
        var result: [String: String] = [:]
        for pair in raw.split(separator: "&") {
            let parts = pair.split(separator: "=", maxSplits: 1, omittingEmptySubsequences: false)
            let key = parts.first.map(String.init) ?? ""
            let value = parts.count > 1 ? String(parts[1]) : ""
            let k = key.removingPercentEncoding ?? key
            let v = value.removingPercentEncoding ?? value
            result[k] = v
        }
        return result
    }

    private func pickFreePort() throws -> UInt16 {
        let s = socket(AF_INET, SOCK_STREAM, 0)
        if s < 0 { throw NSError(domain: "HttpServer", code: 1, userInfo: [NSLocalizedDescriptionKey: "socket() failed"]) }
        defer { close(s) }

        var yes: Int32 = 1
        setsockopt(s, SOL_SOCKET, SO_REUSEADDR, &yes, socklen_t(MemoryLayout<Int32>.size))

        var addr = sockaddr_in()
        addr.sin_len = UInt8(MemoryLayout<sockaddr_in>.size)
        addr.sin_family = sa_family_t(AF_INET)
        addr.sin_port = 0
        addr.sin_addr.s_addr = in_addr_t(0) // INADDR_ANY

        let bindResult = withUnsafePointer(to: &addr) { ptr -> Int32 in
            ptr.withMemoryRebound(to: sockaddr.self, capacity: 1) { sa in
                bind(s, sa, socklen_t(MemoryLayout<sockaddr_in>.size))
            }
        }
        if bindResult != 0 {
            throw NSError(domain: "HttpServer", code: 2, userInfo: [NSLocalizedDescriptionKey: "bind() failed"])
        }

        var resultAddr = sockaddr_in()
        var len = socklen_t(MemoryLayout<sockaddr_in>.size)
        let nameResult = withUnsafeMutablePointer(to: &resultAddr) { ptr -> Int32 in
            ptr.withMemoryRebound(to: sockaddr.self, capacity: 1) { sa in
                getsockname(s, sa, &len)
            }
        }
        if nameResult != 0 {
            throw NSError(domain: "HttpServer", code: 3, userInfo: [NSLocalizedDescriptionKey: "getsockname() failed"])
        }
        return UInt16(bigEndian: resultAddr.sin_port)
    }

    private func localIPv4() -> String {
        let preferredInterfaces = ["en0", "en1", "bridge100", "pdp_ip0"]
        var fallback: String?
        var preferred: String?

        var ifaddr: UnsafeMutablePointer<ifaddrs>?
        guard getifaddrs(&ifaddr) == 0, let first = ifaddr else { return "127.0.0.1" }
        defer { freeifaddrs(ifaddr) }

        var ptr: UnsafeMutablePointer<ifaddrs>? = first
        while let cur = ptr {
            let iface = cur.pointee
            let family = iface.ifa_addr.pointee.sa_family
            if family == UInt8(AF_INET) {
                let name = String(cString: iface.ifa_name)
                if name != "lo0" {
                    var hostname = [CChar](repeating: 0, count: Int(NI_MAXHOST))
                    let res = getnameinfo(
                        iface.ifa_addr,
                        socklen_t(iface.ifa_addr.pointee.sa_len),
                        &hostname,
                        socklen_t(hostname.count),
                        nil,
                        0,
                        NI_NUMERICHOST
                    )
                    if res == 0 {
                        let ipString = String(cString: hostname)
                        if preferredInterfaces.contains(name) {
                            preferred = ipString
                            break
                        } else if fallback == nil {
                            fallback = ipString
                        }
                    }
                }
            }
            ptr = iface.ifa_next
        }

        return preferred ?? fallback ?? "127.0.0.1"
    }
}
