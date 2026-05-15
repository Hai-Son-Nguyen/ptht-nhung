package com.example.vrp.model;

/**
 * Thông tin xe giao hàng
 */
public class Vehicle {
    private int id;              // ID xe
    private double capacity;     // Sức chứa (kg)
    private double lat;          // Vị trí depot (kho hàng)
    private double lng;
    private String name;         // Tên xe (VD: "Xe 01", "Container A")
    private double costPerKm;    // Chi phí per km (VNĐ/km)
    private double fixedCost;    // Chi phí cố định (VNĐ/ngày)
    private long timeWindowStart; // Thời gian bắt đầu làm việc (phút từ 00:00)
    private long timeWindowEnd;   // Thời gian kết thúc làm việc (phút từ 00:00)

    public Vehicle() {
    }

    public Vehicle(int id, double capacity) {
        this.id = id;
        this.capacity = capacity;
        this.costPerKm = 0;
        this.fixedCost = 0;
        this.timeWindowStart = 0;
        this.timeWindowEnd = 24 * 60;
    }

    public Vehicle(int id, double capacity, double lat, double lng) {
        this.id = id;
        this.capacity = capacity;
        this.lat = lat;
        this.lng = lng;
        this.costPerKm = 0;
        this.fixedCost = 0;
        this.timeWindowStart = 0;
        this.timeWindowEnd = 24 * 60;
    }

    public Vehicle(int id, double capacity, double lat, double lng, String name) {
        this.id = id;
        this.capacity = capacity;
        this.lat = lat;
        this.lng = lng;
        this.name = name;
        this.costPerKm = 0;
        this.fixedCost = 0;
        this.timeWindowStart = 0;
        this.timeWindowEnd = 24 * 60;
    }

    public Vehicle(int id, double capacity, String name, double costPerKm, double fixedCost,
                   long timeWindowStart, long timeWindowEnd) {
        this.id = id;
        this.capacity = capacity;
        this.name = name;
        this.costPerKm = costPerKm;
        this.fixedCost = fixedCost;
        this.timeWindowStart = timeWindowStart;
        this.timeWindowEnd = timeWindowEnd;
    }

    public int getId() {
        return id;
    }

    public void setId(int id) {
        this.id = id;
    }

    public double getCapacity() {
        return capacity;
    }

    public void setCapacity(double capacity) {
        this.capacity = capacity;
    }

    public double getLat() {
        return lat;
    }

    public void setLat(double lat) {
        this.lat = lat;
    }

    public double getLng() {
        return lng;
    }

    public void setLng(double lng) {
        this.lng = lng;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
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

    public long getTimeWindowStart() {
        return timeWindowStart;
    }

    public void setTimeWindowStart(long timeWindowStart) {
        this.timeWindowStart = timeWindowStart;
    }

    public long getTimeWindowEnd() {
        return timeWindowEnd;
    }

    public void setTimeWindowEnd(long timeWindowEnd) {
        this.timeWindowEnd = timeWindowEnd;
    }
}
