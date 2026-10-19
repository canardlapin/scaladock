import ApplicationServices
import Foundation
import AppKit

// Only inspect the explicitly supplied PID of the synthetic ScalaDock probe.
guard CommandLine.arguments.count >= 2, let pid = Int32(CommandLine.arguments[1]) else {
    fatalError("usage: ax_probe PID [ACTION NAME]")
}
guard AXIsProcessTrusted() else { fatalError("macOS Accessibility permission is required") }
let app = AXUIElementCreateApplication(pid)
func attr(_ node: AXUIElement, _ name: String) -> CFTypeRef? {
    var value: CFTypeRef?
    let result = AXUIElementCopyAttributeValue(node, name as CFString, &value)
    return result == .success ? value : nil
}
func actions(_ node: AXUIElement) -> [String] {
    var value: CFArray?
    _ = AXUIElementCopyActionNames(node, &value)
    return value as? [String] ?? []
}
func nodes(_ node: AXUIElement, _ depth: Int = 0) -> [(AXUIElement, [String: Any])] {
    if depth > 20 { return [] }
    var record: [String: Any] = ["depth": depth, "actions": actions(node)]
    for name in ["AXRole", "AXTitle", "AXDescription", "AXValue", "AXEnabled", "AXFocused"] {
        if let value = attr(node, name), value is String || value is NSNumber { record[name] = value }
    }
    var result = [(node, record)]
    for child in (attr(node, "AXChildren") as? [AXUIElement] ?? []) {
        result += nodes(child, depth + 1)
    }
    return result
}
let tree = (attr(app, "AXWindows") as? [AXUIElement] ?? []).flatMap { nodes($0) }
guard tree.contains(where: { ($0.1["AXTitle"] as? String) == "ScalaDock S4 synthetic accessibility probe" }) else {
    fatalError("PID does not expose the owned synthetic window")
}
if CommandLine.arguments.count == 4 {
    let action = CommandLine.arguments[2]
    let name = CommandLine.arguments[3]
    NSRunningApplication(processIdentifier: pid)?.activate(options: [])
    guard let target = tree.first(where: {
        ($0.1["AXTitle"] as? String == name || $0.1["AXDescription"] as? String == name) && actions($0.0).contains(action)
    }) else { fatalError("No accessible target for \(action): \(name)") }
    let result = AXUIElementPerformAction(target.0, action as CFString)
    guard result == .success else { fatalError("Action failed: \(result.rawValue)") }
    print("AX_ACTION_OK pid=\(pid) action=\(action) name=\(name)")
} else {
    let data = try JSONSerialization.data(withJSONObject: tree.map { $0.1 }, options: [.prettyPrinted, .sortedKeys])
    print(String(decoding: data, as: UTF8.self))
}
