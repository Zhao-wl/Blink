import ActivityKit
import Foundation

@available(iOS 16.2, *)
struct BlinkAttributes: ActivityAttributes {
    struct ContentState: Codable, Hashable {
        var title: String
        var deadline: Date
    }
    var appName: String
}
