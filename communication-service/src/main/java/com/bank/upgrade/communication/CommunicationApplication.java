package com.bank.upgrade.communication;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** Builds the welcome letter for an upgraded account and emails it (the letter is the email body). */
@SpringBootApplication
public class CommunicationApplication {
    public static void main(String[] args) {
        SpringApplication.run(CommunicationApplication.class, args);
    }
}
