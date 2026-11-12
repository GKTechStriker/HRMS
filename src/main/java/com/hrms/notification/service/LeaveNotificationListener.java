package com.hrms.notification.service;

import com.hrms.leave.event.LeaveNotificationEvent;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Listens for leave lifecycle events only after the enclosing transaction commits cleanly,
 * preventing phantom emails on database rollbacks, then delegates to async NotificationService.
 */
@Component
public class LeaveNotificationListener {

    private final NotificationService notificationService;

    public LeaveNotificationListener(NotificationService notificationService) {
        this.notificationService = notificationService;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onLeaveNotification(LeaveNotificationEvent event) {
        notificationService.sendEmail(event.to(), event.subject(), event.body());
    }
}
