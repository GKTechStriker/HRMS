package com.hrms.notification.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

/**
 * Asynchronous notification service for email and operational alerts.
 * Operates in the background so slow SMTP roundtrips never block web transactions.
 */
@Service
public class NotificationService {

    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);

    private final JavaMailSender mailSender;

    public NotificationService(@Autowired(required = false) JavaMailSender mailSender) {
        this.mailSender = mailSender;
    }

    /**
     * Sends an email notification asynchronously.
     */
    @Async
    public void sendEmail(String to, String subject, String body) {
        log.info("[NOTIFICATION] Queued notification to: '{}' | Subject: '{}'", to, subject);

        if (mailSender == null) {
            log.info("[NOTIFICATION SIMULATION] (No SMTP mail sender configured) Content:\n{}", body);
            return;
        }

        try {
            SimpleMailMessage message = new SimpleMailMessage();
            message.setTo(to);
            message.setSubject(subject);
            message.setText(body);
            mailSender.send(message);
            log.info("[NOTIFICATION] Email successfully dispatched to '{}'", to);
        } catch (Exception ex) {
            log.warn("[NOTIFICATION] Could not send email to '{}': {}. (Application flow continues uninterrupted)",
                    to, ex.getMessage());
        }
    }
}
