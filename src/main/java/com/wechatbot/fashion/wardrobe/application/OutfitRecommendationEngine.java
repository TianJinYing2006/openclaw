package com.wechatbot.fashion.wardrobe.application;

import com.wechatbot.fashion.wardrobe.config.OutfitRecommendationProperties;
import com.wechatbot.fashion.wardrobe.domain.FashionAttributeNormalizer;
import com.wechatbot.fashion.wardrobe.domain.FashionReferenceGarment;
import com.wechatbot.fashion.wardrobe.domain.FashionUserPreference;
import com.wechatbot.fashion.wardrobe.domain.OutfitRecommendationRequest;
import com.wechatbot.fashion.wardrobe.domain.OutfitRecommendationResult;
import com.wechatbot.fashion.wardrobe.domain.SemanticReferenceGarmentMatch;
import com.wechatbot.fashion.wardrobe.domain.WardrobeItem;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/** Deterministic evidence aggregation and ranking. It has no LLM, vector-store, image, or channel dependency. */
@Component
public class OutfitRecommendationEngine {
    private static final Set<String> ROLES = Set.of("TOP", "BOTTOM", "OUTERWEAR");
    private final OutfitRecommendationProperties properties;

    public OutfitRecommendationEngine(OutfitRecommendationProperties properties) {
        this.properties = properties;
    }

    public EngineResult recommend(
            WardrobeItem anchor,
            List<WardrobeItem> wardrobe,
            List<SemanticReferenceGarmentMatch> publicMatches,
            List<FashionUserPreference> preferences,
            Set<Long> recentlyRecommendedItemIds,
            OutfitRecommendationRequest request
    ) {
        String anchorRole = role(anchor);
        if (!ROLES.contains(anchorRole)) {
            return new EngineResult(List.of(), new OutfitRecommendationResult.MissingItem(
                    "", "当前单品类型暂不支持自动整套搭配", 0, 0d));
        }
        List<Pattern> patterns = patterns(anchor, anchorRole, publicMatches);
        List<String> requiredRoles = requiredRoles(anchorRole);
        if (patterns.isEmpty()) {
            return insufficientEvidence();
        }
        patterns = patterns.stream().filter(pattern ->
                requiredRoles.stream().allMatch(pattern.companions()::containsKey)).toList();
        if (patterns.isEmpty()) {
            return insufficientEvidence();
        }

        Map<String, List<WardrobeItem>> wardrobeByRole = wardrobe.stream()
                .filter(item -> item.id() != anchor.id())
                .filter(item -> ROLES.contains(role(item)))
                .collect(Collectors.groupingBy(OutfitRecommendationEngine::role,
                        LinkedHashMap::new, Collectors.toList()));
        List<Candidate> generated = new ArrayList<>();
        for (Pattern pattern : patterns) {
            Map<String, List<ScoredItem>> matchesByRole = new LinkedHashMap<>();
            boolean complete = true;
            for (String requiredRole : requiredRoles) {
                FashionReferenceGarment evidence = pattern.companions().get(requiredRole);
                List<ScoredItem> matches = evidence == null ? List.of()
                        : bestMatches(wardrobeByRole.getOrDefault(requiredRole, List.of()), evidence);
                if (matches.isEmpty()) {
                    complete = false;
                    break;
                }
                matchesByRole.put(requiredRole, matches.stream().limit(3).toList());
            }
            if (!complete) continue;

            String optionalRole = optionalRole(anchorRole, request);
            if (!optionalRole.isBlank() && pattern.companions().containsKey(optionalRole)) {
                List<ScoredItem> optional = bestMatches(wardrobeByRole.getOrDefault(optionalRole, List.of()),
                        pattern.companions().get(optionalRole));
                if (!optional.isEmpty()) matchesByRole.put(optionalRole, optional.stream().limit(1).toList());
            }
            List<Map<String, ScoredItem>> combinations = combinations(matchesByRole);
            for (Map<String, ScoredItem> selected : combinations) {
                Map<String, WardrobeItem> items = new LinkedHashMap<>();
                items.put(anchorRole, anchor);
                selected.forEach((key, value) -> items.put(key, value.item()));
                generated.add(score(items, pattern, selected, request, preferences,
                        recentlyRecommendedItemIds == null ? Set.of() : recentlyRecommendedItemIds));
            }
        }

        List<Candidate> deduplicated = deduplicate(generated);
        List<Candidate> diversified = diversify(deduplicated, request.maxResults());
        if (diversified.isEmpty()) {
            String missingRole = requiredRoles.stream()
                    .filter(value -> wardrobeByRole.getOrDefault(value, List.of()).isEmpty())
                    .findFirst().orElse(requiredRoles.getFirst());
            return new EngineResult(List.of(), missing(missingRole, patterns));
        }
        return new EngineResult(diversified, null);
    }

