package com.example.vrp.model;

import java.util.List;

/**
 * Kết quả tối ưu VRP - chứa tất cả các tuyến đường
 */
public class VrpSolution {
    private List<Route> routes;          // Danh sách các tuyến đường
    private double totalDistance;        // Tổng quãng đường tất cả xe
    private double totalWeight;          // Tổng trọng lượng giao
    private double totalCost;            // Tổng chi phí (VNĐ)
    private long totalTime;              // Tổng thời gian (phút)
    private boolean feasible;            // Có khả thi không (tất cả hàng được giao?)
    private String message;              // Thông báo (lỗi, cảnh báo)

    public VrpSolution() {
    }

    public VrpSolution(List<Route> routes, double totalDistance, double totalWeight, boolean feasible) {
        this.routes = routes;
        this.totalDistance = totalDistance;
        this.totalWeight = totalWeight;
        this.feasible = feasible;
        this.totalCost = 0;
        this.totalTime = 0;
    }

    public List<Route> getRoutes() {
        return routes;
    }

    public void setRoutes(List<Route> routes) {
        this.routes = routes;
    }

    public double getTotalDistance() {
        return totalDistance;
    }

    public void setTotalDistance(double totalDistance) {
        this.totalDistance = totalDistance;
    }

    public double getTotalWeight() {
        return totalWeight;
    }

    public void setTotalWeight(double totalWeight) {
        this.totalWeight = totalWeight;
    }

    public double getTotalCost() {
        return totalCost;
    }

    public void setTotalCost(double totalCost) {
        this.totalCost = totalCost;
    }

    public long getTotalTime() {
        return totalTime;
    }

    public void setTotalTime(long totalTime) {
        this.totalTime = totalTime;
    }

    public boolean isFeasible() {
        return feasible;
    }

    public void setFeasible(boolean feasible) {
        this.feasible = feasible;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }
}
