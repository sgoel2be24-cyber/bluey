import AppKit
import GooglyShared

/// His brain when Fireworks is picked in the menu bar. Fireworks has no realtime voice or speech-to-text,
/// so the Mac listens with Apple's speech recognition and a Fireworks model (one that can see images and
/// use tools) does the thinking. His tools, captions and pointing are the same ones the OpenAI session uses.
///
/// The phone stays the face: it says when you hold and let go, and gets back what was heard and said
/// (for its chirps and saved transcripts).
@MainActor
final class FireworksBrain {
    private unowned let host: RealtimeHost
    /// Sends a packet to the phone(s).
    var toPhone: ((Packet) -> Void)?

    private let ears = MacEars()
    private var messages: [[String: Any]] = []
    private var overheard: [String] = []
    private var turn = 0
    private var thinking: Task<Void, Never>?
    private var sleepAfterReply = false
    private(set) var active = false

    /// Serverless Fireworks models that can see screenshots and call tools, as (id, display name).
    private(set) var models: [(id: String, name: String)] = []
    private var loadingModels: Task<Void, Never>?
    /// Models that refused "reasoning_effort: none" (thinking-only ones): they get asked without it.
    private var mustThink: Set<String> = []

    static let base = "https://api.fireworks.ai/inference/v1"

    init(host: RealtimeHost) {
        self.host = host
        ears.onHeard = { [weak self] text in self?.heard(text) }
    }

    // MARK: Session

    /// Starts listening on the Mac. Calls back with a problem to show, or nil once he's ready.
    func begin(_ done: @escaping (String?) -> Void) {
        guard Keychain.get(.fireworks) != nil else {
            done("I need a Fireworks API key. Add one under Fireworks Key in the menu bar.")
            return
        }
        MacEars.requestPermissions { [weak self] problem in
            guard let self else { return }
            if let problem { done(problem); return }
            do {
                try self.ears.start()
            } catch {
                done("I couldn't start listening: \(error.localizedDescription)")
                return
            }
            self.active = true
            self.messages = [["role": "system", "content": Self.instructions]]
            self.overheard = []
            self.sleepAfterReply = false
            done(nil)
            Task { await self.ensureModel() }
        }
    }

    func end() {
        guard active else { return }
        active = false
        ears.stop()
        thinking?.cancel()
        thinking = nil
        messages = []
        overheard = []
    }

    func askStart() {
        guard active else { return }
        ears.askStart()
    }

    func askEnd() {
        guard active else { return }
        ears.askEnd { [weak self] question in self?.answer(question) }
    }

    func sayHi() {
        guard active, thinking == nil else { return }
        messages.append(["role": "user", "content": "(The user tapped a test button.) Reply with a quick, cheerful hi in under eight words."])
        think()
    }

    private func heard(_ text: String) {
        guard active else { return }
        overheard.append(text)
        turn += 1
        toPhone?(Packet(command: "heard", speech: turn, text: text))
    }

    private func answer(_ question: String) {
        guard active else { return }
        var content = ""
        if !overheard.isEmpty {
            content += "Overheard since your last reply:\n" + overheard.map { "- " + $0 }.joined(separator: "\n") + "\n\n"
            overheard = []
        }
        if question.isEmpty {
            guard !content.isEmpty else { toPhone?(Packet(command: "turnDone")); return }
            content += "Question: (they held the screen without saying anything new; respond to what they said last)"
        } else {
            turn += 1
            toPhone?(Packet(command: "asked", speech: turn, text: question))
            content += "Question: " + question
        }
        messages.append(["role": "user", "content": content])
        think()
    }

    // MARK: Thinking

    private func think() {
        thinking?.cancel()
        thinking = Task { [weak self] in
            await self?.run()
            self?.thinking = nil
        }
    }