    private static EngineResult insufficientEvidence() {
        return new EngineResult(List.of(), new OutfitRecommendationResult.MissingItem(
                "", "暂时没有足够的公共穿搭证据，无法可靠判断衣橱缺少哪件单品", 0, 0d));
    }

    private List<Pattern> patterns(
            WardrobeItem anchor, String anchorRole, List<SemanticReferenceGarmentMatch> matches
    ) {
        Map<Long, Pattern> patterns = new LinkedHashMap<>();
        if (matches == null) return List.of();
        for (SemanticReferenceGarmentMatch match : matches) {
            if (match == null || match.look() == null || match.garment() == null
                    || !anchorRole.equals(role(match.garment()))) continue;
            Map<String, FashionReferenceGarment> companions = match.look().garments().stream()
                    .filter(value -> !anchorRole.equals(role(value)))
                    .filter(value -> ROLES.contains(role(value)))
                    .collect(Collectors.toMap(OutfitRecommendationEngine::role, Function.identity(),
                            (left, right) -> left, LinkedHashMap::new));
            if (companions.isEmpty()) continue;
            double structuredSupport = attributeSimilarity(anchor, match.garment());
            double vectorSupport = match.score() < 0d ? structuredSupport * 0.85d
                    : unit(unit(match.score()) * 0.65d + structuredSupport * 0.35d);
            Pattern current = new Pattern(match.look().id(), match.look().referenceCode(),
                    vectorSupport, companions);
            Pattern previous = patterns.get(current.lookId());
            if (previous == null || current.anchorSupport() > previous.anchorSupport()) {
                patterns.put(current.lookId(), current);
            }
        }
        return patterns.values().stream()
                .sorted(Comparator.comparingDouble(Pattern::anchorSupport).reversed())
                .limit(properties.getPublicCandidateLimit()).toList();
    }

    private List<ScoredItem> bestMatches(List<WardrobeItem> candidates, FashionReferenceGarment evidence) {
        return candidates.stream()
                .map(item -> new ScoredItem(item, attributeSimilarity(item, evidence)))
                .filter(value -> value.similarity() >= properties.getMinimumCompanionSimilarity())
                .sorted(Comparator.comparingDouble(ScoredItem::similarity).reversed()
                        .thenComparingLong(value -> value.item().id()))
                .limit(properties.getWardrobeCandidatesPerRole())
                .toList();
    }

