package com.example.vrp.model;

import java.util.List;

/**
 * Request để tối ưu hóa tuyến đường giao hàng
 * Bao gồm thông tin xe và đơn hàng cần giao
 */
public class VrpRequest {
    private List<Vehicle> vehicles;      // Danh sách xe có sức chứa
    private List<Delivery> deliveries;   // Danh sách đơn hàng cần giao
    private double depotLat;             // Kho hàng (depot) - điểm xuất phát
    private double depotLng;

    // Constructor không tham số
    public VrpRequest() {
    }

    public VrpRequest(List<Vehicle> vehicles, List<Delivery> deliveries, 
                      double depotLat, double depotLng) {
        this.vehicles = vehicles;
        this.deliveries = deliveries;
        this.depotLat = depotLat;
        this.depotLng = depotLng;
    }

    public List<Vehicle> getVehicles() {
        return vehicles;
    }

    public void setVehicles(List<Vehicle> vehicles) {
        this.vehicles = vehicles;
    }

    public List<Delivery> getDeliveries() {
        return deliveries;
    }

    public void setDeliveries(List<Delivery> deliveries) {
        this.deliveries = deliveries;
    }

    public double getDepotLat() {
        return depotLat;
    }

    public void setDepotLat(double depotLat) {
        this.depotLat = depotLat;
    }

    public double getDepotLng() {
        return depotLng;
    }

    public void setDepotLng(double depotLng) {
        this.depotLng = depotLng;
    }
}