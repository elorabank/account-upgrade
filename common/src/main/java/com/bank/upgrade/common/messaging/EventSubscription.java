package com.bank.upgrade.common.messaging;

/** Declare one as a Spring bean to have the consumer template read {@code topic} and call {@code handler}. */
public record EventSubscription(String topic, EventHandler handler) {}
