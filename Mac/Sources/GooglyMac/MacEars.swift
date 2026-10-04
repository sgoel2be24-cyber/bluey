import AVFoundation
import Speech

/// Listens through the Mac's microphone with Apple's speech recognition (on-device when available),
/// one turn at a time. A pause closes a turn of overheard talk; holding the phone marks the question.
/// Used when Fireworks is his brain, since Fireworks has no speech-to-text of its own.
///
/// Hands-free: a turn with "Hey Bluey" in it is a question (the words after it), and so is talk that starts
/// right after he answers. While he's asleep it only listens for "Hey Bluey", on-device, and keeps nothing else.
@MainActor
final class MacEars {
    /// A finished turn of talk he overheard (background context).
    var onHeard: ((String) -> Void)?
    /// Heard "Hey Bluey" (or a follow-up started): a question is on its way.
    var onWake: (() -> Void)?
    /// A hands-free question, finished by a pause.
    var onQuestion: ((String) -> Void)?

    /// Listen for "Hey Bluey" and follow-ups.
    var handsFree = false
    /// Asleep: only "Hey Bluey" counts; everything else is dropped, not kept as context.
    var wakeOnly = false
    /// He just answered: talk that starts before this time is a follow-up question.
    var followUpUntil = 0.0

    private let engine = AVAudioEngine()
    private let recognizer = SFSpeechRecognizer(locale: Locale.current) ?? SFSpeechRecognizer(locale: Locale(identifier: "en-US"))
    /// The request the microphone feeds right now. Swapped at every turn; read from the audio thread.
    private let feed = Feed()
    private var current: Turn?
    /// Turns that have stopped listening and are waiting for their final words.
    private var ending: [Turn] = []
    private var timer: Timer?
    private var asking = false
    /// Said just "Hey Bluey" and paused: the next words are the question.
    private var expectQuestionUntil = 0.0
    private(set) var running = false

    /// How long a pause closes a turn of overheard talk (or a hands-free question).
    private static let pause = 1.4

    private final class Turn {
        let request = SFSpeechAudioBufferRecognitionRequest()
        var task: SFSpeechRecognitionTask?
        var text = ""
        /// Words from an earlier piece of the same question, when the recognizer split it.
        var prefix = ""
        var changed = CACurrentMediaTime()
        var firstWords: Double?
        /// A hands-free question: woken by "Hey Bluey", or a follow-up.
        var question = false
        var ended = false
        var finish: ((String) -> Void)?
    }

    private final class Feed: @unchecked Sendable {
        private let lock = NSLock()
        private var request: SFSpeechAudioBufferRecognitionRequest?

        func set(_ request: SFSpeechAudioBufferRecognitionRequest?) {
            lock.lock(); self.request = request; lock.unlock()
        }

        func append(_ buffer: AVAudioPCMBuffer) {
            lock.lock(); let request = request; lock.unlock()
            request?.append(buffer)
        }
    }

    /// Asks for speech recognition and microphone access. Calls back on the main queue with a problem, or nil.
    static func requestPermissions(_ done: @escaping (String?) -> Void) {
        SFSpeechRecognizer.requestAuthorization { status in
            guard status == .authorized else {
                DispatchQueue.main.async {
                    done("Speech recognition is off. Turn it on for Googly Eyes in System Settings › Privacy & Security › Speech Recognition.")
                }
                return
            }
            AVCaptureDevice.requestAccess(for: .audio) { granted in
                DispatchQueue.main.async {
                    done(granted ? nil : "The Mac's microphone is off. Turn it on for Googly Eyes in System Settings › Privacy & Security › Microphone.")
                }
            }
        }
    }

    /// Both permissions already given (checked without asking).
    static var permitted: Bool {
        SFSpeechRecognizer.authorizationStatus() == .authorized && AVCaptureDevice.authorizationStatus(for: .audio) == .authorized
    }

