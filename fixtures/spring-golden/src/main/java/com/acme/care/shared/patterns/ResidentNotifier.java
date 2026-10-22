package com.acme.care.shared.patterns;

import org.springframework.mail.MailSender;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

@Service
public class ResidentNotifier {

    private final MailSender mailSender;

    public ResidentNotifier(MailSender mailSender) {
        this.mailSender = mailSender;
    }

    public void notifyResident(ResidentNoticeDto notice) {
        notifyResident(new ResidentNoticeEvent(notice.residentId()));
    }

    @Async
    public void notifyResident(ResidentNoticeEvent event) {
        SimpleMailMessage message = new SimpleMailMessage();
        message.setTo(event.residentId());
        mailSender.send(message);
    }
}
