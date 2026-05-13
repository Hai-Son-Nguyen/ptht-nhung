package com.example.vrp.model;

import java.util.List;

public class VrpRequest {
    private int vehicles;
    private List<Location> locations;

    public VrpRequest() {
    }

    public VrpRequest(int vehicles, List<Location> locations) {
        this.vehicles = vehicles;
        this.locations = locations;
    }

    public int getVehicles() {
        return vehicles;
    }

    public void setVehicles(int vehicles) {
        this.vehicles = vehicles;
    }

    public List<Location> getLocations() {
        return locations;
    }

    public void setLocations(List<Location> locations) {
        this.locations = locations;
    }
}