import Capacitor
import ActivityKit
import UIKit

class BlinkViewController: CAPBridgeViewController {
    override func capacitorDidLoad() { bridge?.registerPluginInstance(BlinkLive()) }
}

@objc(BlinkLive)
public class BlinkLive: CAPPlugin, CAPBridgedPlugin {
    public let identifier = "BlinkLive"
    public let jsName = "BlinkLive"
    public let pluginMethods = [CAPPluginMethod(name: "setTimer",returnType: CAPPluginReturnPromise)]
    @objc func setTimer(_ call: CAPPluginCall) {
        guard #available(iOS 16.2, *) else { call.resolve(); return }
        let deadline = Date(timeIntervalSince1970:(call.getDouble("at") ?? 0)/1000)
        let title = call.getString("title") ?? "提醒"
        Task { @MainActor in
            let activities = Activity<BlinkAttributes>.activities
            guard deadline > Date(), ActivityAuthorizationInfo().areActivitiesEnabled else {
                for activity in activities { await activity.end(nil,dismissalPolicy:.immediate) }
                call.resolve(); return
            }
            let content = ActivityContent(state:BlinkAttributes.ContentState(title:title,deadline:deadline),staleDate:deadline)
            if let active = activities.first { await active.update(content) }
            else if UIApplication.shared.applicationState == .active {
                do { _ = try Activity.request(attributes:BlinkAttributes(appName:"Blink"),content:content,pushType:nil) }
                catch { call.reject(error.localizedDescription);return }
            }
            call.resolve()
        }
    }
}
