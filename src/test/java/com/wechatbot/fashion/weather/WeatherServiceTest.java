package com.wechatbot.fashion.weather;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class WeatherServiceTest {

    @Test
    void mapsPublicWeatherApiResponseToStableDomainResult() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(request -> {
                    assertThat(request.getURI().getPath()).isEqualTo("/api/v1/misc/weather");
                    assertThat(request.getURI().getQuery()).contains("city=杭州", "lang=zh");
                })
                .andRespond(withSuccess("""
                        {
                          "province":"浙江省",
                          "city":"杭州市",
                          "weather":"小雨",
                          "temperature":26,
                          "wind_direction":"西南风",
                          "wind_power":"2级",
                          "humidity":95,
                          "report_time":"5 分钟前发布"
                        }
                        """, MediaType.APPLICATION_JSON));
        WeatherService service = new WeatherService(builder);

        WeatherInfo result = service.getCurrentWeather("  杭州  ");

        assertThat(result).isEqualTo(new WeatherInfo(
                "浙江省", "杭州市", "小雨", 26,
                "西南风", "2级", 95, "5 分钟前发布"
        ));
        server.verify();
    }

    @Test
    void rejectsBlankCityBeforeCallingRemoteService() {
        WeatherService service = new WeatherService(RestClient.builder());

        assertThatIllegalArgumentException()
                .isThrownBy(() -> service.getCurrentWeather("  "))
                .withMessage("城市名称不能为空");
    }
}
