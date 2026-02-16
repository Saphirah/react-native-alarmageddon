import Foundation
import React
import UserNotifications

@objc(AlarmModule)
class AlarmModule: RCTEventEmitter {
    
    private static let PREFS_KEY = "rn_alarm_module_alarms"
    
    override init() {
        super.init()
    }
    
    @objc override static func requiresMainQueueSetup() -> Bool {
        return true
    }
    
    override func supportedEvents() -> [String]! {
        return ["activeAlarmId"]
    }
    
    // MARK: - Permission Methods
    
    @objc(requestPermissions:rejecter:)
    func requestPermissions(_ resolve: @escaping RCTPromiseResolveBlock,
                            rejecter reject: @escaping RCTPromiseRejectBlock) {
        UNUserNotificationCenter.current().requestAuthorization(options: [.alert, .sound, .badge]) { granted, error in
            if let error = error {
                reject("PERMISSION_ERROR", error.localizedDescription, error)
                return
            }
            resolve(["granted": granted])
        }
    }
    
    // MARK: - Alarm Scheduling
    
    @objc(scheduleAlarm:resolver:rejecter:)
    func scheduleAlarm(_ alarm: NSDictionary,
                       resolver resolve: @escaping RCTPromiseResolveBlock,
                       rejecter reject: @escaping RCTPromiseRejectBlock) {
        guard let id = alarm["id"] as? String,
              let datetimeISO = alarm["datetimeISO"] as? String else {
            reject("INVALID_PARAMS", "id and datetimeISO are required", nil)
            return
        }
        
        let title = alarm["title"] as? String ?? "Alarm"
        let body = alarm["body"] as? String ?? ""
        let snoozeEnabled = alarm["snoozeEnabled"] as? Bool ?? true
        let snoozeInterval = alarm["snoozeInterval"] as? Int ?? 5
        let repeatFrequency = alarm["repeatFrequency"] as? Int ?? -1
        
        // Parse ISO date
        let dateFormatter = ISO8601DateFormatter()
        dateFormatter.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        
        var triggerDate: Date?
        triggerDate = dateFormatter.date(from: datetimeISO)
        
        // Try without fractional seconds if first parse fails
        if triggerDate == nil {
            dateFormatter.formatOptions = [.withInternetDateTime]
            triggerDate = dateFormatter.date(from: datetimeISO)
        }
        
        // Try basic format
        if triggerDate == nil {
            let basicFormatter = DateFormatter()
            basicFormatter.dateFormat = "yyyy-MM-dd'T'HH:mm:ss"
            basicFormatter.locale = Locale(identifier: "en_US_POSIX")
            triggerDate = basicFormatter.date(from: datetimeISO)
        }
        
        guard let date = triggerDate else {
            reject("INVALID_DATE", "Could not parse datetimeISO: \(datetimeISO)", nil)
            return
        }
        
        // Create notification content
        let content = UNMutableNotificationContent()
        content.title = title
        content.body = body
        content.sound = UNNotificationSound.default
        content.categoryIdentifier = snoozeEnabled ? "ALARM_CATEGORY_WITH_SNOOZE" : "ALARM_CATEGORY"
        content.userInfo = [
            "alarmId": id,
            "snoozeEnabled": snoozeEnabled,
            "snoozeInterval": snoozeInterval,
            "repeatFrequency": repeatFrequency
        ]
        
        // Register notification category with appropriate actions
        registerNotificationCategory(snoozeEnabled: snoozeEnabled)
        
        // Create trigger based on repeatFrequency
        let trigger: UNNotificationTrigger
        let calendar = Calendar.current
        
        if repeatFrequency >= 0 {
            // Repeating alarm
            var components: DateComponents
            switch repeatFrequency {
            case 0: // HOURLY
                components = calendar.dateComponents([.minute, .second], from: date)
            case 1: // DAILY
                components = calendar.dateComponents([.hour, .minute, .second], from: date)
            case 2: // WEEKLY
                components = calendar.dateComponents([.weekday, .hour, .minute, .second], from: date)
            default: // Invalid values default to DAILY behavior
                components = calendar.dateComponents([.hour, .minute, .second], from: date)
            }
            trigger = UNCalendarNotificationTrigger(dateMatching: components, repeats: true)
        } else {
            // One-time alarm
            let components = calendar.dateComponents([.year, .month, .day, .hour, .minute, .second], from: date)
            trigger = UNCalendarNotificationTrigger(dateMatching: components, repeats: false)
        }
        
        // Create request
        let request = UNNotificationRequest(identifier: id, content: content, trigger: trigger)
        
        UNUserNotificationCenter.current().add(request) { error in
            if let error = error {
                reject("SCHEDULE_ERROR", error.localizedDescription, error)
                return
            }
            
            // Save alarm to UserDefaults
            self.saveAlarm(id: id, datetimeISO: datetimeISO, title: title, body: body, snoozeEnabled: snoozeEnabled, snoozeInterval: snoozeInterval, repeatFrequency: repeatFrequency)
            resolve(nil)
        }
    }
    
