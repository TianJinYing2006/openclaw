package com.example.ykdsummer.tianxing;

import com.fasterxml.jackson.annotation.JsonProperty;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

/**
 * 天行数据 BFR 体脂率计算业务服务。
 * 接口地址：https://apis.tianapi.com/bfrsum/index?key={apiKey}&age={age}&height={height}&weight={weight}&sex={sex}
 */
@Service
public class BfrService {

    private static final String BASE_URL = "https://apis.tianapi.com";

    private final RestClient restClient;
    private final String apiKey;

    public BfrService(
            RestClient.Builder restClientBuilder,
            @Value("${tianxing.api-key:}") String apiKey
    ) {
        this.restClient = restClientBuilder.baseUrl(BASE_URL).build();
        this.apiKey = apiKey;
    }

    /**
     * 计算体脂率。
     *
     * @param age    年龄（岁）
     * @param height 身高（厘米）
     * @param weight 体重（千克）
     * @param sex    性别：0=女性，1=男性
     * @return 体脂率计算结果
     */
    public BfrInfo calculateBfr(int age, int height, int weight, int sex) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("天行数据 API Key 未配置");
        }
        if (age < 1 || age > 150) {
            throw new IllegalArgumentException("年龄必须在 1~150 之间");
        }
        if (height < 50 || height > 300) {
            throw new IllegalArgumentException("身高必须在 50~300 cm 之间");
        }
        if (weight < 1 || weight > 500) {
            throw new IllegalArgumentException("体重必须在 1~500 kg 之间");
        }
        if (sex != 0 && sex != 1) {
            throw new IllegalArgumentException("性别：0=女性，1=男性");
        }

        var response = restClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/bfrsum/index")
                        .queryParam("key", apiKey)
                        .queryParam("age", age)
                        .queryParam("height", height)
                        .queryParam("weight", weight)
                        .queryParam("sex", sex)
                        .build())
                .accept(MediaType.APPLICATION_JSON)
                .retrieve()
                .body(ApiResponse.class);

        if (response == null || response.code() != 200) {
            String msg = (response != null) ? response.msg() : "null";
            throw new IllegalStateException("体脂率计算失败: " + msg);
        }

        var r = response.result();
        if (r == null) {
            throw new IllegalStateException("体脂率计算未返回结果");
        }
        return new BfrInfo(r.bfr(), r.tip(), r.healthy(), r.normbfr(), r.normweight(), r.idealweight());
    }

    // ---------- 内部 JSON 映射 ----------

    private record ApiResponse(
            int code,
            String msg,
            ResultItem result
    ) {
    }

    private record ResultItem(
            String bfr,
            String tip,
            String healthy,
            String normbfr,
            String normweight,
            String idealweight
    ) {
    }
}
