package com.example.ykdsummer.fashion.application;

import com.example.ykdsummer.ai.service.LocalImageAssetStore;
import com.example.ykdsummer.ai.service.LocalImageAssetStore.StoredImage;
import com.example.ykdsummer.ai.service.OssImageAssetStore;
import com.example.ykdsummer.ai.tool.ImageTaskRunner;
import com.example.ykdsummer.fashion.domain.ClothingCandidate;
import com.example.ykdsummer.fashion.domain.ClothingCandidateLabels;
import com.example.ykdsummer.fashion.domain.ClothingCandidateStatus;
import com.example.ykdsummer.fashion.domain.FashionImageAsset;
import com.example.ykdsummer.fashion.domain.GarmentCutoutTask;
import com.example.ykdsummer.fashion.domain.GarmentCutoutWork;
import com.example.ykdsummer.fashion.domain.GarmentDraftVersion;
import com.example.ykdsummer.fashion.domain.WardrobeItem;
import com.example.ykdsummer.fashion.domain.WardrobeItemDraft;
import com.example.ykdsummer.fashion.persistence.FashionWardrobeIngestionRepository;
import com.example.ykdsummer.fashion.runtime.FashionGarmentCutoutCompletedEvent;
import com.example.ykdsummer.fashion.runtime.FashionGarmentCutoutFailedEvent;
import com.example.ykdsummer.fashion.runtime.FashionWardrobePhotoAnalyzedEvent;
import com.example.ykdsummer.fashion.runtime.FashionWardrobePhotoAnalysisFailedEvent;
import com.example.ykdsummer.persistence.ImageAssetMetadataStore;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Application workflow for analyze -> select -> render -> final confirm. */
@Service
@ConditionalOnProperty(prefix = "app.persistence", name = "enabled", havingValue = "true")
public class FashionWardrobeIngestionService {
    private static final Logger log = LoggerFactory.getLogger(FashionWardrobeIngestionService.class);
    private static final Duration DEFAULT_DRAFT_LIFETIME = Duration.ofMinutes(30);
    private final FashionWardrobeIngestionRepository repository;
    private final FashionCoreService fashion;
    private final WardrobePhotoAnalyzer analyzer;
    private final GarmentCutoutService cutouts;
    private final LocalImageAssetStore imageStore;
    private final ObjectMapper objectMapper;
    private volatile ImageAssetMetadataStore assetMetadata = ImageAssetMetadataStore.disabled();
    private volatile ApplicationEventPublisher completionPublisher = event -> { };
    private volatile ImageTaskRunner analysisRunner = ImageTaskRunner.inline();
    private final Set<String> inFlightPhotoAnalyses = ConcurrentHashMap.newKeySet();
    private volatile Duration draftLifetime = DEFAULT_DRAFT_LIFETIME;

