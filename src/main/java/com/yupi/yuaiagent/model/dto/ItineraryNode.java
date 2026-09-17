package com.yupi.yuaiagent.model.dto;

/**
 * 单个行程节点（地点、时间、交通）
 *
 * @param nodeId        节点 ID
 * @param poiName       地点名称
 * @param category      地点类别
 * @param arrivalTime   到达时间
 * @param stayDuration  建议停留时长
 * @param transitToNext 前往下一站的通勤方式
 */
public record ItineraryNode(
        String nodeId,
        String poiName,
        String category,
        String arrivalTime,
        String stayDuration,
        String transitToNext
) {
}