    private func registerNotificationCategory(snoozeEnabled: Bool) {
        let stopAction = UNNotificationAction(
            identifier: "STOP_ACTION",
            title: "Stop",
            options: [.destructive, .foreground]
        )
        
        if snoozeEnabled {
            let snoozeAction = UNNotificationAction(
                identifier: "SNOOZE_ACTION",
                title: "Snooze",
                options: []
            )
            
            let categoryWithSnooze = UNNotificationCategory(
                identifier: "ALARM_CATEGORY_WITH_SNOOZE",
                actions: [stopAction, snoozeAction],
                intentIdentifiers: [],
                options: [.customDismissAction]
            )
            UNUserNotificationCenter.current().setNotificationCategories([categoryWithSnooze])
        } else {
            let categoryWithoutSnooze = UNNotificationCategory(
                identifier: "ALARM_CATEGORY",
                actions: [stopAction],
                intentIdentifiers: [],
                options: [.customDismissAction]
            )
            UNUserNotificationCenter.current().setNotificationCategories([categoryWithoutSnooze])
        }
    }
    
    @objc(cancelAlarm:resolver:rejecter:)
    func cancelAlarm(_ id: String,
                     resolver resolve: @escaping RCTPromiseResolveBlock,
                     rejecter reject: @escaping RCTPromiseRejectBlock) {
        UNUserNotificationCenter.current().removePendingNotificationRequests(withIdentifiers: [id])
        removeAlarm(id: id)
        resolve(nil)
    }
    
    @objc(listAlarms:rejecter:)
    func listAlarms(_ resolve: @escaping RCTPromiseResolveBlock,
                    rejecter reject: @escaping RCTPromiseRejectBlock) {
        let alarms = getAlarms()
        resolve(alarms)
    }
    
    // MARK: - Snooze Methods
    
    @objc(snoozeAlarm:minutes:resolver:rejecter:)
    func snoozeAlarm(_ id: String,
                     minutes: Int,
                     resolver resolve: @escaping RCTPromiseResolveBlock,
                     rejecter reject: @escaping RCTPromiseRejectBlock) {
        // Create a new alarm in X minutes
        let content = UNMutableNotificationContent()
        content.title = "Snoozed Alarm"
        content.body = id
        content.sound = UNNotificationSound.default
        content.categoryIdentifier = "ALARM_CATEGORY"
        
        let trigger = UNTimeIntervalNotificationTrigger(timeInterval: TimeInterval(minutes * 60), repeats: false)
        let request = UNNotificationRequest(identifier: UUID().uuidString, content: content, trigger: trigger)
        
        UNUserNotificationCenter.current().add(request) { error in
            if let error = error {
                reject("SNOOZE_ERROR", error.localizedDescription, error)
                return
            }
            resolve(nil)
        }
    }
    
    // MARK: - Current Alarm Methods
    
