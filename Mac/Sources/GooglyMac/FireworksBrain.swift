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
    /// A look at the screen taken as you start asking, so his answer can point straight away.
    private var screen: Task<(text: String, image: String?), Never>?
    private var sleepAfterReply = false
    private(set) var active = false
    /// A hands-free question that came in while his session was still starting.
    private var queuedQuestion: String?
    /// Asked the phone to wake him (heard "Hey Bluey" while he was asleep).
    private var wakingSince: Double?
    /// How long after his reply you can just keep talking.
    private static let followUpWindow = 8.0

    /// Serverless Fireworks models that can see screenshots and call tools, as (id, display name).
    private(set) var models: [(id: String, name: String)] = []
    private var loadingModels: Task<Void, Never>?
    /// Models that refused "reasoning_effort: none" (thinking-only ones): they get asked without it.
    private var mustThink: Set<String> = []

    static let base = "https://api.fireworks.ai/inference/v1"

    init(host: RealtimeHost) {
        self.host = host
        ears.onHeard = { [weak self] text in self?.heard(text) }
        ears.onWake = { [weak self] in self?.wakeHeard() }
        ears.onQuestion = { [weak self] question in self?.handsFreeQuestion(question) }
    }

    // MARK: "Hey Bluey"

    /// Hands-free is on, Fireworks is his brain, and this Mac can recognize speech without sending it anywhere.
    var handsFreeReady: Bool {
        Settings.shared.brain == .fireworks && Settings.shared.heyBluey && ears.onDevice
    }

    /// While he's asleep, listens for "Hey Bluey" on the Mac's mic (on-device; nothing else is kept).
    /// Called at launch and whenever the settings change. Needs the mic and speech permissions already given.
    func listenForWakeWord() {
        guard !active else { ears.handsFree = Settings.shared.heyBluey; return }
        guard handsFreeReady, MacEars.permitted, Keychain.get(.fireworks) != nil else {
            ears.handsFree = false
            ears.stop()
            return
        }
        ears.handsFree = true
        ears.wakeOnly = true
        try? ears.start()
    }

    /// Heard "Hey Bluey" (or a follow-up started).
    private func wakeHeard() {
        // Look while they're still talking, so the screen is ready when the question is.
        screen = Task { [host] in await host.lookNow() }
        if active {
            toPhone?(Packet(command: "earsOpen", text: "wake"))
        } else if wakingSince.map({ CACurrentMediaTime() - $0 > 10 }) ?? true {
            // Asleep: the phone starts the session (as if you'd double tapped), then this question gets answered.
            wakingSince = CACurrentMediaTime()
            toPhone?(Packet(command: "wake"))
        }
    }

    private func handsFreeQuestion(_ question: String) {
        if active {
            answer(question)
        } else {
            queuedQuestion = question  // the session is still starting
            DispatchQueue.main.asyncAfter(deadline: .now() + 10) { [weak self] in
                if self?.active == false { self?.queuedQuestion = nil }
            }
        }
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
            // Already listening for "Hey Bluey": keep going, so a question in progress isn't lost.
            self.ears.handsFree = Settings.shared.heyBluey
            self.ears.wakeOnly = false
            do {
                try self.ears.start()
            } catch {
                done("I couldn't start listening: \(error.localizedDescription)")
                return
            }
            self.active = true
            self.wakingSince = nil
            self.messages = [["role": "system", "content": Self.instructions]]
            self.overheard = []
            self.sleepAfterReply = false
            done(nil)
            Task { await self.ensureModel() }
            if let question = self.queuedQuestion {
                self.queuedQuestion = nil
                self.answer(question)
            }
        }
    }

    func end() {
        guard active else { return }
        active = false
        ears.followUpUntil = 0
        if handsFreeReady {
            ears.wakeOnly = true  // back to waiting for "Hey Bluey"
        } else {
            ears.stop()
        }
        thinking?.cancel()
        thinking = nil
        screen = nil
        messages = []
        overheard = []
    }

    func askStart() {
        guard active else { return }
        ears.askStart()
        // Look while you're still talking, so the screen is ready the moment you let go.
        screen = Task { [host] in await host.lookNow() }
    }

    func askEnd() {
        guard active else { toPhone?(Packet(command: "turnDone")); return }
        if screen == nil { screen = Task { [host] in await host.lookNow() } }
        ears.askEnd { [weak self] question in self?.answer(question) }
    }

    func sayHi() {
        guard active, thinking == nil else { return }
        messages.append(["role": "user", "content": "(The user tapped a test button.) Reply with a quick, cheerful hi in under eight words."])
        think { await $0.run() }
    }

    private func heard(_ text: String) {
        guard active else { return }
        overheard.append(text)
        turn += 1
        toPhone?(Packet(command: "heard", speech: turn, text: text))
    }

    private func answer(_ question: String) {
        guard active else { toPhone?(Packet(command: "turnDone")); return }
        let screen = self.screen ?? Task { [host] in await host.lookNow() }
        self.screen = nil
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
        think { brain in
            // The question goes in with the screen as it was when they asked: its text with ids, and a picture.
            let look = await screen.value
            var parts: [[String: Any]] = [["type": "text", "text": content + Self.screenMarker + look.text]]
            if let image = look.image {
                parts.append(["type": "image_url", "image_url": ["url": "data:image/jpeg;base64,\(image)"]])
            }
            brain.messages.append(["role": "user", "content": parts])
            await brain.run()
        }
    }

    // MARK: Thinking

    private static let screenMarker = "\n\nThe screen when they asked:\n"
    /// Tools that only point (or say goodbye): once he's said his line with them, there's nothing to go back for.
    private static let quickTools: Set<String> = ["point_at", "point_at_spot", "stop_pointing", "go_to_sleep"]

    private func think(_ work: @escaping (FireworksBrain) async -> Void) {
        thinking?.cancel()
        thinking = Task { [weak self] in
            guard let self else { return }
            await work(self)
            self.thinking = nil
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
            var lines: [String] = []
            for call in reply.calls {
                if call.name == "go_to_sleep" { sleepAfterReply = true }
                if let line = Self.say(in: call.arguments) { lines.append(line) }
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
            // His line came inside the pointing call: show it by the cursor.
            if !lines.isEmpty {
                let line = lines.joined(separator: " ")
                if reply.text.isEmpty { toPhone?(Packet(command: "replying", text: line)) }
                host.handle(Packet(command: "captionDone", text: reply.text.isEmpty ? line : reply.text + " " + line)) { _ in }
                toPhone?(Packet(command: "replyDone", text: line))
            }
            // Pointed and said his piece: no need for another trip to the model.
            if reply.calls.allSatisfy({ Self.quickTools.contains($0.name) }), !lines.isEmpty || !reply.text.isEmpty { break }
        }
        toPhone?(Packet(command: "turnDone"))
        if sleepAfterReply {
            sleepAfterReply = false
            toPhone?(Packet(command: "sleep"))
        } else if ears.handsFree {
            // A moment to follow up without "Hey Bluey".
            ears.followUpUntil = CACurrentMediaTime() + Self.followUpWindow
            toPhone?(Packet(command: "earsOpen", text: "followUp"))
            DispatchQueue.main.asyncAfter(deadline: .now() + Self.followUpWindow) { [weak self] in
                guard let self, self.active, CACurrentMediaTime() >= self.ears.followUpUntil else { return }
                self.toPhone?(Packet(command: "earsClosed"))
            }
        }
    }

    /// Runs one of his tools on the Mac, giving up if it never comes back.
    private func runTool(_ call: ToolCall) async -> (String, String?) {
        let limit: Double = call.name == "web_research" ? 120 : 45
        return await withCheckedContinuation { continuation in
            var finished = false
            let finish: (String, String?) -> Void = { text, image in
                guard !finished else { return }
                finished = true
                continuation.resume(returning: (text, image))
            }
            host.handle(Packet(command: "tool", callID: call.id, tool: call.name, text: call.arguments.isEmpty ? "{}" : call.arguments)) { reply in
                finish(reply.text ?? "", reply.image)
            }
            DispatchQueue.main.asyncAfter(deadline: .now() + limit) {
                finish("\(call.name) didn't finish in time. Tell the user in a few words.", nil)
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
            "messages": Self.withLatestScreenOnly(messages),
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

    /// The line he wants to say, from a pointing call's "say" argument.
    private static func say(in arguments: String) -> String? {
        guard let json = try? JSONSerialization.jsonObject(with: Data(arguments.utf8)) as? [String: Any],
              let line = (json["say"] as? String)?.trimmingCharacters(in: .whitespacesAndNewlines), !line.isEmpty else { return nil }
        return line
    }

    /// Some models think out loud in <think> tags first; only the answer goes in the bubble.
    private static func withoutThinking(_ text: String) -> String {
        var text = text.replacingOccurrences(of: #"(?s)<think>.*?</think>"#, with: "", options: .regularExpression)
        if let open = text.range(of: "<think>") { text = String(text[..<open.lowerBound]) }
        return text.trimmingCharacters(in: .whitespacesAndNewlines)
    }

    /// Every look at the screen is big (a picture plus every word on it), so only the latest one is sent;
    /// earlier ones are swapped for a short note.
    private static func withLatestScreenOnly(_ messages: [[String: Any]]) -> [[String: Any]] {
        func hasScreen(_ message: [String: Any]) -> Bool {
            if let text = message["content"] as? String { return text.contains(screenListMarker) }
            let parts = message["content"] as? [[String: Any]] ?? []
            return parts.contains { $0["type"] as? String == "image_url" || ($0["text"] as? String)?.contains(screenMarker) == true }
        }
        guard let latest = messages.lastIndex(where: hasScreen) else { return messages }
        return messages.enumerated().map { index, message in
            guard index < latest, hasScreen(message) else { return message }
            var message = message
            if let text = message["content"] as? String {
                // A tool's look at the screen: keep what it did, drop the screen.
                let head = text.components(separatedBy: "\n").first ?? ""
                message["content"] = String(head.prefix(200)) + " (Earlier screen, no longer shown.)"
            } else {
                let parts = message["content"] as? [[String: Any]] ?? []
                let texts = parts.compactMap { $0["text"] as? String }.map { $0.components(separatedBy: screenMarker).first ?? $0 }
                let kept = texts.filter { $0 != "The screen right now:" }.joined(separator: "\n")
                message["content"] = kept.isEmpty ? "(An earlier screenshot, no longer shown.)" : kept + "\n\n(Their screen then: no longer shown.)"
            }
            return message
        }
    }

    /// How a look at the screen starts its list of text (see ScreenSnapshot.targetList).
    private static let screenListMarker = "Text on screen (L = line"

    // MARK: Instructions and tools

    /// His tools in chat-completions form. Web research needs OpenAI, so it's only offered with an OpenAI key too.
    static var tools: [[String: Any]] {
        RealtimeHost.tools.compactMap { tool in
            guard let name = tool["name"] as? String else { return nil }
            if name == "web_research", Keychain.get(.openai) == nil { return nil }
            var description = tool["description"] as? String ?? ""
            var parameters = tool["parameters"] as? [String: Any] ?? ["type": "object", "properties": [String: Any]()]
            switch name {
            case "look_at_screen":
                description = "Take a fresh look at the user's screen. Their question already comes with the screen as it was "
                    + "when they asked, so only call this if it may have changed since (for example after you've clicked or typed)."
            case "point_at", "point_at_spot":
                // His answer rides along with the point, so one call does both.
                var properties = parameters["properties"] as? [String: Any] ?? [:]
                properties["say"] = ["type": "string",
                                     "description": "Your one-line answer about the thing, shown in your speech bubble as you point."]
                parameters["properties"] = properties
                parameters["required"] = (parameters["required"] as? [String] ?? []) + ["say"]
                description += " Put your one-line answer in say."
            default:
                break
            }
            return ["type": "function", "function": ["name": name, "description": description, "parameters": parameters] as [String: Any]]
        }
    }

    static var instructions: String {
        var guide = """
        How the conversation works: you can hear the user the whole time. What they say reaches you as text. \
        Lines under "Overheard" are background context: never reply to them on their own. You only reply when \
        the user asks you something (by holding the phone, saying "Hey Bluey", or following up right after your \
        reply), marked "Question:". Answer that, using the earlier \
        talk as context. Each question comes with the screen as it was when they asked: the frontmost app, its \
        controls (C ids), its text (lines L#, words W#) with positions on a 0-1000 grid, where their mouse is, and \
        a screenshot.

        Your replies are one short line of plain text. No lists, no markdown, no emoji, no quotation marks around \
        your reply, never mention ids or coordinates. Never write things like "one moment", "sure", "okay" or \
        "let me", and never say you're going to look or point: just do it.

        Point whenever you can. If the question is about anything on the screen ("what's this?", "what does this \
        mean?"), answer by calling point_at on the thing, with your one-line answer in say: that one call is your \
        whole reply, and your bubble appears right by your cursor. "This", "that" and "here" mean what's at the \
        user's mouse pointer. Point at the most specific thing (a word or number rather than a whole line). For \
        shapes, arrows or charts with no text, use point_at_spot. Only call look_at_screen if the screen may have \
        changed since they asked. If the question isn't about the screen, just reply with your line. When the user \
        says goodbye or asks you to sleep, reply with a very short goodbye and call go_to_sleep.
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
        if let automatic { return automatic }
        await loadModels()
        let best = Self.preferred(models) ?? Self.fallbackModel
        automatic = best  // remembered for this run only, so Automatic keeps following the best available
        return best
    }

    private var automatic: String?

    /// A safe default if the model list can't be read: a serverless model that takes images.
    static let fallbackModel = "accounts/fireworks/models/ember-1"

    /// Strong, fast, multimodal tool-callers first.
    private static func preferred(_ models: [(id: String, name: String)]) -> String? {
        // From side-by-side runs (October 2026) with real screens: all call tools well; these answer fastest with thinking off.
        // Ember-1 also answered inside its pointing calls most reliably, in a single round.
        let order = ["ember-1", "deepseek-v4p1-flash", "qwen3p8-max", "kimi-k3", "qwen3", "kimi", "deepseek", "glm", "gemma", "llama"]
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
        automatic = nil
    }
}