    private Candidate score(
            Map<String, WardrobeItem> items,
            Pattern pattern,
            Map<String, ScoredItem> selected,
            OutfitRecommendationRequest request,
            List<FashionUserPreference> preferences,
            Set<Long> recent
    ) {
        Map<String, Double> dimensions = new LinkedHashMap<>();
        Map<String, Double> weights = new LinkedHashMap<>();

        double companionSupport = selected.values().stream().mapToDouble(ScoredItem::similarity)
                .average().orElse(0d);
        dimensions.put("evidence", unit(pattern.anchorSupport() * 0.45d + companionSupport * 0.55d));
        weights.put("evidence", properties.getEvidenceWeight());

        dimension(dimensions, weights, "occasion", tagMatch(items.values(), request.occasionTags(),
                WardrobeItem::occasionTags), properties.getOccasionWeight());
        List<String> seasons = request.seasonTags().isEmpty()
                ? inferSeasons(request.weatherSummary()) : request.seasonTags();
        dimension(dimensions, weights, "seasonWeather", tagMatch(items.values(), seasons,
                WardrobeItem::seasonTags), properties.getSeasonWeatherWeight());
        dimension(dimensions, weights, "color", colorHarmony(items.values()), properties.getColorWeight());
        dimension(dimensions, weights, "style", styleMatch(items.values(), request.styleTags()),
                properties.getStyleWeight());
        dimension(dimensions, weights, "fitFormality", fitFormality(selected, pattern, request.occasionTags()),
                properties.getFitFormalityWeight());
        dimension(dimensions, weights, "preference", preferenceMatch(items.values(), preferences),
                properties.getPreferenceWeight());
        dimension(dimensions, weights, "novelty", novelty(
                        selected.values().stream().map(ScoredItem::item).toList(), recent),
                properties.getNoveltyWeight());

        double effectiveWeight = weights.values().stream().mapToDouble(Double::doubleValue).sum();
        double total = effectiveWeight <= 0d ? 0d : dimensions.entrySet().stream()
                .mapToDouble(entry -> entry.getValue() * weights.getOrDefault(entry.getKey(), 0d)).sum()
                / effectiveWeight * 100d;
        List<OutfitRecommendationResult.Evidence> evidence = selected.entrySet().stream().map(entry -> {
            FashionReferenceGarment garment = pattern.companions().get(entry.getKey());
            return new OutfitRecommendationResult.Evidence(pattern.lookId(), pattern.referenceCode(),
                    garment.subCategoryCode(), garment.displayName(),
                    unit(pattern.anchorSupport() * entry.getValue().similarity()));
        }).toList();
        return new Candidate(items, round(total), dimensions, evidence);
    }

    private List<Candidate> deduplicate(List<Candidate> values) {
        Map<String, Candidate> byKey = new LinkedHashMap<>();
        values.stream().sorted(Comparator.comparingDouble(Candidate::totalScore).reversed())
                .forEach(candidate -> byKey.merge(candidate.key(), candidate, (left, right) -> {
                    List<OutfitRecommendationResult.Evidence> merged = java.util.stream.Stream
                            .concat(left.evidence().stream(), right.evidence().stream())
                            .collect(Collectors.toMap(value -> value.referenceLookId() + ":" + value.companionCategory(),
                                    Function.identity(), (a, b) -> a.support() >= b.support() ? a : b,
                                    LinkedHashMap::new)).values().stream().limit(6).toList();
                    Candidate base = left.totalScore() >= right.totalScore() ? left : right;
                    Map<String, Double> breakdown = new LinkedHashMap<>(base.breakdown());
                    long supportingLooks = merged.stream().map(OutfitRecommendationResult.Evidence::referenceLookId)
                            .distinct().count();
                    double evidence = breakdown.getOrDefault("evidence", 0d);
                    breakdown.put("evidence", unit(evidence + Math.min(0.12d,
                            Math.max(0L, supportingLooks - 1L) * 0.04d)));
                    return new Candidate(base.items(), weightedTotal(breakdown), breakdown, merged);
                }));
        return byKey.values().stream().sorted(Comparator.comparingDouble(Candidate::totalScore).reversed()
                .thenComparing(Candidate::key)).toList();
    }

    private double weightedTotal(Map<String, Double> dimensions) {
        double weighted = 0d;
        double effectiveWeight = 0d;
        for (Map.Entry<String, Double> entry : dimensions.entrySet()) {
            double weight = switch (entry.getKey()) {
                case "evidence" -> properties.getEvidenceWeight();
                case "occasion" -> properties.getOccasionWeight();
                case "seasonWeather" -> properties.getSeasonWeatherWeight();
                case "color" -> properties.getColorWeight();
                case "style" -> properties.getStyleWeight();
                case "fitFormality" -> properties.getFitFormalityWeight();
                case "preference" -> properties.getPreferenceWeight();
                case "novelty" -> properties.getNoveltyWeight();
                default -> 0d;
            };
            if (weight <= 0d) continue;
            weighted += unit(entry.getValue()) * weight;
            effectiveWeight += weight;
        }
        return round(effectiveWeight == 0d ? 0d : weighted / effectiveWeight * 100d);
    }