    @objc(stopCurrentAlarm:resolver:rejecter:)
    func stopCurrentAlarm(_ id: String,
                          resolver resolve: @escaping RCTPromiseResolveBlock,
                          rejecter reject: @escaping RCTPromiseRejectBlock) {
        // iOS doesn't have persistent alarm sounds like Android
        // Remove the delivered notification
        UNUserNotificationCenter.current().removeDeliveredNotifications(withIdentifiers: [id])
        resolve(nil)
    }
    
    @objc(snoozeCurrentAlarm:minutes:resolver:rejecter:)
    func snoozeCurrentAlarm(_ id: String,
                            minutes: Int,
                            resolver resolve: @escaping RCTPromiseResolveBlock,
                            rejecter reject: @escaping RCTPromiseRejectBlock) {
        // Remove current notification
        UNUserNotificationCenter.current().removeDeliveredNotifications(withIdentifiers: [id])
        
        // Get alarm details
        if let alarm = getAlarmById(id: id) {
            let title = alarm["title"] ?? "Alarm"
            let body = (alarm["body"] ?? "") + " (Snoozed)"
            let snoozeEnabled = (alarm["snoozeEnabled"] as NSString?)?.boolValue ?? true
            let snoozeInterval = Int(alarm["snoozeInterval"] ?? "5") ?? 5
            let repeatFrequency = Int(alarm["repeatFrequency"] ?? "-1") ?? -1
            
            let content = UNMutableNotificationContent()
            content.title = title
            content.body = body
            content.sound = UNNotificationSound.default
            content.categoryIdentifier = snoozeEnabled ? "ALARM_CATEGORY_WITH_SNOOZE" : "ALARM_CATEGORY"
            content.userInfo = [
                "alarmId": id,
                "snoozeEnabled": snoozeEnabled,
                "snoozeInterval": snoozeInterval,
                "repeatFrequency": repeatFrequency
            ]
            
            registerNotificationCategory(snoozeEnabled: snoozeEnabled)
            
            let trigger = UNTimeIntervalNotificationTrigger(timeInterval: TimeInterval(minutes * 60), repeats: false)
            let request = UNNotificationRequest(identifier: id, content: content, trigger: trigger)
            
            UNUserNotificationCenter.current().add(request) { error in
                if let error = error {
                    reject("SNOOZE_ERROR", error.localizedDescription, error)
                    return
                }
                resolve(nil)
            }
        } else {
            // Alarm not found, just snooze with generic content
            snoozeAlarm(id, minutes: minutes, resolver: resolve, rejecter: reject)
        }
    }
    
    @objc(getCurrentAlarmPlaying:rejecter:)
    func getCurrentAlarmPlaying(_ resolve: @escaping RCTPromiseResolveBlock,
                                 rejecter reject: @escaping RCTPromiseRejectBlock) {
        // iOS doesn't have a concept of "currently playing" alarm like Android
        // Return nil as there's no persistent alarm sound
        resolve(nil)
    }
    
    // MARK: - Storage Helpers
    
    private func saveAlarm(id: String, datetimeISO: String, title: String, body: String, snoozeEnabled: Bool = true, snoozeInterval: Int = 5, repeatFrequency: Int = -1) {
        var alarms = getAlarmsDict()
        alarms[id] = [
            "id": id,
            "datetimeISO": datetimeISO,
            "title": title,
            "body": body,
            "snoozeEnabled": String(snoozeEnabled),
            "snoozeInterval": String(snoozeInterval),
            "repeatFrequency": String(repeatFrequency)
        ]
        UserDefaults.standard.set(alarms, forKey: AlarmModule.PREFS_KEY)
    }
    
    private func removeAlarm(id: String) {
        var alarms = getAlarmsDict()
        alarms.removeValue(forKey: id)
        UserDefaults.standard.set(alarms, forKey: AlarmModule.PREFS_KEY)
    }
    
    private func getAlarmsDict() -> [String: [String: String]] {
        return UserDefaults.standard.dictionary(forKey: AlarmModule.PREFS_KEY) as? [String: [String: String]] ?? [:]
    }
    
    private func getAlarms() -> [[String: String]] {
        return Array(getAlarmsDict().values)
    }
    
    private func getAlarmById(id: String) -> [String: String]? {
        return getAlarmsDict()[id]
    }
}