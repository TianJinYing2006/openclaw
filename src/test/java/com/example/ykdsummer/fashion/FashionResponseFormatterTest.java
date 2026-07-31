package com.example.ykdsummer.fashion;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.ykdsummer.fashion.model.FashionResult;
import java.util.List;
import org.junit.jupiter.api.Test;

class FashionResponseFormatterTest {

    @Test
    void keepsWechatCopyClearAndCompact() {
        FashionResult result = new FashionResult(
                "通勤",
                "针织短袖 + 西装裤 + 乐福鞋",
                "正式度刚好，适合办公室。",
                List.of("带薄外套。"),
                List.of("衬衫 + 直筒裤。"),
                List.of("避免拖鞋。"),
                "模拟管道完成。"
        );

        String message = new FashionResponseFormatter().format(result);

        assertThat(message)
                .startsWith("穿搭建议｜通勤")
                .contains("最终方案\n- 针织短袖 + 西装裤 + 乐福鞋")
                .contains("推荐理由\n- 正式度刚好，适合办公室。")
                .contains("实用建议\n- 带薄外套。")
                .contains("备选风格\n- 衬衫 + 直筒裤。")
                .contains("注意避雷\n- 避免拖鞋。")
                .contains("小结\n- 模拟管道完成。");
        assertThat(message.length()).isLessThan(280);
    }
}
