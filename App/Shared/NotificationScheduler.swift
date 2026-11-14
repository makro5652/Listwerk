import Foundation
import UserNotifications
import ListwerkCore

/// Plant lokale Mitteilungen aus den Erinnerungen der Einträge (höchstens 64, siehe ReminderPlanner).
/// UNGETESTET auf Gerät.
enum NotificationScheduler {
    static func requestAuthorization() async {
        _ = try? await UNUserNotificationCenter.current().requestAuthorization(options: [.alert, .sound, .badge])
    }

    static func reschedule(records: [TaskRecord]) async {
        let center = UNUserNotificationCenter.current()
        center.removeAllPendingNotificationRequests()
        let plan = ReminderPlanner.plan(records: records, now: Date())
        for p in plan {
            let content = UNMutableNotificationContent()
            content.title = p.title
            content.body = p.body
            content.sound = .default
            content.userInfo = ["list": p.listHref, "uid": p.uid]
            let comps = Calendar.current.dateComponents([.year, .month, .day, .hour, .minute, .second], from: p.fireDate)
            let trigger = UNCalendarNotificationTrigger(dateMatching: comps, repeats: false)
            try? await center.add(UNNotificationRequest(identifier: p.id, content: content, trigger: trigger))
        }
    }
}
