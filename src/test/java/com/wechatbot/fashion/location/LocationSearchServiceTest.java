package com.wechatbot.fashion.location;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class LocationSearchServiceTest {

    @Test
    void sendsNearbyBoundaryInTencentLatitudeLongitudeOrderAndClampsRadius() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(request -> {
                    assertThat(request.getURI().getPath()).isEqualTo("/ws/place/v1/search");
                    assertThat(request.getURI().getQuery())
                            .contains("boundary=nearby(39.9042,116.4074,10)");
                })
                .andRespond(withSuccess("""
                        {"status":0,"message":"query ok","count":1,"data":[
                          {"id":"poi-1","title":"测试餐厅","address":"测试地址",
                           "location":{"lat":39.9042,"lng":116.4074},"_distance":80}
                        ]}
                        """, MediaType.APPLICATION_JSON));
        LocationSearchService service = new LocationSearchService(builder, "test-key");

        LocationSearchInfo result = service.searchNearby("餐厅", 39.9042, 116.4074, 5);

        assertThat(result.count()).isEqualTo(1);
        assertThat(result.pois()).singleElement().satisfies(poi -> {
            assertThat(poi.lat()).isEqualTo(39.9042);
            assertThat(poi.lng()).isEqualTo(116.4074);
        });
        server.verify();
    }
}
