import AppKit
// Vector-drawn graph mark; raster sizes are generated for macOS icon packaging.
let output = CommandLine.arguments[1]
try FileManager.default.createDirectory(atPath: output, withIntermediateDirectories: true)
for (size, name) in [(16,"icon_16x16"),(32,"icon_16x16@2x"),(32,"icon_32x32"),(64,"icon_32x32@2x"),(128,"icon_128x128"),(256,"icon_128x128@2x"),(256,"icon_256x256"),(512,"icon_256x256@2x"),(512,"icon_512x512"),(1024,"icon_512x512@2x")] {
    let image = NSImage(size: NSSize(width: size, height: size))
    image.lockFocus()
    let scale = CGFloat(size) / 1024
    let transform = NSAffineTransform()
    transform.scale(by: scale)
    transform.concat()
    let background = NSBezierPath(roundedRect: NSRect(x: 32, y: 32, width: 960, height: 960), xRadius: 216, yRadius: 216)
    NSGradient(starting: NSColor(red: 0.07, green: 0.17, blue: 0.34, alpha: 1), ending: NSColor(red: 0.14, green: 0.40, blue: 0.60, alpha: 1))!.draw(in: background, angle: 45)
    NSColor(red: 0.36, green: 0.87, blue: 0.78, alpha: 1).setStroke()
    let links = NSBezierPath()
    links.lineWidth = 44; links.lineCapStyle = .round; links.lineJoinStyle = .round
    links.move(to: NSPoint(x: 230, y: 512)); links.line(to: NSPoint(x: 490, y: 740)); links.line(to: NSPoint(x: 794, y: 512))
    links.move(to: NSPoint(x: 230, y: 512)); links.line(to: NSPoint(x: 490, y: 284)); links.line(to: NSPoint(x: 794, y: 512)); links.stroke()
    for point in [NSPoint(x: 230, y: 512),NSPoint(x: 490, y: 740),NSPoint(x: 490, y: 284),NSPoint(x: 794, y: 512)] {
        NSColor.white.setFill(); NSBezierPath(ovalIn: NSRect(x: point.x - 62, y: point.y - 62, width: 124, height: 124)).fill()
    }
    image.unlockFocus()
    let rep = NSBitmapImageRep(data: image.tiffRepresentation!)!
    try rep.representation(using: .png, properties: [:])!.write(to: URL(fileURLWithPath: "\(output)/\(name).png"))
}
