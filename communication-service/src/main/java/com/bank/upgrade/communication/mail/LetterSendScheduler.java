package com.bank.upgrade.communication.mail;

import org.springframework.scheduling.annotation.Scheduled;

/** Looks for due letters every few seconds. Safe on several instances: each locks different letters. */
public class LetterSendScheduler {

    private final LetterSender sender;
    private final int batchSize;

    public LetterSendScheduler(LetterSender sender, int batchSize) {
        this.sender = sender;
        this.batchSize = batchSize;
    }

    @Scheduled(fixedDelayString = "${bank.communication.poll-interval:PT5S}")
    public void sendDue() {
        // A full batch means more may be waiting: carry on, so a large day is not limited to one batch per interval
        while (sender.sendDue() >= batchSize && !Thread.currentThread().isInterrupted()) {
            // next batch
        }
    }
}