    public FashionWardrobeIngestionService(
            FashionWardrobeIngestionRepository repository,
            FashionCoreService fashion,
            WardrobePhotoAnalyzer analyzer,
            GarmentCutoutService cutouts,
            LocalImageAssetStore imageStore,
            ObjectMapper objectMapper
    ) {
        this.repository = repository;
        this.fashion = fashion;
        this.analyzer = analyzer;
        this.cutouts = cutouts;
        this.imageStore = imageStore;
        this.objectMapper = objectMapper;
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setAssetMetadata(ImageAssetMetadataStore assetMetadata) {
        this.assetMetadata = assetMetadata == null ? ImageAssetMetadataStore.disabled() : assetMetadata;
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setCompletionPublisher(ApplicationEventPublisher completionPublisher) {
        this.completionPublisher = completionPublisher == null ? event -> { } : completionPublisher;
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setAnalysisRunner(ImageTaskRunner analysisRunner) {
        this.analysisRunner = analysisRunner == null ? ImageTaskRunner.inline() : analysisRunner;
    }

    @Value("${app.fashion.ingestion.draft-lifetime:30m}")
    void setDraftLifetime(Duration draftLifetime) {
        if (draftLifetime != null && !draftLifetime.isNegative() && !draftLifetime.isZero()) {
            this.draftLifetime = draftLifetime;
        }
    }

    /** Analyze an owned source image once; repeated requests reuse its unexpired candidate drafts. */
    public IntakeResult analyzePhoto(String externalUserId, String imageAssetId, Integer imageVersion) {
        FashionImageAsset source = repository.requireOwnedImage(externalUserId, imageAssetId, imageVersion);
        List<ClothingCandidate> existing = repository.candidatesForSource(externalUserId, source.assetVersionId());
        if (!existing.isEmpty()) {
            Instant deadline = draftDeadline();
            existing.forEach(candidate -> repository.renewCandidateDraft(externalUserId, candidate.id(), deadline));
            return new IntakeResult("Existing candidate drafts were reused", existing, true);
        }
        StoredImage stored = imageStore.find(externalUserId, source.assetId(), source.version())
                .orElseThrow(() -> new IllegalStateException("Image bytes are not available for garment analysis"));
        WardrobePhotoAnalyzer.AnalysisResult analysis = analyzer.analyze(stored);
        List<ClothingCandidate> candidates = repository.createCandidateDrafts(externalUserId, source, analysis.candidates(),
                analyzer.providerName(), "", analyzer.promptVersion(), draftDeadline());
        return new IntakeResult(analysis.summary(), candidates, false);
    }

    /**
     * Submits a background photo analysis that replies with candidates once ready.
     * Returns {@code true} when a new analysis task was queued (or is already running), and
     * {@code false} when usable candidate drafts already exist for this photo.
     */
    public boolean submitPhotoAnalysis(String externalUserId, String imageAssetId, Integer imageVersion) {
        FashionImageAsset source = repository.requireOwnedImage(externalUserId, imageAssetId, imageVersion);
        if (!repository.candidatesForSource(externalUserId, source.assetVersionId()).isEmpty()) {
            return false;
        }
        String key = externalUserId + ":" + source.assetVersionId();
        if (!inFlightPhotoAnalyses.add(key)) {
            return true; // 同一张照片的分析已经在进行中
        }
        boolean submitted = analysisRunner.submit(() -> {
            try {
                IntakeResult result = analyzePhoto(externalUserId, imageAssetId, imageVersion);
                publishPhotoAnalyzed(externalUserId, imageAssetId, imageVersion, result);
            } catch (RuntimeException failure) {
                publishPhotoAnalysisFailed(externalUserId, imageAssetId, imageVersion, failure);
            } finally {
                inFlightPhotoAnalyses.remove(key);
            }
        });
        if (!submitted) inFlightPhotoAnalyses.remove(key);
        return submitted;
    }

    public List<ClothingCandidate> candidatesForPhoto(String externalUserId, String imageAssetId, Integer imageVersion) {
        FashionImageAsset source = repository.requireOwnedImage(externalUserId, imageAssetId, imageVersion);
        return repository.candidatesForSource(externalUserId, source.assetVersionId());
    }

    public ClothingCandidate updateCandidateLabels(String externalUserId, String candidateId, ClothingCandidateLabels labels) {
        repository.updateLabels(externalUserId, candidateId, labels);
        repository.renewCandidateDraft(externalUserId, candidateId, draftDeadline());
        return repository.candidate(externalUserId, candidateId).orElseThrow();
    }

    public Optional<ClothingCandidate> candidate(String externalUserId, String candidateId) {
        return repository.candidate(externalUserId, candidateId);
    }

    /** Candidates that are fully identified and waiting for the user's approval to start cutout. */
    public List<ClothingCandidate> pendingSelectionCandidates(String externalUserId) {
        return repository.pendingSelectionCandidates(externalUserId);
    }

    public List<ClothingCandidate> activeWorkflowCandidates(String externalUserId) {
        return repository.activeWorkflowCandidates(externalUserId);
    }

    public int cancelCandidates(String externalUserId, List<String> candidateIds) {
        return repository.cancelCandidates(externalUserId, candidateIds, Instant.now());
    }

    /** Only completed cutout drafts are eligible for a natural-language final confirmation. */
    public List<ClothingCandidate> awaitingFinalConfirmationCandidates(String externalUserId) {
        return repository.awaitingFinalConfirmationCandidates(externalUserId);
    }

    /** Completed previews are preserved as selectable alternatives until the draft expires or is confirmed. */
    public List<GarmentDraftVersion> draftVersions(String externalUserId, String candidateId) {
        return repository.draftVersions(externalUserId, candidateId);
    }

    public Optional<GarmentCutoutTask> latestCutoutTask(String externalUserId, String candidateId) {
        return repository.latestCutoutTask(externalUserId, candidateId);
    }

    public GarmentDraftVersion draftVersion(String externalUserId, String candidateId, int versionNumber) {
        return draftVersions(externalUserId, candidateId).stream()
                .filter(value -> value.versionNumber() == versionNumber)
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Garment draft version is not available"));
    }

    public StoredImage draftPreviewImage(String externalUserId, String candidateId, int versionNumber) {
        GarmentDraftVersion version = draftVersion(externalUserId, candidateId, versionNumber);
        return imageStore.find(externalUserId, version.image().assetId(), version.image().version())
                .orElseThrow(() -> new IllegalStateException("Garment draft image bytes are no longer available"));
    }

    public byte[] draftPreviewBytes(String externalUserId, String candidateId, int versionNumber) {
        return imageStore.readBytes(draftPreviewImage(externalUserId, candidateId, versionNumber));
    }

    /** Human-readable labels intentionally omit internal candidate and task identifiers. */
    public String draftReviewSummary(ClothingCandidate candidate) {
        if (candidate == null) return "";
        JsonNode attributes = attributes(candidate.analysisAttributesJson());
        List<String> lines = new ArrayList<>();
        addLine(lines, "名称", FashionItemNamer.nameFor(candidate.categoryCode(), candidate.colorPrimary(),
                candidate.displayName() + " " + candidate.analysisAttributesJson(), candidate.fitCode(),
                attributes.path("patternCode").asText()));
        addLine(lines, "类型", categoryLabel(candidate.categoryCode()));
        addLine(lines, "颜色", colorLabel(candidate.colorPrimary()));
        addLine(lines, "风格", labels(candidate.styleTags(), FashionWardrobeIngestionService::styleLabel));
        addLine(lines, "版型", fitLabel(candidate.fitCode()));
        addLine(lines, "季节", labels(candidate.seasonTags(), FashionWardrobeIngestionService::seasonLabel));
        addLine(lines, "材质", text(attributes.path("material").asText(), 128));
        addLine(lines, "图案", patternLabel(attributes.path("patternCode").asText()));
        addLine(lines, "适合场景", labels(strings(attributes.path("occasionTags")), FashionWardrobeIngestionService::occasionLabel));
        return String.join("\n", lines);
    }

    /** Selection only creates durable tasks; the scheduled worker claims them independently and in parallel. */
    public List<GarmentCutoutTask> selectCandidatesForCutout(String externalUserId, List<String> candidateIds) {
        Instant deadline = draftDeadline();
        List<String> selected = (candidateIds == null ? List.<String>of() : candidateIds).stream()
                .filter(candidateId -> candidateId != null && !candidateId.isBlank()).map(String::strip).distinct().toList();
        selected.forEach(candidateId ->
                repository.renewCandidateDraft(externalUserId, candidateId, deadline));
        return repository.submitCutoutTasks(externalUserId, selected, "");
    }

    public GarmentCutoutTask retryCutout(String externalUserId, String candidateId, String instruction) {
        return repository.retryCutoutTask(externalUserId, candidateId, instruction, draftDeadline());
    }

    /** Create another selectable visual version from an existing cutout, without replacing earlier choices. */
    public GarmentCutoutTask reviseDraft(String externalUserId, String candidateId, Integer sourceVersionNumber,
                                         String instruction) {
        List<GarmentDraftVersion> versions = draftVersions(externalUserId, candidateId);
        if (versions.isEmpty()) throw new IllegalStateException("No completed garment draft is available for editing");
        int source = sourceVersionNumber == null || sourceVersionNumber < 1
                ? versions.stream().filter(GarmentDraftVersion::current).findFirst()
                        .orElse(versions.getLast()).versionNumber()
                : sourceVersionNumber;
        return repository.reviseDraftTask(externalUserId, candidateId, source, instruction, draftDeadline());
    }

    public List<String> pendingCutoutTaskIds(int limit) {
        return repository.pendingCutoutTaskIds(Instant.now(), limit);
    }

    /** Called by a bounded background worker. Claiming is atomic so duplicate dispatches are harmless. */
    public void executeCutoutTask(String taskId) {
        Optional<GarmentCutoutWork> claimed = repository.claimCutoutTask(taskId, Instant.now());
        if (claimed.isEmpty()) return;
        GarmentCutoutWork work = claimed.orElseThrow();
        try {
            StoredImage source = imageStore.find(work.externalUserId(), work.sourceImage().assetId(), work.sourceImage().version())
                    .orElseThrow(() -> new IllegalStateException("Source image bytes are no longer available"));
            boolean isRevision = work.task().sourceAssetVersionId() != work.candidate().sourceAssetVersionId();
            GarmentCutoutService.CutoutResult result = isRevision
                    ? cutouts.revise(work.externalUserId(), source, work.candidate(), work.task().instructionText())
                    : cutouts.cutout(work.externalUserId(), source, work.candidate(), work.task().instructionText());
            if (!result.hasImage()) {
                repository.failCutoutTask(work.task().id(), result.failureSummary(), Instant.now());
                publishFailure(work, result.failureSummary());
                return;
            }
            boolean cancelledWhileRendering = repository.candidate(work.externalUserId(), work.candidate().id())
                    .map(ClothingCandidate::status)
                    .filter(status -> status == ClothingCandidateStatus.REJECTED
                            || status == ClothingCandidateStatus.EXPIRED)
                    .isPresent();
            if (cancelledWhileRendering) {
                log.info("Fashion garment cutout output ignored after cancellation, task={}, candidate={}",
                        work.task().id(), work.candidate().id());
                return;
            }
            StoredImage output = imageStore.saveGenerated(work.externalUserId(), "garment-cutout:" + work.candidate().id(),
                    result.imageBytes(), result.remoteUrl());
            assetMetadata.record(work.externalUserId(), output, imageStore instanceof OssImageAssetStore ? "oss" : "local");
            FashionImageAsset outputAsset = repository.requireOwnedImage(work.externalUserId(), output.assetId(), output.version());
            repository.completeCutoutTask(work.task().id(), outputAsset.assetVersionId(), Instant.now());
            Optional<ClothingCandidate> refreshedCandidate =
                    repository.candidate(work.externalUserId(), work.candidate().id());
            if (refreshedCandidate.map(ClothingCandidate::status)
                    .filter(status -> status == ClothingCandidateStatus.REJECTED
                            || status == ClothingCandidateStatus.EXPIRED)
                    .isPresent()) {
                log.info("Fashion garment cutout result discarded after cancellation, task={}, candidate={}",
                        work.task().id(), work.candidate().id());
                return;
            }
            refreshedCandidate
                    .filter(candidate -> candidate.status() != ClothingCandidateStatus.FINAL_CONFIRMED)
                    .ifPresent(candidate -> repository.renewCandidateDraft(work.externalUserId(), candidate.id(), draftDeadline()));
            boolean readyForConfirmation = repository.candidate(work.externalUserId(), work.candidate().id())
                    .map(candidate -> candidate.status() == ClothingCandidateStatus.AWAITING_FINAL_CONFIRMATION
                            && Long.valueOf(outputAsset.assetVersionId()).equals(candidate.currentCutoutAssetVersionId()))
                    .orElse(false);
            if (readyForConfirmation) {
                publishCompletion(work, output, result.imageBytes());
            }
            log.info("Fashion garment cutout completed, task={}, candidate={}", work.task().id(), work.candidate().id());
        } catch (RuntimeException exception) {
            String failureSummary = "Garment cutout provider or storage failed";
            try {
                repository.failCutoutTask(work.task().id(), failureSummary, Instant.now());
                publishFailure(work, failureSummary);
            } catch (RuntimeException ignored) {
                // The draft may have expired while a provider request was running.
            }
            log.warn("Fashion garment cutout failed, task={}", work.task().id(), exception);
        }
    }

    /** Final confirmation is the only transition that creates a durable wardrobe item. */
    @Transactional
    public WardrobeItem confirmCandidate(String externalUserId, String candidateId) {
        return confirmCandidate(externalUserId, candidateId, null);
    }

    /** A user can confirm any preview version; the default remains the most recently generated preview. */
    @Transactional
    public WardrobeItem confirmCandidate(String externalUserId, String candidateId, Integer versionNumber) {
        ClothingCandidate candidate = repository.candidate(externalUserId, candidateId)
                .orElseThrow(() -> new IllegalArgumentException("Clothing candidate is not available to the current user"));
        if (candidate.status() != ClothingCandidateStatus.AWAITING_FINAL_CONFIRMATION
                || candidate.currentCutoutAssetVersionId() == null) {
            throw new IllegalStateException("Candidate is not ready for final confirmation");
        }
        List<GarmentDraftVersion> versions = draftVersions(externalUserId, candidate.id());
        GarmentDraftVersion selected = (versionNumber == null || versionNumber < 1)
                ? versions.stream().filter(GarmentDraftVersion::current).findFirst().orElseThrow(() ->
                        new IllegalStateException("Current garment draft version is not available"))
                : draftVersion(externalUserId, candidate.id(), versionNumber);
        FashionImageAsset cutout = repository.requireOwnedImageVersion(externalUserId, selected.image().assetVersionId());
        FashionImageAsset original = repository.requireOwnedImageVersion(externalUserId, candidate.sourceAssetVersionId());
        WardrobeItem item = fashion.addWardrobeItemWithImage(externalUserId, wardrobeDraft(candidate), cutout.assetId(), cutout.version());
        fashion.attachWardrobeAsset(externalUserId, item.id(), original.assetVersionId(), "ORIGINAL", false);
        repository.markCandidateConfirmed(externalUserId, candidate.id(), selected.image().assetVersionId(), item.id(), Instant.now());
        return item;
    }

    public int expireUnconfirmedDrafts() { return repository.expireUnconfirmedDrafts(Instant.now()); }

    private Instant draftDeadline() { return Instant.now().plus(draftLifetime); }

    private void publishPhotoAnalyzed(String externalUserId, String imageAssetId, int imageVersion, IntakeResult result) {
        List<String> names = result.candidates().stream()
                .map(ClothingCandidate::displayName)
                .filter(name -> name != null && !name.isBlank())
                .toList();
        try {
            completionPublisher.publishEvent(new FashionWardrobePhotoAnalyzedEvent(
                    externalUserId, imageAssetId, imageVersion, result.summary(), names));
        } catch (RuntimeException exception) {
            // The candidates are already durable; the user can still inspect them via list_wardrobe_photo_candidates.
            log.warn("Could not publish wardrobe photo analysis completion, user={}, image={}",
                    anonymize(externalUserId), imageAssetId, exception);
        }
    }

    private void publishPhotoAnalysisFailed(String externalUserId, String imageAssetId, int imageVersion,
                                            RuntimeException failure) {
        try {
            completionPublisher.publishEvent(new FashionWardrobePhotoAnalysisFailedEvent(
                    externalUserId, imageAssetId, imageVersion, failure.getMessage()));
        } catch (RuntimeException exception) {
            log.warn("Could not publish wardrobe photo analysis failure, user={}, image={}",
                    anonymize(externalUserId), imageAssetId, exception);
        }
    }

    private static String anonymize(String userId) {
        return userId == null ? "unknown" : Integer.toHexString(userId.hashCode());
    }

    private void publishCompletion(GarmentCutoutWork work, StoredImage output, byte[] imageBytes) {
        try {
            completionPublisher.publishEvent(new FashionGarmentCutoutCompletedEvent(
                    work.externalUserId(), work.task().id(), work.candidate().id(), imageBytes,
                    output.assetId(), output.version()));
        } catch (RuntimeException exception) {
            // The generated asset is already durable; a user can still inspect or confirm it later.
            log.warn("Could not publish fashion garment cutout completion, task={}", work.task().id(), exception);
        }
    }

    private void publishFailure(GarmentCutoutWork work, String failureSummary) {
        try {
            completionPublisher.publishEvent(new FashionGarmentCutoutFailedEvent(
                    work.externalUserId(), work.task().id(), work.candidate().id(), failureSummary));
        } catch (RuntimeException exception) {
            log.warn("Could not publish fashion garment cutout failure, task={}", work.task().id(), exception);
        }
    }

    private WardrobeItemDraft wardrobeDraft(ClothingCandidate candidate) {
        JsonNode attributes = attributes(candidate.analysisAttributesJson());
        return new WardrobeItemDraft(candidate.displayName(), parentCategory(candidate.categoryCode()),
                candidate.categoryCode(), candidate.colorPrimary(), candidate.secondaryColors(),
                candidate.styleTags(), candidate.fitCode(), text(attributes.path("patternCode").asText(), 64),
                candidate.seasonTags(), strings(attributes.path("occasionTags")), text(attributes.path("material").asText(), 128),
                "USER_CONFIRMED", text(attributes.path("notes").asText(), 512), "1.0.0",
                candidate.analysisAttributesJson(), confidence(candidate.analysisConfidence()));
    }

    private static String parentCategory(String category) {
        return switch (text(category, 64).toUpperCase(java.util.Locale.ROOT)) {
            case "T_SHIRT", "SHIRT", "KNITWEAR" -> "TOP";
            case "JACKET" -> "OUTERWEAR";
            case "JEANS", "STRAIGHT_PANTS", "SKIRT" -> "BOTTOM";
            case "DRESS" -> "ONE_PIECE";
            case "SNEAKERS", "LOAFERS" -> "SHOES";
            case "OUTFIT", "SUIT", "SET" -> "OUTFIT";
            default -> text(category, 64).toUpperCase(java.util.Locale.ROOT);
        };
    }

    private JsonNode attributes(String value) {
        try {
            JsonNode node = objectMapper.readTree(value == null || value.isBlank() ? "{}" : value);
            return node != null && node.isObject() ? node : objectMapper.createObjectNode();
        } catch (Exception ignored) {
            return objectMapper.createObjectNode();
        }
    }

    private static List<String> strings(JsonNode node) {
        if (node == null || !node.isArray()) return List.of();
        List<String> values = new ArrayList<>();
        node.forEach(value -> { if (value.isTextual() && !value.asText().isBlank()) values.add(text(value.asText(), 128)); });
        return List.copyOf(values);
    }

    private static void addLine(List<String> lines, String label, String value) {
        if (value != null && !value.isBlank()) lines.add("- " + label + "：" + value);
    }

    private static String labels(List<String> values, java.util.function.Function<String, String> mapper) {
        if (values == null || values.isEmpty()) return "";
        return values.stream().map(mapper).filter(value -> value != null && !value.isBlank()).distinct()
                .reduce((left, right) -> left + "、" + right).orElse("");
    }

    private static String categoryLabel(String value) {
        return switch (text(value, 64).toUpperCase(java.util.Locale.ROOT)) {
            case "T_SHIRT" -> "短袖上衣";
            case "SHIRT" -> "衬衫";
            case "KNITWEAR" -> "针织衫";
            case "JACKET" -> "外套";
            case "JEANS" -> "牛仔裤";
            case "STRAIGHT_PANTS" -> "长裤";
            case "SKIRT" -> "半身裙";
            case "DRESS" -> "连衣裙";
            case "OUTFIT", "SUIT", "SET" -> "整套穿搭";
            case "SHOES" -> "鞋子";
            case "BAG" -> "包";
            case "ACCESSORY" -> "配饰";
            default -> text(value, 64);
        };
    }

    private static String colorLabel(String value) {
        return switch (text(value, 64).toUpperCase(java.util.Locale.ROOT)) {
            case "BLACK" -> "黑色";
            case "WHITE" -> "白色";
            case "OFF_WHITE" -> "米白色";
            case "GRAY" -> "灰色";
            case "BLUE" -> "蓝色";
            case "DENIM_BLUE" -> "牛仔蓝";
            case "NAVY" -> "藏青色";
            case "BROWN" -> "棕色";
            case "KHAKI" -> "卡其色";
            case "RED" -> "红色";
            case "GREEN" -> "绿色";
            case "YELLOW" -> "黄色";
            default -> text(value, 64);
        };
    }

    private static String styleLabel(String value) {
        return switch (text(value, 64).toUpperCase(java.util.Locale.ROOT)) {
            case "MINIMAL" -> "简约";
            case "CASUAL" -> "休闲";
            case "COMMUTE" -> "通勤";
            default -> text(value, 64);
        };
    }

    private static String fitLabel(String value) {
        return switch (text(value, 64).toUpperCase(java.util.Locale.ROOT)) {
            case "RELAXED" -> "宽松";
            case "STRAIGHT" -> "直筒";
            case "SLIM" -> "修身";
            default -> text(value, 64);
        };
    }

    private static String seasonLabel(String value) {
        return switch (text(value, 64).toUpperCase(java.util.Locale.ROOT)) {
            case "SPRING" -> "春季";
            case "SUMMER" -> "夏季";
            case "AUTUMN" -> "秋季";
            case "WINTER" -> "冬季";
            default -> text(value, 64);
        };
    }

    private static String patternLabel(String value) {
        return switch (text(value, 64).toUpperCase(java.util.Locale.ROOT)) {
            case "SOLID" -> "纯色";
            case "STRIPED" -> "条纹";
            case "CHECKED" -> "格纹";
            case "PRINTED" -> "印花";
            default -> text(value, 64);
        };
    }

    private static String occasionLabel(String value) {
        return switch (text(value, 64).toUpperCase(java.util.Locale.ROOT)) {
            case "COMMUTE" -> "通勤";
            case "CASUAL" -> "日常休闲";
            case "DATE" -> "约会";
            case "FORMAL" -> "正式场合";
            case "SPORT" -> "运动";
            default -> text(value, 64);
        };
    }

    private static BigDecimal confidence(BigDecimal value) {
        return value == null || value.compareTo(BigDecimal.ZERO) < 0 || value.compareTo(BigDecimal.ONE) > 0
                ? BigDecimal.ZERO : value;
    }

    private static String text(String value, int limit) {
        String clean = value == null ? "" : value.replace('\u0000', ' ').strip();
        return clean.length() <= limit ? clean : clean.substring(0, limit);
    }

    public record IntakeResult(String summary, List<ClothingCandidate> candidates, boolean reusedExistingDrafts) { }
}
