package com.example.vrp.model;

public class Location {
    private double lat; // Đã đổi thành private
    private double lng; // Đã đổi thành private

    // Constructor không tham số (Bắt buộc phải có để Spring Boot/Jackson đọc hiểu JSON)
    public Location() {
    }

    // Constructor có tham số để khởi tạo nhanh
    public Location(double lat, double lng) {
        this.lat = lat;
        this.lng = lng;
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
}