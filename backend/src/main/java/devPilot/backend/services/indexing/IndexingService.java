package devPilot.backend.services.indexing;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;

import devPilot.backend.entity.IndexStatus;
import devPilot.backend.entity.Repository;
import devPilot.backend.exceptions.BadRequestException;
import devPilot.backend.exceptions.NotFoundException;
import devPilot.backend.repository.RepositoryRepository;
import devPilot.backend.services.UserService;
import devPilot.backend.services.ai.RagSettings;
import devPilot.backend.services.github.GitHubRateLimiter;
import devPilot.backend.services.github.GithubApiClient;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Service
@RequiredArgsConstructor
@Slf4j
public class IndexingService {
    private static final int VECTOR_BATCH_SIZE = 12;
    private static final int PROGRESS_EVERY_N_FILES = 5;

    private final RepositoryRepository repositoryRepository;
    private final UserService userService;
    private final GithubApiClient gitHubApiClient;
    private final CodeFileFilter fileFilter;
    private final CodeChunker codeChunker;
    private final GitHubRateLimiter rateLimiter;
    private final VectorStore vectorStore;

    private final Set<UUID> cancelledRepos = ConcurrentHashMap.newKeySet();

    @Value("${app.indexing.max-file-bytes:102400}")
    private long maxFileBytes;

    public Repository startIndexing(UUID repoId, UUID userId) {
        Repository repo = repositoryRepository.findByIdAndUserId(repoId, userId)
                .orElseThrow(() -> new NotFoundException("Repository not found"));

        if (repo.getIndexStatus() == IndexStatus.INDEXING) {
            throw new BadRequestException("Repository is already being indexed");
        }

        cancelledRepos.remove(repoId);

        repo.setIndexStatus(IndexStatus.INDEXING);
        repo.setFilesProcessed(0);
        repo.setFilesTotal(0);
        repo.setChunkCount(0);
        repo.setErrorMessage(null);
        repo.setUpdatedAt(Instant.now());
        return repositoryRepository.save(repo);
    }

    @Transactional
    public Repository cancelIndexing(UUID repoId, UUID userId) {
        Repository repo = repositoryRepository.findByIdAndUserId(repoId, userId)
                .orElseThrow(() -> new NotFoundException("Repository not found"));

        cancelledRepos.add(repoId);
        deleteExistingVectors(repoId.toString());

        repo.setIndexStatus(IndexStatus.PENDING);
        repo.setFilesProcessed(0);
        repo.setFilesTotal(0);
        repo.setChunkCount(0);
        repo.setErrorMessage(null);
        repo.setUpdatedAt(Instant.now());
        Repository saved = repositoryRepository.save(repo);
        log.info("Indexing cancelled for repository {} by user {}", repo.getFullName(), userId);
        return saved;
    }

    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void resetStuckIndexingOnStartup() {
        List<Repository> stuck = repositoryRepository.findAll().stream()
                .filter(r -> r.getIndexStatus() == IndexStatus.INDEXING)
                .toList();
        for (Repository r : stuck) {
            r.setIndexStatus(IndexStatus.PENDING);
            r.setErrorMessage(null);
            r.setFilesProcessed(0);
            r.setFilesTotal(0);
            r.setChunkCount(0);
            r.setUpdatedAt(Instant.now());
            repositoryRepository.save(r);
            log.info("Reset stuck indexing repository {} to PENDING on startup", r.getFullName());
        }
    }

    @Async("indexingExecutor")
     public void indexAsync(UUID repoId, UUID userId) {
        try {
            doIndex(repoId, userId);
        } catch (Exception ex) {
            log.error("Indexing failed for repo {}", repoId, ex);
            markFailed(repoId, ex.getMessage());
        }
    }


      private void doIndex(UUID repoId, UUID userId) {
        if (cancelledRepos.contains(repoId)) {
            log.info("Indexing cancelled before start for repo {}", repoId);
            cancelledRepos.remove(repoId);
            return;
        }

        Repository repo = repositoryRepository.findById(repoId)
                .orElseThrow(() -> new NotFoundException("Repository not found"));
        String token = userService.decryptAccessToken(userService.requiredById(userId));

        deleteExistingVectors(repoId.toString());

        Map<String, Object> tree = gitHubApiClient.getRepoTree(
                token, repo.getOwner(), repo.getName(), repo.getDefaultBranch());
        List<String> filePaths = listIndexableFiles(tree);

        updateProgress(repoId, filePaths.size(), 0, 0, IndexStatus.INDEXING, null);

        List<Document> batch = new ArrayList<>();
        int processed = 0;
        int totalChunks = 0;

        for (String path : filePaths) {
            if (cancelledRepos.contains(repoId)) {
                log.info("Indexing cancelled during file loop for repo {}", repoId);
                cancelledRepos.remove(repoId);
                deleteExistingVectors(repoId.toString());
                return;
            }

            try {
                String content = gitHubApiClient.getFileContent(
                        token, repo.getOwner(), repo.getName(), path);
                List<Document> chunks = codeChunker.chunkFile(repoId.toString(), path, content);
                batch.addAll(chunks);
                totalChunks += chunks.size();
                if (batch.size() >= VECTOR_BATCH_SIZE) {
                    addWithRetry(repoId, batch);
                    batch.clear();
                }
            } catch (Exception ex) {
                log.warn("Skipping file {} in {}: {}", path, repo.getFullName(), ex.getMessage());
            }

            processed++;
            if (processed % PROGRESS_EVERY_N_FILES == 0 || processed == filePaths.size()) {
                updateProgress(repoId, filePaths.size(), processed, totalChunks, IndexStatus.INDEXING, null);
            }
            rateLimiter.pause();
        }

        if (cancelledRepos.contains(repoId)) {
            log.info("Indexing cancelled before final batch for repo {}", repoId);
            cancelledRepos.remove(repoId);
            deleteExistingVectors(repoId.toString());
            return;
        }

        if (!batch.isEmpty()) {
            addWithRetry(repoId, batch);
            batch.clear();
        }

        markReady(repoId, filePaths.size(), processed, totalChunks, repo.getFullName());
    }

