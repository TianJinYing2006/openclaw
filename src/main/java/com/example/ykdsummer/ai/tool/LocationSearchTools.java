package com.example.ykdsummer.ai.tool;

import com.example.ykdsummer.location.LocationSearchInfo;
import com.example.ykdsummer.location.LocationSearchService;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

@Component
public class LocationSearchTools {

    private final LocationSearchService locationSearchService;

    public LocationSearchTools(LocationSearchService locationSearchService) {
        this.locationSearchService = locationSearchService;
    }

    @Tool(
            name = "search_poi",
            description = "搜索指定城市内的地点（POI），如餐厅、酒店、景点、商场、医院等。"
                    + "返回地点名称、地址、电话、分类和坐标。"
                    + "当用户询问某城市有什么、在哪里、推荐去哪时调用。"
    )
    public Object searchPoi(
            @ToolParam(required = true, description = "搜索关键词，如餐厅、酒店、星巴克、医院")
            String keyword,
            @ToolParam(required = true, description = "城市名称，如北京、上海、杭州")
            String city
    ) {
        if (!locationSearchService.isConfigured()) {
            return "腾讯地图 POI 搜索未配置 API Key";
        }
        return locationSearchService.search(keyword, city);
    }

    @Tool(
            name = "search_nearby_poi",
            description = "搜索某个坐标附近的地点（POI），如附近的餐厅、便利店、医院等。"
                    + "返回地点名称、地址、电话、分类、坐标和距离。"
                    + "当用户询问'附近'、'一公里内'、'周围'等基于位置的搜索时调用。"
                    + "需要提供经纬度坐标和搜索半径（米）。"
    )
    public Object searchNearbyPoi(
            @ToolParam(required = true, description = "搜索关键词，如餐厅、便利店、药店")
            String keyword,
            @ToolParam(required = true, description = "纬度，如39.9042（北京纬度）")
            double lat,
            @ToolParam(required = true, description = "经度，如116.4074（北京经度）")
            double lng,
            @ToolParam(required = false, description = "搜索半径（米，10 到 1000；默认1000）")
            Integer radiusMeters
    ) {
        if (!locationSearchService.isConfigured()) {
            return "腾讯地图 POI 搜索未配置 API Key";
        }
        int radius = radiusMeters == null || radiusMeters <= 0 ? 1000 : radiusMeters;
        return locationSearchService.searchNearby(keyword, lat, lng, radius);
    }

    @Tool(
            name = "geocode",
            description = "将地址或地名转换为经纬度坐标。"
                    + "当用户提到一个具体地点（如西湖、天安门、东方明珠）但没有给出经纬度时，"
                    + "先调用此工具获取坐标，再用坐标调用 search_nearby_poi 搜索附近。"
    )
    public Object geocode(
            @ToolParam(required = true, description = "地址或地名，如西湖、天安门、北京市海淀区")
            String address
    ) {
        if (!locationSearchService.isConfigured()) {
            return "腾讯地图 POI 搜索未配置 API Key";
        }
        return locationSearchService.geocode(address);
    }
}
