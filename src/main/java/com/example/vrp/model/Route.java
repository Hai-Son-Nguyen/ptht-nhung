package com.example.vrp.model;

import java.util.List;

/**
 * Kết quả tối ưu cho một tuyến đường (route) của một xe
 */
public class Route {
    private int vehicleId;           // ID xe
    private String vehicleName;      // Tên xe
    private List<Integer> deliveryIds; // Danh sách ID đơn hàng theo thứ tự
    private double totalWeight;      // Tổng trọng lượng trên xe
    private double capacity;         // Sức chứa của xe
    private double totalDistance;    // Tổng quãng đường (km)
    private double utilizationRate;  // Tỷ lệ sử dụng sức chứa (%)
    private long totalTime;          // Tổng thời gian (phút)
    private double totalCost;        // Tổng chi phí (VNĐ)
    private double costPerKm;        // Chi phí per km
    private double fixedCost;        // Chi phí cố định

    public Route() {
    }

    public Route(int vehicleId, String vehicleName, List<Integer> deliveryIds, 
                 double totalWeight, double capacity, double totalDistance) {
        this.vehicleId = vehicleId;
        this.vehicleName = vehicleName;
        this.deliveryIds = deliveryIds;
        this.totalWeight = totalWeight;
        this.capacity = capacity;
        this.totalDistance = totalDistance;
        this.utilizationRate = (totalWeight / capacity) * 100;
        this.totalTime = 0;
        this.totalCost = 0;
    }

    public Route(int vehicleId, String vehicleName, List<Integer> deliveryIds, 
                 double totalWeight, double capacity, double totalDistance, 
                 long totalTime, double costPerKm, double fixedCost) {
        this.vehicleId = vehicleId;
        this.vehicleName = vehicleName;
        this.deliveryIds = deliveryIds;
        this.totalWeight = totalWeight;
        this.capacity = capacity;
        this.totalDistance = totalDistance;
        this.utilizationRate = (totalWeight / capacity) * 100;
        this.totalTime = totalTime;
        this.costPerKm = costPerKm;
        this.fixedCost = fixedCost;
        this.totalCost = (totalDistance * costPerKm) + fixedCost;
    }

    public int getVehicleId() {
        return vehicleId;
    }

    public void setVehicleId(int vehicleId) {
        this.vehicleId = vehicleId;
    }

    public String getVehicleName() {
        return vehicleName;
    }

    public void setVehicleName(String vehicleName) {
        this.vehicleName = vehicleName;
    }

    public List<Integer> getDeliveryIds() {
        return deliveryIds;
    }

    public void setDeliveryIds(List<Integer> deliveryIds) {
        this.deliveryIds = deliveryIds;
    }

    public double getTotalWeight() {
        return totalWeight;
    }

    public void setTotalWeight(double totalWeight) {
        this.totalWeight = totalWeight;
    }

    public double getCapacity() {
        return capacity;
    }

    public void setCapacity(double capacity) {
        this.capacity = capacity;
    }

    public double getTotalDistance() {
        return totalDistance;
    }

    public void setTotalDistance(double totalDistance) {
        this.totalDistance = totalDistance;
    }

    public double getUtilizationRate() {
        return utilizationRate;
    }

    public void setUtilizationRate(double utilizationRate) {
        this.utilizationRate = utilizationRate;
    }

    public long getTotalTime() {
        return totalTime;
    }

    public void setTotalTime(long totalTime) {
        this.totalTime = totalTime;
    }

    public double getTotalCost() {
        return totalCost;
    }

    public void setTotalCost(double totalCost) {
        this.totalCost = totalCost;
    }

    public double getCostPerKm() {
        return costPerKm;
    }

    public void setCostPerKm(double costPerKm) {
        this.costPerKm = costPerKm;
    }

    public double getFixedCost() {
        return fixedCost;
    }

    public void setFixedCost(double fixedCost) {
        this.fixedCost = fixedCost;
    }
}
