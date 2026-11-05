package com.bank.upgrade.upgrade.gateway;

/** @param idempotencyKey the same for every try of the same upgrade, so the core system can ignore a repeat */
public record UpgradeCommand(String idempotencyKey, String accountNo, String fromType, String toType) {}
