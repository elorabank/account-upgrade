package com.bank.upgrade.audit.api;

import com.bank.upgrade.audit.archive.AuditArchiveRepository;
import com.bank.upgrade.common.persistence.BlockingJpa;
import java.time.Instant;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/**
 * {@code GET /api/audit/archives}: the months moved out of the database into archive files, newest first, with
 * each file's name, number of events and SHA-256, so an auditor knows where an old month is and can check the file.
 * Same access as searching the trail: ops admins, auditors and the {@code audit.read} scope.
 */
@RestController
public class AuditArchiveController {

    /** @param month the month, as {@code 2025-01} */
    public record ArchiveView(String month, String status, String fileName, Long events, String sha256,
            Long firstId, Long lastId, Instant archivedAt, Instant droppedAt) {

        static ArchiveView of(AuditArchiveRepository.Entry e) {
            return new ArchiveView(e.month().toString().substring(0, 7), e.status(), e.fileName(), e.rowCount(),
                e.sha256(), e.firstId(), e.lastId(), e.archivedAt(), e.droppedAt());
        }
    }

    private final AuditArchiveRepository archives;
    private final BlockingJpa jpa;

    public AuditArchiveController(AuditArchiveRepository archives, BlockingJpa jpa) {
        this.archives = archives;
        this.jpa = jpa;
    }

    @GetMapping("/api/audit/archives")
    public Mono<List<ArchiveView>> list() {
        return jpa.call(() -> archives.entries().stream().map(ArchiveView::of).toList());
    }
}