    private List<Candidate> diversify(List<Candidate> candidates, int limit) {
        List<Candidate> selected = new ArrayList<>();
        Set<Long> usedCompanions = new HashSet<>();
        List<Candidate> remaining = new ArrayList<>(candidates);
        while (!remaining.isEmpty() && selected.size() < Math.max(1, Math.min(limit, 3))) {
            Candidate best = remaining.stream().max(Comparator.<Candidate>comparingDouble(value -> {
                long repeated = value.items().values().stream().map(WardrobeItem::id)
                        .filter(usedCompanions::contains).count();
                return value.totalScore() - repeated * 4d;
            }).thenComparing(Candidate::key, Comparator.reverseOrder())).orElseThrow();
            selected.add(best);
            best.items().values().stream().map(WardrobeItem::id).forEach(usedCompanions::add);
            remaining.remove(best);
        }
        return List.copyOf(selected);
    }

    private OutfitRecommendationResult.MissingItem missing(String role, List<Pattern> patterns) {
        List<FashionReferenceGarment> evidence = patterns.stream()
                .map(value -> value.companions().get(role)).filter(java.util.Objects::nonNull).toList();
        MissingDescriptor descriptor = evidence.stream().map(value -> new MissingDescriptor(
                        token(value.subCategoryCode()), token(value.colorPrimary()), token(value.fitCode())))
                .collect(Collectors.groupingBy(Function.identity(), LinkedHashMap::new, Collectors.counting()))
                .entrySet().stream()
                .max(Map.Entry.<MissingDescriptor, Long>comparingByValue()
                        .thenComparing(entry -> entry.getKey().key()))
                .map(Map.Entry::getKey)
                .orElse(new MissingDescriptor(token(role), "", ""));
        String summary = String.join("", List.of(label(descriptor.color()), label(descriptor.fit()),
                label(descriptor.subCategory()))).strip();
        if (summary.isBlank()) summary = label(role);
        double confidence = patterns.isEmpty() ? 0d : Math.min(1d, evidence.size() / 5d);
        return new OutfitRecommendationResult.MissingItem(role,
                "衣橱里暂时缺少适合这件衣服的" + summary, evidence.size(), confidence);
    }

    private static List<Map<String, ScoredItem>> combinations(Map<String, List<ScoredItem>> byRole) {
        List<Map<String, ScoredItem>> result = new ArrayList<>();
        result.add(new LinkedHashMap<>());
        for (Map.Entry<String, List<ScoredItem>> entry : byRole.entrySet()) {
            List<Map<String, ScoredItem>> next = new ArrayList<>();
            for (Map<String, ScoredItem> current : result) {
                for (ScoredItem item : entry.getValue()) {
                    Map<String, ScoredItem> copy = new LinkedHashMap<>(current);
                    copy.put(entry.getKey(), item);
                    next.add(copy);
                }
            }
            result = next;
        }
        return result;
    }

    static double attributeSimilarity(WardrobeItem item, FashionReferenceGarment evidence) {
        WeightedScore score = new WeightedScore();
        score.add(0.25d, role(item).equals(role(evidence)) ? 1d : 0d);
        score.add(0.20d, scalar(item.colorPrimary(), evidence.colorPrimary()));
        score.add(0.15d, overlap(item.styleTags(), evidence.styleTags()));
        score.add(0.10d, scalar(item.fitCode(), evidence.fitCode()));
        score.add(0.10d, scalar(item.patternCode(), evidence.patternCode()));
        score.add(0.10d, overlap(item.seasonTags(), evidence.seasonTags()));
        score.add(0.05d, overlap(item.occasionTags(), evidence.occasionTags()));
        score.add(0.05d, scalar(item.material(),
                evidence.materialTags().isEmpty() ? "" : evidence.materialTags().getFirst()));
        return score.value();
    }

    private static Double tagMatch(
            java.util.Collection<WardrobeItem> items,
            List<String> requested,
            Function<WardrobeItem, List<String>> extractor
    ) {
        Set<String> expected = tokens(requested);
        if (expected.isEmpty()) return null;
        List<Set<String>> actual = items.stream().map(extractor).map(OutfitRecommendationEngine::tokens)
                .filter(value -> !value.isEmpty()).toList();
        if (actual.isEmpty()) return null;
        return actual.stream().mapToDouble(value -> intersection(value, expected) / (double) expected.size())
                .average().orElse(0d);
    }