    /// Asks the model, runs the tools it calls, and goes round again until it just replies.
    private func run() async {
        for _ in 0..<14 {
            guard active, !Task.isCancelled else { return }
            let reply: Reply
            do {
                reply = try await complete()
            } catch {
                if Task.isCancelled { return }
                host.handle(Packet(command: "captionDone", text: "Fireworks didn't answer: \(error.localizedDescription.prefix(140))")) { _ in }
                break
            }
            guard active, !Task.isCancelled else { return }

            var said: [String: Any] = ["role": "assistant", "content": reply.text]
            if !reply.calls.isEmpty {
                said["tool_calls"] = reply.calls.map {
                    ["id": $0.id, "type": "function", "function": ["name": $0.name, "arguments": $0.arguments]] as [String: Any]
                }
            }
            messages.append(said)
            if !reply.text.isEmpty {
                host.handle(Packet(command: "captionDone", text: reply.text)) { _ in }
                toPhone?(Packet(command: "replyDone", text: reply.text))
            }
            if reply.calls.isEmpty { break }

            var images: [String] = []
            for call in reply.calls {
                if call.name == "go_to_sleep" { sleepAfterReply = true }
                let (output, image) = await runTool(call)
                guard active else { return }
                if call.name == "web_research", let range = output.range(of: RealtimeHost.reportMarker) {
                    toPhone?(Packet(command: "report", text: String(output[range.upperBound...])))
                }
                messages.append(["role": "tool", "tool_call_id": call.id, "content": output])
                if let image { images.append(image) }
            }
            // Screenshots go in as a picture so he can actually see the screen.
            if !images.isEmpty {
                messages.append(["role": "user", "content": [["type": "text", "text": "The screen right now:"]] + images.map {
                    ["type": "image_url", "image_url": ["url": "data:image/jpeg;base64,\($0)"]] as [String: Any]
                }])
            }
        }
        toPhone?(Packet(command: "turnDone"))
        if sleepAfterReply {
            sleepAfterReply = false
            toPhone?(Packet(command: "sleep"))
        }
    }

    private func runTool(_ call: ToolCall) async -> (String, String?) {
        await withCheckedContinuation { continuation in
            host.handle(Packet(command: "tool", callID: call.id, tool: call.name, text: call.arguments.isEmpty ? "{}" : call.arguments)) { reply in
                continuation.resume(returning: (reply.text ?? "", reply.image))
            }
        }
    }

    private struct ToolCall {
        var id: String
        var name: String
        var arguments: String
    }

    private struct Reply {
        var text: String
        var calls: [ToolCall]
    }

    /// One streamed chat completion. His words show up in the speech bubble as they arrive.
    private func complete() async throws -> Reply {
        guard let key = Keychain.get(.fireworks) else { throw BrainError.noKey }
        let model = await ensureModel()
        do {
            return try await complete(model: model, key: key, think: mustThink.contains(model))
        } catch BrainError.thinkingOnly {
            mustThink.insert(model)
            return try await complete(model: model, key: key, think: true)
        }
    }

    private func complete(model: String, key: String, think: Bool) async throws -> Reply {
        var request = URLRequest(url: URL(string: Self.base + "/chat/completions")!)
        request.httpMethod = "POST"
        request.timeoutInterval = 90
        request.setValue("Bearer \(key)", forHTTPHeaderField: "Authorization")
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.setValue("text/event-stream", forHTTPHeaderField: "Accept")
        var body: [String: Any] = [
            "model": model,
            "messages": Self.withLatestScreenshotOnly(messages),
            "tools": Self.tools,
            "tool_choice": "auto",
            "stream": true,
            "max_tokens": 700,
            "temperature": 0.6,
        ]
        // These models all think before answering; for one-line replies that only adds seconds.
        if !think { body["reasoning_effort"] = "none" }
        request.httpBody = try JSONSerialization.data(withJSONObject: body)

        let (bytes, response) = try await URLSession.shared.bytes(for: request)
        let code = (response as? HTTPURLResponse)?.statusCode ?? 0
        guard code == 200 else {
            var body = ""
            for try await line in bytes.lines { body += line; if body.count > 400 { break } }
            let message = Self.errorMessage(body)
            if code == 400, !think, message.localizedCaseInsensitiveContains("think") || message.localizedCaseInsensitiveContains("reasoning") {
                throw BrainError.thinkingOnly
            }
            throw BrainError.failed("\(code) \(message)")
        }

        var raw = ""
        var shown = ""
        var calls: [Int: ToolCall] = [:]
        for try await line in bytes.lines {
            guard line.hasPrefix("data:") else { continue }
            let payload = line.dropFirst(5).trimmingCharacters(in: .whitespaces)
            if payload == "[DONE]" { break }
            guard let json = try? JSONSerialization.jsonObject(with: Data(payload.utf8)) as? [String: Any],
                  let choice = (json["choices"] as? [[String: Any]])?.first,
                  let delta = choice["delta"] as? [String: Any] else { continue }
            if let piece = delta["content"] as? String, !piece.isEmpty {
                raw += piece
                let visible = Self.withoutThinking(raw)
                if visible != shown, !visible.isEmpty {
                    if shown.isEmpty { toPhone?(Packet(command: "replying", text: visible)) }
                    shown = visible
                    host.handle(Packet(command: "caption", text: visible)) { _ in }
                }
            }
            for part in delta["tool_calls"] as? [[String: Any]] ?? [] {
                let index = part["index"] as? Int ?? calls.count
                var call = calls[index] ?? ToolCall(id: "", name: "", arguments: "")
                if let id = part["id"] as? String, !id.isEmpty { call.id = id }
                if let function = part["function"] as? [String: Any] {
                    if let name = function["name"] as? String, !name.isEmpty { call.name = name }
                    if let arguments = function["arguments"] as? String { call.arguments += arguments }
                }
                calls[index] = call
            }
        }
        let ordered = calls.sorted { $0.key < $1.key }.enumerated().compactMap { offset, entry -> ToolCall? in
            var call = entry.value
            guard !call.name.isEmpty else { return nil }
            if call.id.isEmpty { call.id = "call_\(turn)_\(offset)" }
            return call
        }
        return Reply(text: shown, calls: ordered)
    }

