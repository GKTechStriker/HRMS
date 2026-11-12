package com.hrms.security;

import org.springframework.context.event.EventListener;
import org.springframework.session.events.SessionExpiredEvent;
import org.springframework.stereotype.Component;

/**
 * Listens for Spring Session expiration events and closes corresponding
 * user_sessions oversight records with endedReason = EXPIRED (GAP-01).
 */
@Component
public class SessionEventListener {

    private final SessionManagementService sessionManagementService;

    public SessionEventListener(SessionManagementService sessionManagementService) {
        this.sessionManagementService = sessionManagementService;
    }

    @EventListener
    public void onSessionExpired(SessionExpiredEvent event) {
        if (event != null && event.getSessionId() != null) {
            sessionManagementService.recordExpiredSession(event.getSessionId());
        }
    }
}
