package com.wechatbot.fashion.iplocation;

/**
 * IP 地理位置信息，从 ip-api.com 解析后的稳定领域对象。
 */
public record IPLocationInfo(
        String query,
        String status,
        String message,
        String country,
        String countryCode,
        String region,
        String regionName,
        String city,
        String zip,
        double lat,
        double lon,
        String timezone,
        String isp,
        String org,
        String asn,
        boolean mobile,
        boolean proxy,
        boolean hosting
) {

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("🌐 IP: ").append(query).append("\n");

        if ("fail".equals(status)) {
            sb.append("状态：查询失败");
            if (message != null && !message.isBlank()) {
                sb.append("（").append(message).append("）");
            }
            return sb.toString();
        }

        sb.append("位置：").append(country);
        if (regionName != null && !regionName.isBlank() && !regionName.equals(country)) {
            sb.append(" · ").append(regionName);
        }
        if (city != null && !city.isBlank()) {
            sb.append(" · ").append(city);
        }
        if (zip != null && !zip.isBlank()) {
            sb.append(" (").append(zip).append(")");
        }
        sb.append("\n");

        if (Math.abs(lat) > 0.001 || Math.abs(lon) > 0.001) {
            sb.append("坐标：").append(String.format("%.4f", lat))
                    .append(", ").append(String.format("%.4f", lon)).append("\n");
        }
        if (timezone != null && !timezone.isBlank()) {
            sb.append("时区：").append(timezone).append("\n");
        }
        if (isp != null && !isp.isBlank()) {
            sb.append("ISP：").append(isp);
            if (asn != null && !asn.isBlank()) {
                sb.append(" (").append(asn).append(")");
            }
            sb.append("\n");
        }
        sb.append("代理：").append(proxy ? "是" : "否")
                .append("  移动：").append(mobile ? "是" : "否")
                .append("  托管：").append(hosting ? "是" : "否");

        return sb.toString();
    }
}
