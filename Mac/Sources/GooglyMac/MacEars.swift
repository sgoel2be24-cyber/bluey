import AVFoundation
import Speech

/// Listens through the Mac's microphone with Apple's speech recognition (on-device when available),
/// one turn at a time. A pause closes a turn of overheard talk; holding the phone marks the question.
/// Used when Fireworks is his brain, since Fireworks has no speech-to-text of its own.
@MainActor
final class MacEars {
    /// A finished turn of talk he overheard (background context).
    var onHeard: ((String) -> Void)?

    private let engine = AVAudioEngine()
    private let recognizer = SFSpeechRecognizer(locale: Locale.current) ?? SFSpeechRecognizer(locale: Locale(identifier: "en-US"))
    /// The request the microphone feeds right now. Swapped at every turn; read from the audio thread.
    private let feed = Feed()
    private var current: Turn?
    /// Turns that have stopped listening and are waiting for their final words.
    private var ending: [Turn] = []
    private var timer: Timer?
    private var asking = false
    private(set) var running = false

    /// How long a pause closes a turn of overheard talk.
    private static let pause = 1.4

    private final class Turn {
        let request = SFSpeechAudioBufferRecognitionRequest()
        var task: SFSpeechRecognitionTask?
        var text = ""
        /// Words from an earlier piece of the same question, when the recognizer split it.
        var prefix = ""
        var changed = CACurrentMediaTime()
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
        if let turn = current, !Self.words(turn).isEmpty { closeTurn() }
    }

    /// Let go: hands back the question once the recognizer has caught up.
    func askEnd(_ done: @escaping (String) -> Void) {
        guard running, let turn = current else { done(""); return }
        asking = false
        current = newTurn()
        end(turn, then: done)
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
                    }
                    if final { self.finished(turn) }
                }
            }
        }
        return turn
    }

    /// A pause after some talk closes the turn as background context (not while he's being asked).
    private func checkPause() {
        guard running, !asking, let turn = current, !Self.words(turn).isEmpty,
              CACurrentMediaTime() - turn.changed > Self.pause else { return }
        closeTurn()
    }

    private func closeTurn() {
        guard let turn = current else { return }
        current = newTurn()
        end(turn) { [weak self] text in
            if !text.isEmpty { self?.onHeard?(text) }
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
            if asking { next.prefix = text } else if !text.isEmpty { onHeard?(text) }
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
}
