package com.bank.upgrade.communication.domain;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LetterTemplateRepository extends JpaRepository<LetterTemplate, Long> {
    Optional<LetterTemplate> findFirstByAccountTypeAndActiveTrueOrderByVersionNoDesc(String accountType);
}
