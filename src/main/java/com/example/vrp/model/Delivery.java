package com.example.vrp.model;

/**
 * Đơn hàng cần giao - mỗi delivery có một vị trí, trọng lượng và khung giờ giao
 */
public class Delivery {
    private int id;              // ID đơn hàng
    private double lat;          // Vị trí giao hàng
    private double lng;
    private double weight;       // Trọng lượng hàng hóa (kg)
    private String address;      // Địa chỉ giao hàng (optional)
    private long timeWindowStart; // Khung giờ bắt đầu (phút từ 00:00)
    private long timeWindowEnd;   // Khung giờ kết thúc (phút từ 00:00)
    private long serviceTime;     // Thời gian phục vụ (phút) - bao lâu để giao hàng

    public Delivery() {
    }

    public Delivery(int id, double lat, double lng, double weight) {
        this.id = id;
        this.lat = lat;
        this.lng = lng;
        this.weight = weight;
        this.timeWindowStart = 0;
        this.timeWindowEnd = 24 * 60; // Cả ngày
        this.serviceTime = 5; // 5 phút
    }

    public Delivery(int id, double lat, double lng, double weight, long timeWindowStart, long timeWindowEnd) {
        this.id = id;
        this.lat = lat;
        this.lng = lng;
        this.weight = weight;
        this.timeWindowStart = timeWindowStart;
        this.timeWindowEnd = timeWindowEnd;
        this.serviceTime = 5;
    }

    public Delivery(int id, double lat, double lng, double weight, String address, 
                    long timeWindowStart, long timeWindowEnd, long serviceTime) {
        this.id = id;
        this.lat = lat;
        this.lng = lng;
        this.weight = weight;
        this.address = address;
        this.timeWindowStart = timeWindowStart;
        this.timeWindowEnd = timeWindowEnd;
        this.serviceTime = serviceTime;
    }

    public int getId() {
        return id;
    }

    public void setId(int id) {
        this.id = id;
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

    public double getWeight() {
        return weight;
    }

    public void setWeight(double weight) {
        this.weight = weight;
    }

    public String getAddress() {
        return address;
    }

    public void setAddress(String address) {
        this.address = address;
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

    public long getServiceTime() {
        return serviceTime;
    }

    public void setServiceTime(long serviceTime) {
        this.serviceTime = serviceTime;
    }
}