    private static Double styleMatch(java.util.Collection<WardrobeItem> items, List<String> requested) {
        Set<String> expected = tokens(requested);
        List<Set<String>> actual = items.stream().map(WardrobeItem::styleTags)
                .map(OutfitRecommendationEngine::tokens).filter(value -> !value.isEmpty()).toList();
        if (!expected.isEmpty()) {
            if (actual.isEmpty()) return null;
            return actual.stream().mapToDouble(value -> intersection(value, expected) / (double) expected.size())
                    .average().orElse(0d);
        }
        if (actual.size() < 2) return null;
        double total = 0d;
        int pairs = 0;
        for (int left = 0; left < actual.size(); left++) {
            for (int right = left + 1; right < actual.size(); right++) {
                total += jaccard(actual.get(left), actual.get(right));
                pairs++;
            }
        }
        return pairs == 0 ? null : total / pairs;
    }

    private static Double colorHarmony(java.util.Collection<WardrobeItem> items) {
        List<String> colors = items.stream().map(WardrobeItem::colorPrimary).map(OutfitRecommendationEngine::token)
                .filter(value -> !value.isBlank()).toList();
        if (colors.size() < 2) return null;
        double total = 0d;
        int pairs = 0;
        for (int left = 0; left < colors.size(); left++) {
            for (int right = left + 1; right < colors.size(); right++) {
                total += colorPair(colors.get(left), colors.get(right));
                pairs++;
            }
        }
        return total / pairs;
    }

    private static Double fitFormality(
            Map<String, ScoredItem> selected, Pattern pattern, List<String> occasionTags
    ) {
        if (selected.isEmpty()) return null;
        List<Double> fitValues = selected.entrySet().stream().map(entry -> {
            FashionReferenceGarment evidence = pattern.companions().get(entry.getKey());
            return scalar(entry.getValue().item().fitCode(), evidence == null ? "" : evidence.fitCode());
        }).filter(java.util.Objects::nonNull).toList();
        Double fit = fitValues.isEmpty() ? null
                : fitValues.stream().mapToDouble(Double::doubleValue).average().orElse(0d);
        Set<String> occasions = tokens(occasionTags);
        boolean formal = occasions.contains("FORMAL") || occasions.contains("INTERVIEW")
                || occasions.contains("BUSINESS");
        if (!formal) return fit;
        List<Integer> formalityValues = pattern.companions().values().stream()
                .map(FashionReferenceGarment::formalityLevel).filter(value -> value > 0).toList();
        Double formality = formalityValues.isEmpty() ? null
                : formalityValues.stream().mapToInt(Integer::intValue).average().orElse(0d) / 5d;
        if (fit == null) return formality == null ? null : unit(formality);
        if (formality == null) return unit(fit);
        return unit(fit * 0.6d + formality * 0.4d);
    }

    private static Double preferenceMatch(
            java.util.Collection<WardrobeItem> items, List<FashionUserPreference> preferences
    ) {
        if (preferences == null || preferences.isEmpty()) return null;
        double score = 0d;
        double weight = 0d;
        for (FashionUserPreference preference : preferences) {
            double valueWeight = preference.weight() == null ? 1d : preference.weight().doubleValue();
            double confidence = preference.confidence() == null ? 1d : preference.confidence().doubleValue();
            double effective = Math.max(0d, valueWeight * confidence);
            if (effective == 0d) continue;
            Set<String> actual = items.stream()
                    .flatMap(item -> preferenceValues(item, preference.dimensionCode()).stream())
                    .filter(value -> !value.isBlank()).collect(Collectors.toSet());
            if (actual.isEmpty()) continue;
            boolean matches = actual.contains(token(preference.valueCode()));
            boolean negative = "NEGATIVE".equalsIgnoreCase(preference.polarity());
            double preferenceScore = negative ? (matches ? 0d : 1d) : (matches ? 1d : 0d);
            score += preferenceScore * effective;
            weight += effective;
        }
        return weight == 0d ? null : unit(score / weight);
    }

