package com.example.ykdsummer.bot.audio;

import com.example.ykdsummer.bot.config.AliyunTtsProperties;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** 管理 CosyVoice 的系统音色，并按微信用户保存当前选择。 */
@Service
public class TtsVoiceSelectionService {

    private static final String COSYVOICE_V3_FLASH = "cosyvoice-v3-flash";
    private static final List<VoiceOption> OPTIONS = List.of(
            voice("龙安洋", "longanyang", "阳光大男孩", "安洋"),
            voice("龙安欢", "longanhuan_v3", "欢脱元气女", "欢欢", "元气女声"),
            voice("龙呼呼", "longhuhu_v3", "天真烂漫女童", "呼呼", "女童"),
            voice("力豆", "longjielidou_v3", "阳光顽皮男童", "龙杰力豆", "男童", "童声"),
            voice("龙飞", "longfei_v3", "热血磁性男", "磁性男声"),
            voice("龙应聆", "longyingling_v3", "温和共情女", "应聆", "温和女声"),
            voice("龙小淳", "longxiaochun_v3", "知性积极女", "小淳", "知性女声"),
            voice("龙安昀", "longanyun_v3", "居家暖男", "安昀", "暖男"),
            voice("龙安温", "longanwen_v3", "优雅知性女", "安温"),
            voice("龙安朗", "longanlang_v3", "清爽利落男", "安朗"),
            voice("龙婉", "longwan_v3", "细腻柔声女", "柔声女声"),
            voice("龙安柔", "longanrou_v3", "温柔闺蜜女", "安柔", "闺蜜音"),
            voice("龙安智", "longanzhi_v3", "睿智轻熟男", "安智"),
            voice("龙安雅", "longanya_v3", "高雅气质女", "安雅"),
            voice("龙老铁", "longlaotie_v3", "东北直率男", "老铁", "东北话"),
            voice("龙安粤", "longanyue_v3", "欢脱粤语男", "安粤", "粤语")
    );

    private final AliyunTtsProperties properties;
    private final ConcurrentHashMap<String, String> userVoices = new ConcurrentHashMap<>();

    public TtsVoiceSelectionService(AliyunTtsProperties properties) {
        this.properties = properties;
    }

    /** 选择成功后立即影响该用户后续的“语音：”请求。 */
    public Optional<VoiceOption> select(String userId, String requestedVoice) {
        Optional<VoiceOption> selected = find(requestedVoice);
        selected.ifPresent(option -> userVoices.put(userId, option.voiceId()));
        return selected;
    }

    public VoiceOption current(String userId) {
        String voiceId = userVoices.getOrDefault(userId, properties.getVoice());
        return find(voiceId).orElseGet(() ->
                new VoiceOption("默认音色", properties.getModel(), voiceId, "配置的默认音色", List.of()));
    }

    public VoiceOption reset(String userId) {
        userVoices.remove(userId);
        return current(userId);
    }

    public String voiceId(String userId) {
        return current(userId).voiceId();
    }

    public String modelId(String userId) {
        return current(userId).modelId();
    }

    public String listMessage(String userId) {
        String currentVoiceId = voiceId(userId);
        StringBuilder message = new StringBuilder("可用音色：\n");
        for (int index = 0; index < OPTIONS.size(); index++) {
            VoiceOption option = OPTIONS.get(index);
            message.append(index + 1).append(". ").append(option.displayName())
                    .append(" - ").append(option.description());
            if (option.voiceId().equals(currentVoiceId)) {
                message.append("（当前）");
            }
            message.append('\n');
        }
        return message
                .append("\n切换：设置音色：龙婉\n")
                .append("查看：当前音色\n")
                .append("恢复：重置音色")
                .toString();
    }

    private static Optional<VoiceOption> find(String requestedVoice) {
        String normalized = normalize(requestedVoice);
        if (normalized.isEmpty()) {
            return Optional.empty();
        }
        return OPTIONS.stream()
                .filter(option -> option.matches(normalized))
                .findFirst();
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private static VoiceOption voice(String displayName, String voiceId, String description, String... aliases) {
        return new VoiceOption(displayName, COSYVOICE_V3_FLASH, voiceId, description, List.of(aliases));
    }

    public record VoiceOption(
            String displayName,
            String modelId,
            String voiceId,
            String description,
            List<String> aliases
    ) {
        public VoiceOption {
            aliases = List.copyOf(aliases);
        }

        private boolean matches(String normalized) {
            return normalize(displayName).equals(normalized)
                    || normalize(voiceId).equals(normalized)
                    || aliases.stream().map(TtsVoiceSelectionService::normalize).anyMatch(normalized::equals);
        }

        public String display() {
            return displayName + "（" + description + "）";
        }
    }
}
