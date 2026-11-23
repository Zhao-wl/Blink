import ActivityKit
import WidgetKit
import SwiftUI

@main
struct BlinkWidget: Widget {
    var body: some WidgetConfiguration {
        ActivityConfiguration(for:BlinkAttributes.self) { context in
            HStack {
                VStack(alignment:.leading) {
                    Text("Blink").font(.caption)
                    Text(context.state.title).font(.headline)
                }
                Spacer()
                counter(context.state.deadline)
            }.padding()
        } dynamicIsland: { context in
            DynamicIsland {
                DynamicIslandExpandedRegion(.leading) { Text(context.state.title) }
                DynamicIslandExpandedRegion(.trailing) { counter(context.state.deadline) }
            } compactLeading: { Image(systemName:"eye") }
              compactTrailing: { counter(context.state.deadline).frame(width:48) }
              minimal: { Image(systemName:"eye") }
        }
    }
    @ViewBuilder private func counter(_ end: Date) -> some View {
        Text(timerInterval:min(Date(),end)...end,countsDown:true).monospacedDigit()
    }
}
