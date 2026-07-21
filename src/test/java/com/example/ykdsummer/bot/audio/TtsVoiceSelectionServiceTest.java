package com.example.ykdsummer.bot.audio;

import com.example.ykdsummer.bot.config.AliyunTtsProperties;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TtsVoiceSelectionServiceTest {

    @Test
    void selectsVoicePerUserAndCanResetToDefault() {
        AliyunTtsProperties properties = new AliyunTtsProperties();
        TtsVoiceSelectionService service = new TtsVoiceSelectionService(properties);

        assertThat(service.current("user-a").voiceId()).isEqualTo("longanyang");
        assertThat(service.select("user-a", "龙婉")).isPresent();
        assertThat(service.voiceId("user-a")).isEqualTo("longwan_v3");
        assertThat(service.modelId("user-a")).isEqualTo("cosyvoice-v3-flash");
        assertThat(service.voiceId("user-b")).isEqualTo("longanyang");
        assertThat(service.select("user-a", "不存在的音色")).isEmpty();
        assertThat(service.reset("user-a").voiceId()).isEqualTo("longanyang");
    }

    @Test
    void listsCommandsAndMarksCurrentVoice() {
        TtsVoiceSelectionService service = new TtsVoiceSelectionService(new AliyunTtsProperties());

        assertThat(service.listMessage("user"))
                .contains("龙安洋 - 阳光大男孩（当前）", "设置音色：龙婉", "重置音色");
    }
}
