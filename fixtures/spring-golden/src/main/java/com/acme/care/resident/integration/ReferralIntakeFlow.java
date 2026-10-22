package com.acme.care.resident.integration;

import java.io.File;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.integration.annotation.InboundChannelAdapter;
import org.springframework.integration.annotation.Poller;
import org.springframework.integration.channel.DirectChannel;
import org.springframework.integration.core.MessageSource;
import org.springframework.integration.file.FileReadingMessageSource;
import org.springframework.messaging.MessageChannel;

@Configuration
public class ReferralIntakeFlow {

    @Bean
    public MessageChannel intakeChannel() {
        return new DirectChannel();
    }

    @Bean
    @InboundChannelAdapter(channel = "intakeChannel", poller = @Poller(fixedDelay = "60000"))
    public MessageSource<File> referralDropFolder() {
        FileReadingMessageSource source = new FileReadingMessageSource();
        source.setDirectory(new File("/var/care/referrals/inbox"));
        return source;
    }
}