    /// Whether this Mac can recognize speech without sending audio anywhere (needed to listen while asleep).
    var onDevice: Bool { recognizer?.supportsOnDeviceRecognition == true }

    func start() throws {
        guard !running else { return }
        guard let recognizer, recognizer.isAvailable else {
            throw NSError(domain: "Googly", code: 1, userInfo: [NSLocalizedDescriptionKey: "Speech recognition isn't available on this Mac right now."])
        }
        let input = engine.inputNode
        let feed = feed
        input.installTap(onBus: 0, bufferSize: 1024, format: input.outputFormat(forBus: 0)) { buffer, _ in
            feed.append(buffer)
        }
        engine.prepare()
        try engine.start()
        running = true
        asking = false
        current = newTurn()
        let timer = Timer(timeInterval: 0.25, repeats: true) { [weak self] _ in
            MainActor.assumeIsolated { self?.checkPause() }
        }
        RunLoop.main.add(timer, forMode: .common)
        self.timer = timer
    }

    func stop() {
        guard running else { return }
        running = false
        timer?.invalidate()
        timer = nil
        feed.set(nil)
        engine.stop()
        engine.inputNode.removeTap(onBus: 0)
        current?.task?.cancel()
        current = nil
        ending.forEach { $0.task?.cancel() }
        ending = []
    }

    /// The phone is being held: what's said from now on is the question.
    func askStart() {
        guard running else { return }
        asking = true
        // Talk that's still going when you press is the start of the question (people speak as they press);
        // talk that had already paused was background.
        if let turn = current, !Self.words(turn).isEmpty, CACurrentMediaTime() - turn.changed > 1.0 { closeTurn() }
    }

    /// Let go: hands back the question once the recognizer has caught up.
    func askEnd(_ done: @escaping (String) -> Void) {
        guard running, let turn = current else { done(""); return }
        asking = false
        current = newTurn()
        end(turn) { text in
            // Holding counts as asking: drop a "Hey Bluey" they said out of habit.
            done(Self.afterWakePhrase(text) ?? text)
        }
    }

    // MARK: Turns

    private func newTurn() -> Turn {
        let turn = Turn()
        turn.request.shouldReportPartialResults = true
        turn.request.addsPunctuation = true
        turn.request.taskHint = .dictation
        if recognizer?.supportsOnDeviceRecognition == true { turn.request.requiresOnDeviceRecognition = true }
        feed.set(turn.request)
        turn.task = recognizer?.recognitionTask(with: turn.request) { [weak self, weak turn] result, error in
            let text = result?.bestTranscription.formattedString
            let final = result?.isFinal == true || error != nil
            DispatchQueue.main.async {
                MainActor.assumeIsolated {
                    guard let self, let turn else { return }
                    if let text, text != turn.text {
                        turn.text = text
                        turn.changed = CACurrentMediaTime()
                        if turn === self.current { self.noticeQuestion(in: turn) }
                    }
                    if final { self.finished(turn) }
                }
            }
        }
        return turn
    }

    /// Marks a turn as a question as soon as "Hey Bluey" is heard, or when talk starts in the follow-up window.
    private func noticeQuestion(in turn: Turn) {
        guard handsFree, !asking, !turn.question else { return }
        let now = CACurrentMediaTime()
        if turn.firstWords == nil, !Self.words(turn).isEmpty { turn.firstWords = now }
        let followUp = !wakeOnly && (turn.firstWords ?? 0) < max(followUpUntil, expectQuestionUntil)
        if Self.wakePhrase(in: Self.words(turn)) != nil || followUp {
            turn.question = true
            followUpUntil = 0
            expectQuestionUntil = 0
            onWake?()
        }
    }

    /// A pause after some talk closes the turn (not while he's being asked by holding).
    private func checkPause() {
        guard running, !asking, let turn = current, !Self.words(turn).isEmpty,
              CACurrentMediaTime() - turn.changed > Self.pause else { return }
        closeTurn()
    }