    private void addWithRetry(UUID repoId, List<Document> documents) {
        if (documents == null || documents.isEmpty()) {
            return;
        }
        int maxAttempts = 6;
        long waitMs = 3000L;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            if (cancelledRepos.contains(repoId)) {
                log.info("Indexing cancelled during retry backoff for repo {}", repoId);
                return;
            }
            try {
                vectorStore.add(documents);
                try {
                    Thread.sleep(600L);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                }
                return;
            } catch (Exception ex) {
                if (cancelledRepos.contains(repoId)) {
                    log.info("Indexing cancelled after exception for repo {}", repoId);
                    return;
                }
                String msg = ex.getMessage() != null ? ex.getMessage().toLowerCase() : "";
                boolean isQuota = msg.contains("429") || msg.contains("quota") || msg.contains("resource_exhausted");
                if (isQuota && attempt < maxAttempts) {
                    log.warn("Gemini embedding rate limit hit (attempt {}/{}). Backing off for {}ms...", attempt, maxAttempts, waitMs);
                    try {
                        Thread.sleep(waitMs);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new RuntimeException("Interrupted during rate limit backoff", ie);
                    }
                    waitMs *= 2;
                } else {
                    throw ex instanceof RuntimeException re ? re : new RuntimeException(ex);
                }
            }
        }
    }


       @SuppressWarnings("unchecked")
    private List<String> listIndexableFiles(Map<String, Object> tree) {
        if (tree == null || tree.get("tree") == null) {
            return List.of();
        }

        List<Map<String, Object>> entries = (List<Map<String, Object>>) tree.get("tree");
        return entries.stream()
                .filter(entry -> "blob".equals(String.valueOf(entry.get("type"))))
                .filter(entry -> {
                    String path = String.valueOf(entry.get("path"));
                    long size = entry.get("size") instanceof Number n ? n.longValue() : 0L;
                    return fileFilter.isEligible(path, size, maxFileBytes);
                })
                .map(entry -> String.valueOf(entry.get("path")))
                .toList();
    }

     private void deleteExistingVectors(String repoId) {
        try {
            var filter = new FilterExpressionBuilder().eq(RagSettings.METADATA_REPO_ID, repoId).build();
            vectorStore.delete(filter);
        } catch (Exception ex) {
            log.warn("Could not delete existing vectors for repo {}: {}", repoId, ex.getMessage());
        }
    };

      @Transactional
    protected void updateProgress(
            UUID repoId,
            int total,
            int processed,
            int chunks,
            IndexStatus status,
            String error) {
        repositoryRepository.findById(repoId).ifPresent(repo -> {
            repo.setFilesTotal(total);
            repo.setFilesProcessed(processed);
            repo.setChunkCount(chunks);
            repo.setIndexStatus(status);
            repo.setErrorMessage(error);
            repo.setUpdatedAt(Instant.now());
            repositoryRepository.save(repo);
        });
    }

      @Transactional
    protected void markReady(UUID repoId, int totalFiles, int processedFiles, int totalChunks, String fullName) {
        repositoryRepository.findById(repoId).ifPresent(repo -> {
            repo.setIndexStatus(IndexStatus.READY);
            repo.setFilesTotal(totalFiles);
            repo.setFilesProcessed(processedFiles);
            repo.setChunkCount(totalChunks);
            repo.setIndexedAt(Instant.now());
            repo.setErrorMessage(null);
            repo.setUpdatedAt(Instant.now());
            repositoryRepository.save(repo);
        });
        log.info("Indexed {} files ({} chunks) for {}", processedFiles, totalChunks, fullName);
    }

     @Transactional
    protected void markFailed(UUID repoId, String message) {
        repositoryRepository.findById(repoId).ifPresent(repo -> {
            repo.setIndexStatus(IndexStatus.FAILED);
            repo.setErrorMessage(message != null && message.length() > 2000
                    ? message.substring(0, 2000)
                    : message);
            repo.setUpdatedAt(Instant.now());
            repositoryRepository.save(repo);
        });
    }

}
