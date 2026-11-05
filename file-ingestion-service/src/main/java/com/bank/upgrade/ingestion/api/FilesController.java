package com.bank.upgrade.ingestion.api;

import com.bank.upgrade.common.api.PageView;
import com.bank.upgrade.common.persistence.BlockingJpa;
import com.bank.upgrade.ingestion.domain.FileIngestionLog;
import com.bank.upgrade.ingestion.domain.FileIngestionLogRepository;
import com.bank.upgrade.ingestion.domain.IngestionError;
import com.bank.upgrade.ingestion.domain.IngestionErrorRepository;
import java.time.Instant;
import java.time.LocalDate;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/** The loaded files for the ops console: newest first, one file, and the rows a file rejected. Read roles only. */
@RestController
public class FilesController {

    public record FileView(
            long id, String fileName, LocalDate businessDate, String status, Integer totalRows, Integer loadedRows,
            Integer rejectedRows, String errorMessage, Instant startedAt, Instant completedAt) {

        static FileView of(FileIngestionLog f) {
            return new FileView(f.getId(), f.getFileName(), f.getBusinessDate(), f.getStatus(), f.getTotalRows(),
                f.getLoadedRows(), f.getRejectedRows(), f.getErrorMessage(), f.getStartedAt(), f.getCompletedAt());
        }
    }

    /** {@code rawRow} is the row as it was in the file, so ops can see what to correct. */
    public record ErrorView(int rowNo, String errorMessage, String rawRow) {
        static ErrorView of(IngestionError e) {
            return new ErrorView(e.getRowNo(), e.getErrorMessage(), e.getRawRowJson());
        }
    }

    private final FileIngestionLogRepository files;
    private final IngestionErrorRepository errors;
    private final BlockingJpa jpa;

    public FilesController(FileIngestionLogRepository files, IngestionErrorRepository errors, BlockingJpa jpa) {
        this.files = files;
        this.errors = errors;
        this.jpa = jpa;
    }

    @GetMapping("/api/files")
    public Mono<PageView<FileView>> list(
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        var request = PageRequest.of(Math.max(page, 0), Math.clamp(size, 1, 200), Sort.by(Sort.Order.desc("id")));
        return jpa.call(() -> status == null || status.isBlank() ? files.findAll(request) : files.findByStatus(status, request))
            .map(result -> PageView.of(result, FileView::of));
    }

    @GetMapping("/api/files/{id}")
    public Mono<ResponseEntity<FileView>> one(@PathVariable long id) {
        return jpa.call(() -> files.findById(id).map(FileView::of))
            .map(found -> found.map(ResponseEntity::ok).orElseGet(() -> ResponseEntity.notFound().build()));
    }

    @GetMapping("/api/files/{id}/errors")
    public Mono<PageView<ErrorView>> errors(
            @PathVariable long id,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        var request = PageRequest.of(Math.max(page, 0), Math.clamp(size, 1, 200), Sort.by("rowNo"));
        return jpa.call(() -> errors.findByFileId(id, request)).map(result -> PageView.of(result, ErrorView::of));
    }
}