    private func closeTurn() {
        guard let turn = current else { return }
        current = newTurn()
        let question = turn.question
        end(turn) { [weak self] text in self?.conclude(text, question: question) }
    }

    /// A finished turn: background talk, or a hands-free question (the words after "Hey Bluey").
    private func conclude(_ text: String, question: Bool) {
        guard question else {
            if !text.isEmpty, !wakeOnly { onHeard?(text) }
            return
        }
        var asked = text
        if let range = Self.wakePhrase(in: text) {
            let before = String(text[..<range.lowerBound]).trimmingCharacters(in: .whitespacesAndNewlines)
            if !before.isEmpty, !wakeOnly { onHeard?(before) }
            asked = Self.clean(String(text[range.upperBound...]))
        }
        if asked.isEmpty {
            expectQuestionUntil = CACurrentMediaTime() + 6  // just "Hey Bluey": the next words are the question
        } else {
            onQuestion?(asked)
        }
    }

    /// Stops feeding a turn and waits (briefly) for its final words.
    private func end(_ turn: Turn, then done: @escaping (String) -> Void) {
        ending.append(turn)
        turn.ended = true
        turn.finish = done
        turn.request.endAudio()
        DispatchQueue.main.asyncAfter(deadline: .now() + 1.5) { [weak self, weak turn] in
            guard let self, let turn else { return }
            self.deliver(turn)
        }
    }

    private func finished(_ turn: Turn) {
        if turn.ended {
            deliver(turn)
        } else if turn === current, running {
            // The recognizer closed the turn by itself (a long pause, or a hiccup): keep its words and carry on.
            let text = Self.words(turn)
            let next = newTurn()
            current = next
            if asking {
                next.prefix = text
            } else if turn.question, Self.afterWakePhrase(text)?.isEmpty != false {
                // Cut off mid-question: the question carries on in the next turn.
                next.prefix = text
                next.question = true
            } else {
                conclude(text, question: turn.question)
            }
        }
    }

    private static func words(_ turn: Turn) -> String {
        [turn.prefix, turn.text.trimmingCharacters(in: .whitespacesAndNewlines)].filter { !$0.isEmpty }.joined(separator: " ")
    }

    private func deliver(_ turn: Turn) {
        ending.removeAll { $0 === turn }
        guard let finish = turn.finish else { return }
        turn.finish = nil
        turn.task?.cancel()
        finish(Self.words(turn))
    }

    // MARK: "Hey Bluey"

    /// "Bluey" as the recognizer tends to write it.
    private static let names = #"(?:bluey's|blueys|bluey|blue[- ]?e|bluie|bluee|blooey|blooie|bloo[- ]?ey|blewy|bluy|louie)"#
    /// "Hey Bluey" anywhere (with a greeting), or "Bluey, …" at the start of a turn.
    private static let wakePattern = try! NSRegularExpression(
        pattern: #"(?:\b(?:hey|hi|hay|hello|okay|ok|yo|oi)[\s,.!]+"# + names + #"\b|^\s*"# + names + #"\b)[\s,.!?:;-]*"#,
        options: [.caseInsensitive])

    /// Where "Hey Bluey" (and the punctuation after it) is in some text.
    static func wakePhrase(in text: String) -> Range<String.Index>? {
        let all = NSRange(text.startIndex..., in: text)
        guard let match = wakePattern.firstMatch(in: text, range: all) else { return nil }
        return Range(match.range, in: text)
    }

    /// The words after "Hey Bluey", or nil if it isn't there.
    static func afterWakePhrase(_ text: String) -> String? {
        guard let range = wakePhrase(in: text) else { return nil }
        return clean(String(text[range.upperBound...]))
    }

    private static func clean(_ text: String) -> String {
        var text = text.trimmingCharacters(in: .whitespacesAndNewlines.union(.punctuationCharacters))
        if let first = text.first { text = first.uppercased() + text.dropFirst() }
        return text
    }
}
