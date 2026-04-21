import Foundation
import GCDWebServer

/// Tracks the requests that are currently waiting for a JS-side response.
///
/// Each entry stores the GCDWebServer completion block, an optional reference
/// to a temp file that must be deleted once the response has been written
/// (for request bodies that were streamed to disk), and a pre-scheduled
/// timeout work item that turns unanswered requests into 504 responses.
final class HttpRequestBridge {

    struct Pending {
        let completion: (GCDWebServerResponse) -> Void
        let tempFile: URL?
        let timeoutWork: DispatchWorkItem
    }

    private let lock = NSLock()
    private var pending: [String: Pending] = [:]

    func register(_ id: String, entry: Pending) {
        lock.lock(); defer { lock.unlock() }
        pending[id] = entry
    }

    /// Remove and return the entry for `id`, or `nil` if it was already consumed.
    @discardableResult
    func consume(_ id: String) -> Pending? {
        lock.lock(); defer { lock.unlock() }
        return pending.removeValue(forKey: id)
    }

    /// Complete `id` with `response`. No-op if the entry was already consumed.
    func complete(_ id: String, with response: GCDWebServerResponse) {
        guard let entry = consume(id) else { return }
        entry.timeoutWork.cancel()
        entry.completion(response)
        if let file = entry.tempFile {
            try? FileManager.default.removeItem(at: file)
        }
    }

    /// Drain all pending requests, completing each one with `response`.
    func drainAll(with response: GCDWebServerResponse) {
        lock.lock()
        let snapshot = pending
        pending.removeAll()
        lock.unlock()
        for (_, entry) in snapshot {
            entry.timeoutWork.cancel()
            entry.completion(response)
            if let file = entry.tempFile {
                try? FileManager.default.removeItem(at: file)
            }
        }
    }

    var count: Int {
        lock.lock(); defer { lock.unlock() }
        return pending.count
    }
}
