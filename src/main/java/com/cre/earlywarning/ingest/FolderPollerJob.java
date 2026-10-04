package com.cre.earlywarning.ingest;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.List;

@Component
public class FolderPollerJob {

    private static final Logger log = LoggerFactory.getLogger(FolderPollerJob.class);
    private static final long RECENTLY_MODIFIED_WINDOW_MS = 5_000;

    private final CsvRowParser csvRowParser;
    private final IngestService ingestService;
    private final Path inboxDir;
    private final Path processingDir;
    private final Path doneDir;
    private final Path failedDir;

    public FolderPollerJob(CsvRowParser csvRowParser, IngestService ingestService,
                            @Value("${app.intake.inbox-dir}") String inboxDir,
                            @Value("${app.intake.processing-dir}") String processingDir,
                            @Value("${app.intake.done-dir}") String doneDir,
                            @Value("${app.intake.failed-dir}") String failedDir) {
        this.csvRowParser = csvRowParser;
        this.ingestService = ingestService;
        this.inboxDir = Path.of(inboxDir);
        this.processingDir = Path.of(processingDir);
        this.doneDir = Path.of(doneDir);
        this.failedDir = Path.of(failedDir);
        createDirIfMissing(this.inboxDir);
        createDirIfMissing(this.processingDir);
        createDirIfMissing(this.doneDir);
        createDirIfMissing(this.failedDir);
    }

    @Scheduled(fixedDelay = 10_000)
    @SchedulerLock(name = "report-poller", lockAtMostFor = "5m", lockAtLeastFor = "5s")
    public void poll() {
        File[] files = inboxDir.toFile().listFiles((dir, name) -> name.endsWith(".csv"));
        if (files == null) {
            return;
        }
        for (File file : files) {
            if (System.currentTimeMillis() - file.lastModified() < RECENTLY_MODIFIED_WINDOW_MS) {
                continue;
            }
            Path source = file.toPath();
            Path target = processingDir.resolve(file.getName());
            try {
                Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException e) {
                // another node won the race for this file; nothing to do
                continue;
            }
            processFile(target);
        }
    }

    private void processFile(Path file) {
        try {
            List<CsvRow> rows = csvRowParser.parse(file);
            for (CsvRow row : rows) {
                ingestService.ingest(row);
            }
            Files.move(file, doneDir.resolve(file.getFileName()), StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception e) {
            log.error("Failed to process {}: {}", file, e.getMessage(), e);
            try {
                Files.move(file, failedDir.resolve(file.getFileName()), StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException moveFailure) {
                log.error("Failed to move {} to failed dir: {}", file, moveFailure.getMessage(), moveFailure);
            }
        }
    }

    private void createDirIfMissing(Path dir) {
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot create directory " + dir, e);
        }
    }
}