    enum BrainError: LocalizedError {
        case noKey, thinkingOnly, failed(String)
        var errorDescription: String? {
            switch self {
            case .noKey: return "I need a Fireworks API key. Add one under Fireworks Key in the menu bar."
            case .thinkingOnly: return "This model can't answer without thinking first."
            case .failed(let why): return why
            }
        }
    }

    private static func errorMessage(_ body: String) -> String {
        if let json = try? JSONSerialization.jsonObject(with: Data(body.utf8)) as? [String: Any] {
            if let error = json["error"] as? [String: Any], let message = error["message"] as? String { return message }
            if let message = json["error"] as? String { return message }
        }
        return body
    }

    /// Some models think out loud in <think> tags first; only the answer goes in the bubble.
    private static func withoutThinking(_ text: String) -> String {
        var text = text.replacingOccurrences(of: #"(?s)<think>.*?</think>"#, with: "", options: .regularExpression)
        if let open = text.range(of: "<think>") { text = String(text[..<open.lowerBound]) }
        return text.trimmingCharacters(in: .whitespacesAndNewlines)
    }

    /// Old screenshots are swapped for a note, so each request carries only the latest picture.
    private static func withLatestScreenshotOnly(_ messages: [[String: Any]]) -> [[String: Any]] {
        let last = messages.lastIndex { ($0["content"] as? [[String: Any]])?.contains { $0["type"] as? String == "image_url" } == true }
        return messages.enumerated().map { index, message in
            guard index != last, let parts = message["content"] as? [[String: Any]],
                  parts.contains(where: { $0["type"] as? String == "image_url" }) else { return message }
            var message = message
            message["content"] = "(An earlier screenshot, no longer shown.)"
            return message
        }
    }

    // MARK: Instructions and tools

    /// His tools in chat-completions form. Web research needs OpenAI, so it's only offered with an OpenAI key too.
    static var tools: [[String: Any]] {
        RealtimeHost.tools.compactMap { tool in
            guard let name = tool["name"] as? String else { return nil }
            if name == "web_research", Keychain.get(.openai) == nil { return nil }
            return ["type": "function", "function": [
                "name": name,
                "description": tool["description"] ?? "",
                "parameters": tool["parameters"] ?? ["type": "object", "properties": [String: Any]()],
            ] as [String: Any]]
        }
    }

    static var instructions: String {
        var guide = """
        How the conversation works: you can hear the user the whole time. What they say reaches you as text. \
        Lines under "Overheard" are background context: never reply to them on their own. You only reply when \
        the user holds the phone screen to ask you something, marked "Question:". Answer that, using the earlier \
        talk as context.

        Most important rule: when a request needs a tool, call the tool FIRST with no words before it. Never \
        write things like "one moment", "sure", "okay" or "let me". Reply only after, in one short line of plain \
        text. No lists, no markdown, no emoji, no quotation marks around your reply, never mention ids or coordinates.

        Point whenever you can. If the question is about anything on the screen ("what's this?", "what does this \
        mean?"), call look_at_screen, then point_at (or point_at_spot) the thing you're talking about, then give \
        your one-line answer; your bubble appears right by your cursor. "This", "that" and "here" mean what's at \
        the user's mouse pointer, which look_at_screen tells you. Point at the most specific thing (a word or \
        number rather than a whole line). For shapes, arrows or charts with no text, use point_at_spot. If the \
        screen may have changed, look again. When the user says goodbye or asks you to sleep, reply with a very \
        short goodbye and call go_to_sleep.
        """
        if Keychain.get(.openai) != nil {
            guide += """


            Research: when answering well needs facts you're not sure of, anything recent, or details from the web, \
            use web_research. Point at the relevant thing on screen if there is one, write one short line that ends \
            with "doing some research…", then call web_research. Afterwards reply with just one short takeaway line.
            """
        } else {
            guide += "\n\nYou can't browse the web. If something needs up-to-date facts, say so briefly and give your best answer."
        }
        return RealtimeHost.personality + "\n\n" + guide
            + (Settings.shared.computerControl ? "\n\n" + RealtimeHost.computerGuide : "")
    }

    // MARK: Models

    /// The model to use: the one picked in the menu, or the best available one.
    @discardableResult
    func ensureModel() async -> String {
        if let picked = Settings.shared.fireworksModel { return picked }
        await loadModels()
        if let best = Self.preferred(models) {
            Settings.shared.fireworksModel = best
            return best
        }
        return Self.fallbackModel
    }

    /// A safe default if the model list can't be read: a serverless model that takes images.
    static let fallbackModel = "accounts/fireworks/models/qwen3p8-max"

    /// Strong, fast, multimodal tool-callers first.
    private static func preferred(_ models: [(id: String, name: String)]) -> String? {
        // From a side-by-side run (October 2026): all call tools well; these answer fastest with thinking off.
        let order = ["qwen3p8-max", "deepseek-v4p1-flash", "kimi-k3", "ember-1", "qwen3", "kimi", "deepseek", "glm", "gemma", "llama"]
        for hint in order {
            if let match = models.first(where: { $0.id.contains(hint) && !$0.id.contains("thinking") && !$0.id.contains("code") }) {
                return match.id
            }
        }
        return models.first?.id
    }

    /// Reads which serverless models can see images and call tools.
    func loadModels() async {
        if let loadingModels { await loadingModels.value; return }
        guard let key = Keychain.get(.fireworks) else { return }
        let task = Task { @MainActor in
            var found: [(id: String, name: String)] = []
            var pageToken = ""
            for _ in 0..<6 {
                var parts = URLComponents(string: "https://api.fireworks.ai/v1/accounts/fireworks/models")!
                parts.queryItems = [URLQueryItem(name: "filter", value: "supports_serverless=true"),
                                    URLQueryItem(name: "pageSize", value: "200")]
                if !pageToken.isEmpty { parts.queryItems?.append(URLQueryItem(name: "pageToken", value: pageToken)) }
                var request = URLRequest(url: parts.url!)
                request.setValue("Bearer \(key)", forHTTPHeaderField: "Authorization")
                guard let (data, response) = try? await URLSession.shared.data(for: request),
                      (response as? HTTPURLResponse)?.statusCode == 200,
                      let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else { break }
                for model in json["models"] as? [[String: Any]] ?? [] {
                    guard model["supportsImageInput"] as? Bool == true, model["supportsTools"] as? Bool == true,
                          let id = model["name"] as? String else { continue }
                    let name = (model["displayName"] as? String).flatMap { $0.isEmpty ? nil : $0 } ?? id.components(separatedBy: "/").last ?? id
                    found.append((id, name))
                }
                pageToken = json["nextPageToken"] as? String ?? ""
                if pageToken.isEmpty { break }
            }
            self.models = found.sorted { $0.name.localizedCaseInsensitiveCompare($1.name) == .orderedAscending }
        }
        loadingModels = task
        await task.value
        loadingModels = nil
    }

    /// Forgets the model list (after a new key), so it's read again.
    func resetModels() {
        models = []
    }
}
