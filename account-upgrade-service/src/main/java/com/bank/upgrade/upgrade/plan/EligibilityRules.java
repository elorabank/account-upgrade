package com.bank.upgrade.upgrade.plan;

import com.bank.upgrade.upgrade.config.UpgradeProperties;
import com.bank.upgrade.common.api.StagedAccount;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Decides, for one account, whether it is upgraded and to what. The rules are configuration, see {@link UpgradeProperties.Rules}. */
public class EligibilityRules {

    /** @param targetType the new type when eligible; {@code reason} is set when not */
    public record Decision(String targetType, String reason) {
        public boolean eligible() {
            return reason == null;
        }
    }

    private final UpgradeProperties.Rules rules;

    public EligibilityRules(UpgradeProperties.Rules rules) {
        this.rules = rules;
    }

    public Decision evaluate(StagedAccount account) {
        List<String> problems = new ArrayList<>();
        String target = rules.targetTypes().get(account.currentType().toUpperCase(Locale.ROOT));
        if (target == null) {
            problems.add("no upgrade path for account type " + account.currentType());
        }
        String required = rules.requiredKycStatus();
        if (required != null && !required.isBlank() && !required.equalsIgnoreCase(account.kycStatus())) {
            problems.add("KYC status is " + (account.kycStatus() == null ? "missing" : account.kycStatus()) + ", " + required + " is required");
        }
        if (account.balance() == null || account.balance().compareTo(rules.minBalance()) < 0) {
            problems.add("balance " + (account.balance() == null ? "is missing" : account.balance()) + " is below the minimum " + rules.minBalance());
        }
        return problems.isEmpty() ? new Decision(target, null) : new Decision(null, String.join("; ", problems));
    }
}
