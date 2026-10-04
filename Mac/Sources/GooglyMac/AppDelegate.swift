import AppKit
import Carbon.HIToolbox
import GooglyShared

@MainActor
final class AppDelegate: NSObject, NSApplicationDelegate, NSMenuDelegate {
    private let settings = Settings.shared
    private let overlay = CursorOverlay()
    private let server = PhoneServer()
    private var statusItem: NSStatusItem!
    private lazy var host = RealtimeHost(overlay: overlay)


    func applicationDidFinishLaunching(_ notification: Notification) {
        Fonts.registerBundled()
        addEditMenu()
        statusItem = NSStatusBar.system.statusItem(withLength: NSStatusItem.variableLength)
        statusItem.button?.image = MenuIcon.make()
        statusItem.button?.toolTip = "Googly Eyes"
        let menu = NSMenu()
        menu.delegate = self
        statusItem.menu = menu

        overlay.onFace = { [weak self] face in self?.server.send(face) }
        overlay.start()
        showDemoBubbleIfAsked()
        checkPermissions()
        if let question = ProcessInfo.processInfo.environment["GOOGLY_DEMO_RESEARCH"] {
            host.demoResearch(question, out: ProcessInfo.processInfo.environment["GOOGLY_DEMO_OUT"] ?? "/tmp/googly-report")
        }
        server.onPhonesChanged = { [weak self] names in
            self?.refreshIcon()
            if names.isEmpty, self?.host.awake == true { self?.host.setAwake(false) }
        }
        server.onRequest = { [weak self] packet, reply in self?.host.handle(packet, reply: reply) }
        host.brain.toPhone = { [weak self] packet in self?.server.broadcast(packet) }
        server.start()
        host.onChange = { [weak self] in self?.refreshIcon() }

        // ⌥Space wakes him up or puts him back to sleep (same as double tapping him on the phone).
        HotKeys.shared.register(keyCode: kVK_Space, modifiers: optionKey) { [weak self] in self?.toggleAwake() }
        // ⌃⌥P point here, ⌃⌥F follow mouse, ⌃⌥D stop pointing, ⌃⌥H hide, ⌃⌥T talk test.
        HotKeys.shared.register(keyCode: kVK_ANSI_P) { [weak self] in self?.pointHere() }
        HotKeys.shared.register(keyCode: kVK_ANSI_F) { [weak self] in self?.toggleFollow() }
        HotKeys.shared.register(keyCode: kVK_ANSI_D) { [weak self] in self?.overlay.goHome() }
        HotKeys.shared.register(keyCode: kVK_ANSI_H) { [weak self] in self?.toggleShow() }
        HotKeys.shared.register(keyCode: kVK_ANSI_T) { [weak self] in self?.overlay.talkTest() }
        // ⌃⌥S stops him using the computer right away.
        HotKeys.shared.register(keyCode: kVK_ANSI_S) { [weak self] in self?.stopActions() }

        DispatchQueue.main.asyncAfter(deadline: .now() + 0.5) { [weak self] in self?.askForKeyIfNeeded() }
    }

