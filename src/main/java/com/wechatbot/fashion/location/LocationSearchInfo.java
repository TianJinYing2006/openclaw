package com.wechatbot.fashion.location;

import java.util.List;

public record LocationSearchInfo(
        int count,
        List<Poi> pois
) {
    public record Poi(
            String id,
            String title,
            String address,
            String tel,
            String category,
            double lat,
            double lng,
            int distanceMeters,
            String province,
            String city,
            String district
    ) {
    }
}