    private static Double novelty(java.util.Collection<WardrobeItem> items, Set<Long> recent) {
        if (recent == null || recent.isEmpty()) return null;
        long repeated = items.stream().map(WardrobeItem::id).filter(recent::contains).count();
        return 1d - repeated / (double) items.size();
    }

    private static List<String> inferSeasons(String weatherSummary) {
        String value = safe(weatherSummary);
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("(-?\\d{1,2})(?:\\s*[-~到至]\\s*(-?\\d{1,2}))?")
                .matcher(value);
        if (!matcher.find()) return List.of();
        int first = Integer.parseInt(matcher.group(1));
        int second = matcher.group(2) == null ? first : Integer.parseInt(matcher.group(2));
        double average = (first + second) / 2d;
        if (average >= 26d) return List.of("SUMMER");
        if (average <= 12d) return List.of("WINTER");
        return average >= 20d ? List.of("SPRING", "AUTUMN") : List.of("AUTUMN", "SPRING");
    }

    private static Set<String> preferenceValues(WardrobeItem item, String dimension) {
        return switch (token(dimension)) {
            case "COLOR" -> tokens(java.util.stream.Stream.concat(
                    java.util.stream.Stream.of(item.colorPrimary()), item.secondaryColors().stream()).toList());
            case "STYLE" -> tokens(item.styleTags());
            case "FIT" -> Set.of(token(item.fitCode()));
            case "PATTERN" -> Set.of(token(item.patternCode()));
            case "MATERIAL" -> Set.of(token(item.material()));
            case "CATEGORY" -> Set.of(token(item.parentCategoryCode()), token(item.categoryCode()));
            default -> Set.of();
        };
    }

    private static List<String> requiredRoles(String anchorRole) {
        return switch (anchorRole) {
            case "TOP" -> List.of("BOTTOM");
            case "BOTTOM" -> List.of("TOP");
            case "OUTERWEAR" -> List.of("TOP", "BOTTOM");
            default -> List.of("BOTTOM");
        };
    }

    private static String optionalRole(String anchorRole, OutfitRecommendationRequest request) {
        if (!Set.of("TOP", "BOTTOM").contains(anchorRole)) return "";
        Set<String> seasons = tokens(request.seasonTags().isEmpty()
                ? inferSeasons(request.weatherSummary()) : request.seasonTags());
        return seasons.contains("SUMMER") ? "" : "OUTERWEAR";
    }

    static String role(WardrobeItem item) {
        return normalizedRole(item == null ? "" : !safe(item.parentCategoryCode()).isBlank()
                ? item.parentCategoryCode() : item.categoryCode());
    }

    static String role(FashionReferenceGarment garment) {
        return normalizedRole(garment == null ? "" : garment.categoryCode());
    }

    private static String normalizedRole(String value) {
        return switch (token(value)) {
            case "T_SHIRT", "SHIRT", "KNITWEAR", "TOP" -> "TOP";
            case "JEANS", "STRAIGHT_PANTS", "PANTS", "SKIRT", "BOTTOM" -> "BOTTOM";
            case "JACKET", "COAT", "OUTERWEAR" -> "OUTERWEAR";
            default -> token(value);
        };
    }

    private static void dimension(
            Map<String, Double> dimensions, Map<String, Double> weights,
            String name, Double value, double weight
    ) {
        if (value == null || weight <= 0d) return;
        dimensions.put(name, unit(value));
        weights.put(name, weight);
    }

    private static Double scalar(String left, String right) {
        String a = token(left);
        String b = token(right);
        return a.isBlank() || b.isBlank() ? null : a.equals(b) ? 1d : 0d;
    }

    private static Double overlap(List<String> left, List<String> right) {
        Set<String> a = tokens(left);
        Set<String> b = tokens(right);
        return a.isEmpty() || b.isEmpty() ? null : jaccard(a, b);
    }