    /// A menu bar app has no menus of its own, so ⌘V, ⌘C and ⌘A wouldn't work in the key boxes without this
    /// (never shown, it just carries the shortcuts).
    private func addEditMenu() {
        let edit = NSMenu(title: "Edit")
        edit.addItem(withTitle: "Cut", action: #selector(NSText.cut(_:)), keyEquivalent: "x")
        edit.addItem(withTitle: "Copy", action: #selector(NSText.copy(_:)), keyEquivalent: "c")
        edit.addItem(withTitle: "Paste", action: #selector(NSText.paste(_:)), keyEquivalent: "v")
        edit.addItem(withTitle: "Select All", action: #selector(NSText.selectAll(_:)), keyEquivalent: "a")
        edit.addItem(withTitle: "Undo", action: Selector(("undo:")), keyEquivalent: "z")
        let main = NSMenu()
        let item = NSMenuItem()
        item.submenu = edit
        main.addItem(item)
        NSApp.mainMenu = main
    }

    /// First run: pick a brain and add its key.
    private func askForKeyIfNeeded() {
        switch settings.brain {
        case .fireworks where Keychain.get(.fireworks) == nil:
            editFireworksKey()
        case .openai where Keychain.get(.openai) == nil:
            if Keychain.get(.fireworks) != nil { settings.brain = .fireworks; return }
            NSApp.activate(ignoringOtherApps: true)
            let alert = NSAlert()
            alert.messageText = "Which AI should be his brain?"
            alert.informativeText = """
            OpenAI: realtime voice on the phone, plus web research.
            Fireworks: the Mac listens with Apple's speech recognition and a Fireworks model thinks. No web research.
            You can change this any time under Brain in the menu bar.
            """
            alert.addButton(withTitle: "Fireworks")
            alert.addButton(withTitle: "OpenAI")
            if alert.runModal() == .alertFirstButtonReturn {
                settings.brain = .fireworks
                editFireworksKey()
            } else {
                editKeys()
            }
        default:
            break
        }
    }

    private func refreshIcon() {
        statusItem.button?.appearsDisabled = server.phoneNames.isEmpty
    }

    // MARK: Actions

    @objc private func pointHere() {
        let m = NSEvent.mouseLocation
        guard let screen = NSScreen.screens.first else { return }
        overlay.mode = .pinned(CGPoint(x: m.x - screen.frame.minX, y: screen.frame.maxY - m.y))
    }

    @objc private func toggleFollow() {
        settings.followMouse.toggle()
        overlay.goHome()
    }

    @objc private func dock() { overlay.goHome() }

    /// For checking the speech bubble's look: GOOGLY_DEMO_BUBBLE=top|middle|home shows a sample reply.
    private func showDemoBubbleIfAsked() {
        guard let where_ = ProcessInfo.processInfo.environment["GOOGLY_DEMO_BUBBLE"],
              let size = NSScreen.screens.first?.frame.size else { return }
        let y: CGFloat = where_ == "top" ? 40 : size.height * 0.55
        let thing = CGRect(x: size.width * 0.3, y: y, width: 90, height: 22)
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.5) {
            if where_ != "home" {
                self.overlay.mode = .pinned(CGPoint(x: thing.midX, y: thing.maxY + 3))
                self.overlay.view.speechTarget = thing
            }
            self.overlay.view.caption = ProcessInfo.processInfo.environment["GOOGLY_DEMO_TEXT"]
                ?? "That's the prompt box, where you type to Claude. Cheeky little thing."
        }
        // GOOGLY_DEMO_OUT=path.png saves a picture of the overlay (on dark grey) and quits.
        if let out = ProcessInfo.processInfo.environment["GOOGLY_DEMO_OUT"] {
            DispatchQueue.main.asyncAfter(deadline: .now() + 2.5) {
                let view = self.overlay.view
                let scale: CGFloat = 1
                let w = Int(view.bounds.width * scale), h = Int(view.bounds.height * scale)
                guard let ctx = CGContext(data: nil, width: w, height: h, bitsPerComponent: 8, bytesPerRow: 0,
                                          space: CGColorSpaceCreateDeviceRGB(),
                                          bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue) else { exit(1) }
                ctx.setFillColor(NSColor(white: 0.12, alpha: 1).cgColor)
                ctx.fill(CGRect(x: 0, y: 0, width: w, height: h))
                ctx.setFillColor(NSColor(white: 0.5, alpha: 1).cgColor)
                ctx.fill(CGRect(x: thing.minX, y: view.bounds.height - thing.maxY, width: thing.width, height: thing.height))
                ctx.scaleBy(x: scale, y: scale)
                view.layer?.presentation()?.render(in: ctx) ?? view.layer?.render(in: ctx)
                if let image = ctx.makeImage() {
                    let rep = NSBitmapImageRep(cgImage: image)
                    try? rep.representation(using: .png, properties: [:])?.write(to: URL(fileURLWithPath: out))
                }
                exit(0)
            }
        }
    }
    @objc private func toggleShow() { settings.showCursor.toggle() }
    @objc private func toggleGlow() { settings.glow.toggle() }
    @objc private func setTrail(_ item: NSMenuItem) {
        if let raw = item.representedObject as? String, let trail = PointerTrail(rawValue: raw) { settings.trail = trail }
    }
    @objc private func talkTest() { overlay.talkTest() }

    @objc private func setSize(_ item: NSMenuItem) { settings.cursorSize = Double(item.tag) }
    @objc private func setPhonePosition(_ item: NSMenuItem) { settings.phonePosition = Double(item.tag) / 100 }
    @objc private func toggleAwake() {
        server.broadcast(Packet(command: host.awake ? "sleep" : "wake"))
    }

    /// On launch, asks for whatever he still needs (so Googly Eyes shows up in those Settings lists),
    /// and keeps a small status file so it's easy to check what's allowed.
    private func checkPermissions() {
        guard ProcessInfo.processInfo.environment["GOOGLY_DEMO_OUT"] == nil else { return }
        if settings.computerControl && !ComputerControl.isTrusted { ComputerControl.askForPermission() }
        if !CGPreflightScreenCaptureAccess() { CGRequestScreenCaptureAccess() }
        let status = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("Googly/status.txt")
        Timer.scheduledTimer(withTimeInterval: 3, repeats: true) { _ in
            let text = "accessibility=\(ComputerControl.isTrusted) screenRecording=\(CGPreflightScreenCaptureAccess()) computerControl=\(Settings.shared.computerControl)\n"
            try? text.write(to: status, atomically: true, encoding: .utf8)
        }.fire()
    }

    @objc private func stopActions() {
        host.stopActions()
    }

    @objc private func toggleComputerControl() {
        settings.computerControl.toggle()
        if settings.computerControl && !ComputerControl.isTrusted { ComputerControl.askForPermission() }
        restartIfAwake()  // his tools change with this switch
    }

    @objc private func allowComputerControl() {
        ComputerControl.askForPermission()
        ComputerControl.openAccessibilitySettings()
    }

    /// Wakes him again so a new personality or set of tools takes effect right away.
    private func restartIfAwake() {
        guard host.awake else { return }
        server.broadcast(Packet(command: "sleep"))
        DispatchQueue.main.asyncAfter(deadline: .now() + 1) { self.server.broadcast(Packet(command: "wake")) }
    }

    @objc private func editPersonality() {
        NSApp.activate(ignoringOtherApps: true)
        let alert = NSAlert()
        alert.messageText = "His Personality"
        alert.informativeText = "Describe who he is and how he talks. How he looks at and points at your screen stays built in."
        let scroll = NSScrollView(frame: NSRect(x: 0, y: 0, width: 480, height: 220))
        scroll.hasVerticalScroller = true
        scroll.borderType = .bezelBorder
        let text = NSTextView(frame: scroll.bounds)
        text.autoresizingMask = [.width]
        text.isRichText = false
        text.font = NSFont.systemFont(ofSize: 13)
        text.string = RealtimeHost.personality
        text.textContainerInset = NSSize(width: 6, height: 6)
        scroll.documentView = text
        alert.accessoryView = scroll
        alert.addButton(withTitle: "Save")
        alert.addButton(withTitle: "Cancel")
        alert.addButton(withTitle: "Reset to Default")
        alert.window.initialFirstResponder = text
        switch alert.runModal() {
        case .alertFirstButtonReturn: RealtimeHost.personality = text.string
        case .alertThirdButtonReturn: RealtimeHost.personality = ""
        default: return
        }
        restartIfAwake()
    }

    @objc private func editKeys() {
        NSApp.activate(ignoringOtherApps: true)
        let alert = NSAlert()
        alert.messageText = "OpenAI API Key"
        alert.informativeText = "Saved privately on this Mac (not in the project files). The phone only ever gets short-lived keys."
        let field = NSSecureTextField(frame: NSRect(x: 0, y: 0, width: 360, height: 24))
        field.placeholderString = Keychain.get(.openai) == nil ? "sk-…" : "Key saved. Paste a new one to replace it."
        alert.accessoryView = field
        alert.addButton(withTitle: "Save")
        alert.addButton(withTitle: "Cancel")
        alert.window.initialFirstResponder = field
        guard alert.runModal() == .alertFirstButtonReturn else { return }
        let key = field.stringValue.trimmingCharacters(in: .whitespacesAndNewlines)
        if !key.isEmpty { Keychain.set(.openai, key) }
    }

    @objc private func editFireworksKey() {
        NSApp.activate(ignoringOtherApps: true)
        let alert = NSAlert()
        alert.messageText = "Fireworks API Key"
        alert.informativeText = "Saved privately on this Mac (not in the project files). It never leaves the Mac: with Fireworks, the Mac does the listening and thinking."
        let field = NSSecureTextField(frame: NSRect(x: 0, y: 0, width: 360, height: 24))
        field.placeholderString = Keychain.get(.fireworks) == nil ? "fw_…" : "Key saved. Paste a new one to replace it."
        alert.accessoryView = field
        alert.addButton(withTitle: "Save")
        alert.addButton(withTitle: "Cancel")
        alert.window.initialFirstResponder = field
        guard alert.runModal() == .alertFirstButtonReturn else { return }
        let key = field.stringValue.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !key.isEmpty else { return }
        Keychain.set(.fireworks, key)
        host.brain.resetModels()
        Task { await host.brain.loadModels() }
    }

    @objc private func setBrain(_ item: NSMenuItem) {
        guard let raw = item.representedObject as? String, let brain = Settings.Brain(rawValue: raw), brain != settings.brain else { return }
        settings.brain = brain
        if brain == .fireworks, Keychain.get(.fireworks) == nil { editFireworksKey() }
        if brain == .openai, Keychain.get(.openai) == nil { editKeys() }
        restartIfAwake()
    }

    @objc private func setFireworksModel(_ item: NSMenuItem) {
        settings.fireworksModel = item.representedObject as? String
        restartIfAwake()
    }

    @objc private func setMood(_ item: NSMenuItem) {
        if let mood = item.representedObject as? String, let m = Mood(rawValue: mood) { settings.mood = m }
    }

    // MARK: Menu

    func menuNeedsUpdate(_ menu: NSMenu) {
        menu.removeAllItems()

        let phones = server.phoneNames
        let status = NSMenuItem(title: phones.isEmpty ? "Waiting for the phone app…" : "Phone connected: \(phones.joined(separator: ", "))",
                                action: nil, keyEquivalent: "")
        status.isEnabled = false
        menu.addItem(status)
        let wake = NSMenuItem(title: host.awake ? "Go to Sleep (Follow Mode)" : "Wake Up and Talk",
                              action: #selector(toggleAwake), keyEquivalent: " ")
        wake.keyEquivalentModifierMask = [.option]
        wake.target = self
        wake.isEnabled = !phones.isEmpty
        menu.addItem(wake)
        menu.addItem(.separator())

        let control = item("Let Him Use the Computer", #selector(toggleComputerControl))
        control.state = settings.computerControl ? .on : .off
        menu.addItem(control)
        if settings.computerControl && !ComputerControl.isTrusted {
            menu.addItem(item("Allow Clicking and Typing (Accessibility)…", #selector(allowComputerControl)))
        }
        menu.addItem(item("Stop Him", #selector(stopActions), key: "s"))
        menu.addItem(.separator())

        let point = item("Point Here", #selector(pointHere), key: "p")
        menu.addItem(point)
        let follow = item("Eyes Follow My Mouse", #selector(toggleFollow), key: "f")
        follow.state = settings.followMouse ? .on : .off
        menu.addItem(follow)
        menu.addItem(item("Stop Pointing", #selector(dock), key: "d"))
        menu.addItem(item("Talk Test", #selector(talkTest), key: "t"))
        menu.addItem(.separator())

        let moodMenu = NSMenu()
        for mood in Mood.allCases where mood != .talking && mood != .pointing && mood != .sleepy {
            let m = item(mood.title, #selector(setMood(_:)))
            m.representedObject = mood.rawValue
            m.state = settings.mood == mood ? .on : .off
            moodMenu.addItem(m)
        }
        menu.addItem(submenu("Mood", moodMenu))

        let sizeMenu = NSMenu()
        for (name, size) in [("Small", 48), ("Medium", 72), ("Large", 96), ("Huge", 120)] {
            let s = item("\(name) (\(size) pt)", #selector(setSize(_:)))
            s.tag = size
            s.state = Int(settings.cursorSize) == size ? .on : .off
            sizeMenu.addItem(s)
        }
        menu.addItem(submenu("Cursor Size", sizeMenu))

        let phoneMenu = NSMenu()
        for (name, pos) in [("Left", 20), ("Center", 50), ("Right", 80)] {
            let p = item(name, #selector(setPhonePosition(_:)))
            p.tag = pos
            p.state = Int((settings.phonePosition * 100).rounded()) == pos ? .on : .off
            phoneMenu.addItem(p)
        }
        menu.addItem(submenu("Phone Sits Under", phoneMenu))

        menu.addItem(item("Personality…", #selector(editPersonality)))

        let brainMenu = NSMenu()
        for (title, brain) in [("OpenAI (Realtime Voice)", Settings.Brain.openai), ("Fireworks", .fireworks)] {
            let b = item(title, #selector(setBrain(_:)))
            b.representedObject = brain.rawValue
            b.state = settings.brain == brain ? .on : .off
            brainMenu.addItem(b)
        }
        menu.addItem(submenu("Brain: \(settings.brain == .fireworks ? "Fireworks" : "OpenAI")", brainMenu))
        if settings.brain == .fireworks {
            menu.addItem(submenu("Fireworks Model", fireworksModelMenu()))
            menu.addItem(item("Fireworks Key…", #selector(editFireworksKey)))
        }
        menu.addItem(item("OpenAI Key…", #selector(editKeys)))
        menu.addItem(.separator())

        let trailMenu = NSMenu()
        for trail in PointerTrail.allCases {
            let t = item(trail.title, #selector(setTrail(_:)))
            t.representedObject = trail.rawValue
            t.state = settings.trail == trail ? .on : .off
            trailMenu.addItem(t)
        }
        menu.addItem(submenu("Pointer Trail", trailMenu))

        let glow = item("Cursor Glow", #selector(toggleGlow))
        glow.state = settings.glow ? .on : .off
        menu.addItem(glow)
        let show = item("Show Cursor", #selector(toggleShow), key: "h")
        show.state = settings.showCursor ? .on : .off
        menu.addItem(show)

        menu.addItem(.separator())
        menu.addItem(NSMenuItem(title: "Quit Googly Eyes", action: #selector(NSApplication.terminate(_:)), keyEquivalent: "q"))
    }

    /// Serverless Fireworks models that can see screenshots and call tools.
    private func fireworksModelMenu() -> NSMenu {
        let menu = NSMenu()
        let picked = settings.fireworksModel
        let auto = item("Automatic (Best Available)", #selector(setFireworksModel(_:)))
        auto.state = picked == nil ? .on : .off
        menu.addItem(auto)
        menu.addItem(.separator())
        let models = host.brain.models
        if models.isEmpty {
            let note = NSMenuItem(title: Keychain.get(.fireworks) == nil ? "Add a Fireworks key first" : "Loading models… (reopen this menu)",
                                  action: nil, keyEquivalent: "")
            note.isEnabled = false
            menu.addItem(note)
            if Keychain.get(.fireworks) != nil { Task { await host.brain.loadModels() } }
        }
        for model in models {
            let m = item(model.name, #selector(setFireworksModel(_:)))
            m.representedObject = model.id
            m.state = picked == model.id ? .on : .off
            menu.addItem(m)
        }
        if let picked, !models.contains(where: { $0.id == picked }) {
            let m = item(picked.components(separatedBy: "/").last ?? picked, #selector(setFireworksModel(_:)))
            m.representedObject = picked
            m.state = .on
            menu.addItem(m)
        }
        return menu
    }

    /// Menu item that shows its global shortcut (⌃⌥ + key) when given one.
    private func item(_ title: String, _ action: Selector, key: String = "") -> NSMenuItem {
        let item = NSMenuItem(title: title, action: action, keyEquivalent: key)
        item.keyEquivalentModifierMask = [.control, .option]
        item.target = self
        return item
    }

    private func submenu(_ title: String, _ menu: NSMenu) -> NSMenuItem {
        let item = NSMenuItem(title: title, action: nil, keyEquivalent: "")
        item.submenu = menu
        return item
    }
}

/// The little blueberry blob with eyes for the menu bar.
enum MenuIcon {
    static func make() -> NSImage {
        let image = NSImage(size: NSSize(width: 22, height: 18), flipped: true) { rect in
            let body = NSBezierPath(ovalIn: NSRect(x: 1, y: 2, width: 20, height: 16))
            NSGradient(colors: Palette.gradient.map { NSColor(hex: $0) })?.draw(in: body, angle: -60)
            NSColor.white.setFill()
            NSBezierPath(ovalIn: NSRect(x: 5, y: 6, width: 5.5, height: 5.5)).fill()
            NSBezierPath(ovalIn: NSRect(x: 11.5, y: 6, width: 5.5, height: 5.5)).fill()
            NSColor(hex: Palette.ink).setFill()
            NSBezierPath(ovalIn: NSRect(x: 6.3, y: 6.8, width: 3, height: 3)).fill()
            NSBezierPath(ovalIn: NSRect(x: 12.8, y: 6.8, width: 3, height: 3)).fill()
            return true
        }
        image.isTemplate = false
        return image
    }
}
