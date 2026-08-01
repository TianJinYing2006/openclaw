package com.example.ykdsummer.fashion.runtime;

import com.example.ykdsummer.fashion.domain.WardrobeItem;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.springframework.stereotype.Component;

/** Short-lived, user-isolated lookup from a displayed 2 x 2 preview position to a wardrobe item. */
@Component
public class FashionWardrobePreviewSelectionStore {
    private static final int PAGE_SIZE = 4;
    private final Cache<String, PreviewSession> sessions = Caffeine.newBuilder()
            .maximumSize(10_000)
            .expireAfterWrite(Duration.ofMinutes(30))
            .build();

    public void replace(String externalUserId, List<WardrobeItem> displayed) {
        if (safe(externalUserId).isBlank()) return;
        List<PreviewSlot> slots = new ArrayList<>();
        if (displayed != null) {
            for (int index = 0; index < displayed.size(); index++) {
                WardrobeItem item = displayed.get(index);
                if (item == null) continue;
                slots.add(new PreviewSlot(index / PAGE_SIZE + 1, Slot.byIndex(index % PAGE_SIZE), item.id()));
            }
        }
        if (slots.isEmpty()) sessions.invalidate(externalUserId); else sessions.put(externalUserId, new PreviewSession(List.copyOf(slots)));
    }

    public Optional<Selection> select(String externalUserId, int pageNumber, String position) {
        Slot slot = Slot.parse(position).orElse(null);
        if (slot == null || pageNumber < 1) return Optional.empty();
        PreviewSession session = sessions.getIfPresent(externalUserId);
        if (session == null) return Optional.empty();
        return session.slots().stream()
                .filter(value -> value.pageNumber() == pageNumber && value.slot() == slot)
                .findFirst()
                .map(value -> new Selection(value.pageNumber(), value.slot(), value.wardrobeItemId()));
    }

    public enum Slot {
        TOP_LEFT("左上"), TOP_RIGHT("右上"), BOTTOM_LEFT("左下"), BOTTOM_RIGHT("右下");
        private final String displayName;
        Slot(String displayName) { this.displayName = displayName; }
        public String displayName() { return displayName; }
        static Slot byIndex(int index) { return values()[Math.max(0, Math.min(index, values().length - 1))]; }
        static Optional<Slot> parse(String value) {
            return switch (safe(value).replace(" ", "").toUpperCase(Locale.ROOT)) {
                case "左上", "TOP_LEFT", "TOPLEFT", "1" -> Optional.of(TOP_LEFT);
                case "右上", "TOP_RIGHT", "TOPRIGHT", "2" -> Optional.of(TOP_RIGHT);
                case "左下", "BOTTOM_LEFT", "BOTTOMLEFT", "3" -> Optional.of(BOTTOM_LEFT);
                case "右下", "BOTTOM_RIGHT", "BOTTOMRIGHT", "4" -> Optional.of(BOTTOM_RIGHT);
                default -> Optional.empty();
            };
        }
    }

    public record Selection(int pageNumber, Slot slot, long wardrobeItemId) { }
    private record PreviewSlot(int pageNumber, Slot slot, long wardrobeItemId) { }
    private record PreviewSession(List<PreviewSlot> slots) { }
    private static String safe(String value) { return value == null ? "" : value.replace('\u0000', ' ').strip(); }
}