    private static double colorPair(String left, String right) {
        if (left.equals(right)) return 0.82d;
        Set<String> neutral = Set.of("BLACK", "WHITE", "GRAY", "LIGHT_GRAY", "DARK_GRAY", "CHARCOAL",
                "NAVY", "BEIGE", "OFF_WHITE", "KHAKI", "BROWN", "DENIM_BLUE", "BLUE_GRAY");
        if (neutral.contains(left) || neutral.contains(right)) return 0.92d;
        Set<String> warm = Set.of("RED", "ORANGE", "YELLOW", "BROWN", "CAMEL", "KHAKI");
        Set<String> cool = Set.of("BLUE", "NAVY", "GREEN", "PURPLE", "BLUE_GRAY");
        return warm.contains(left) && warm.contains(right) || cool.contains(left) && cool.contains(right)
                ? 0.78d : 0.55d;
    }

    private static double jaccard(Set<String> left, Set<String> right) {
        Set<String> union = new HashSet<>(left);
        union.addAll(right);
        return union.isEmpty() ? 0d : intersection(left, right) / (double) union.size();
    }

    private static int intersection(Set<String> left, Set<String> right) {
        Set<String> copy = new HashSet<>(left);
        copy.retainAll(right);
        return copy.size();
    }

    private static Set<String> tokens(List<String> values) {
        return FashionAttributeNormalizer.tokens(values);
    }

    private static Set<String> tokens(String... values) {
        return tokens(values == null ? List.of() : List.of(values));
    }

    private static String token(String value) {
        return FashionAttributeNormalizer.token(value);
    }

    private static double unit(double value) {
        return Double.isFinite(value) ? Math.max(0d, Math.min(value, 1d)) : 0d;
    }

    private static double round(double value) { return Math.round(value * 10_000d) / 10_000d; }
    private static String safe(String value) { return value == null ? "" : value.replace('\u0000', ' ').strip(); }

    private static String label(String value) {
        return switch (token(value)) {
            case "TOP" -> "上衣";
            case "BOTTOM" -> "下装";
            case "OUTERWEAR" -> "外套";
            case "T_SHIRT" -> "T恤";
            case "SHIRT" -> "衬衫";
            case "JEANS" -> "牛仔裤";
            case "STRAIGHT_PANTS" -> "直筒裤";
            case "BLACK" -> "黑色";
            case "WHITE" -> "白色";
            case "GRAY" -> "灰色";
            case "LIGHT_GRAY" -> "浅灰色";
            case "DARK_GRAY" -> "深灰色";
            case "NAVY" -> "藏青色";
            case "DENIM_BLUE" -> "牛仔蓝";
            case "KHAKI" -> "卡其色";
            case "RELAXED" -> "宽松";
            case "STRAIGHT" -> "直筒";
            case "SLIM" -> "修身";
            default -> safe(value);
        };
    }

    public record EngineResult(
            List<Candidate> candidates,
            OutfitRecommendationResult.MissingItem missingItem
    ) {
        public EngineResult {
            candidates = candidates == null ? List.of() : List.copyOf(candidates);
        }
    }

    public record Candidate(
            Map<String, WardrobeItem> items,
            double totalScore,
            Map<String, Double> breakdown,
            List<OutfitRecommendationResult.Evidence> evidence
    ) {
        public Candidate {
            items = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(items));
            breakdown = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(breakdown));
            evidence = List.copyOf(evidence);
        }
        String key() {
            return items.entrySet().stream().sorted(Map.Entry.comparingByKey())
                    .map(value -> value.getKey() + "=" + value.getValue().id())
                    .collect(Collectors.joining("|"));
        }
    }

    private record Pattern(
            long lookId,
            String referenceCode,
            double anchorSupport,
            Map<String, FashionReferenceGarment> companions
    ) { }

    private record ScoredItem(WardrobeItem item, double similarity) { }

    private record MissingDescriptor(String subCategory, String color, String fit) {
        String key() { return subCategory + "|" + color + "|" + fit; }
    }

    private static final class WeightedScore {
        private double weighted;
        private double weight;
        void add(double nextWeight, Double value) {
            if (value == null || nextWeight <= 0d) return;
            weighted += nextWeight * unit(value);
            weight += nextWeight;
        }
        double value() { return weight == 0d ? 0d : weighted / weight; }
    }
}
