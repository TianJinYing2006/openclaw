package com.example.ykdsummer.fashion.agent;

import com.example.ykdsummer.fashion.model.FashionRequest;
import com.example.ykdsummer.fashion.model.FashionResult;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * Local demo coordinator used until the full multi-agent fashion pipeline is wired in.
 */
@Service
public class DemoFashionConsultationCoordinator implements FashionConsultationCoordinator {

    @Override
    public FashionResult consult(FashionRequest request) {
        String input = request.userInput();
        if (containsAny(input, "婚礼", "婚宴", "伴娘", "正式")) {
            return weddingResult();
        }
        if (containsAny(input, "通勤", "上班", "办公室", "开会")) {
            return commuteResult();
        }
        if (containsAny(input, "海边", "沙滩", "海岛", "度假")) {
            return beachResult();
        }
        if (containsAny(input, "约会", "晚餐", "见面")) {
            return dateResult();
        }
        return dailyResult();
    }

    private static boolean containsAny(String input, String... keywords) {
        if (input == null) {
            return false;
        }
        for (String keyword : keywords) {
            if (input.contains(keyword)) {
                return true;
            }
        }
        return false;
    }

    private static FashionResult beachResult() {
        return new FashionResult(
                "海边度假",
                "浅蓝亚麻短袖衬衫 + 米白高腰百慕大短裤 + 棕色防滑凉鞋 + 草编包和墨镜",
                "亚麻和短裤透气耐晒，浅蓝与米白有海边清爽感，棕色配饰能压住整体质感。",
                List.of("带一件薄防晒衫，进室内或傍晚降温时直接叠穿。", "鞋底选防滑纹路，沙滩和湿地都更稳。"),
                List.of("更甜一点：吊带连衣裙 + 短罩衫。", "更利落一点：白色背心 + 卡其工装短裤。"),
                List.of("避免厚重牛仔和不防滑的高跟鞋。"),
                "已按海边、夏季、低正式度模拟完整穿搭管道。"
        );
    }

    private static FashionResult weddingResult() {
        return new FashionResult(
                "婚礼宾客",
                "香槟粉雪纺衬衫 + 雾蓝缎面半裙 + 裸色中跟鞋 + 珍珠耳钉",
                "配色柔和但不抢新娘风头，缎面半裙保留仪式感，中跟鞋兼顾拍照和久站。",
                List.of("包包选小号手拿包或腋下包，颜色跟鞋子接近。", "妆发保持干净精致，首饰控制在一到两处。"),
                List.of("更端庄：浅灰西装裙套装。", "更浪漫：豆沙色茶歇裙 + 细带凉鞋。"),
                List.of("避免纯白大面积上装或拖地礼服，户外婚礼也别选过细鞋跟。"),
                "已按婚礼、半正式、避开纯白规则模拟完整穿搭管道。"
        );
    }

    private static FashionResult commuteResult() {
        return new FashionResult(
                "日常通勤",
                "雾灰针织短袖 + 黑色垂坠西装裤 + 黑色乐福鞋 + 小号托特包",
                "上身柔和、下身利落，适合办公室和临时会议；黑灰组合稳定耐看，也方便复穿。",
                List.of("怕空调冷就加一件薄西装或开衫。", "裤长落在鞋面附近，显得更精神。"),
                List.of("更轻松：条纹衬衫 + 直筒牛仔裤。", "更正式：白衬衫 + 深蓝西裤。"),
                List.of("避免过透、过短或大面积亮片材质。"),
                "已按通勤、中等正式度、舒适复穿模拟完整穿搭管道。"
        );
    }

    private static FashionResult dateResult() {
        return new FashionResult(
                "约会晚餐",
                "黑色方领上衣 + 深靛直筒牛仔裤 + 酒红玛丽珍鞋 + 细项链",
                "方领修饰肩颈，深色牛仔不过分正式，酒红鞋子给整体增加记忆点。",
                List.of("外套可以选短款针织开衫或小皮衣。", "香水和首饰都轻一点，避免喧宾夺主。"),
                List.of("更温柔：针织连衣裙 + 芭蕾鞋。", "更酷一点：短夹克 + 微喇裤。"),
                List.of("避免全身过多露肤或全套过新导致不自在。"),
                "已按约会、轻正式、亲和感模拟完整穿搭管道。"
        );
    }

    private static FashionResult dailyResult() {
        return new FashionResult(
                "日常出门",
                "干净白 T + 浅卡其直筒裤 + 德训鞋 + 帆布托特包",
                "这套适配度高，颜色轻松干净，临时逛街、见朋友或短途出门都不会出错。",
                List.of("想更显高就把上衣扎进裤腰。", "加一顶棒球帽能增强整体完成度。"),
                List.of("更学院：条纹 Polo + 百褶裙。", "更松弛：宽松衬衫 + 牛仔短裤。"),
                List.of("如果场合偏正式，换成衬衫和乐福鞋。"),
                "用户需求较泛，已用日常安全方案模拟完整穿搭管道。"
        );
    }
}
