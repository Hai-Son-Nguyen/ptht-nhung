package com.example.vrp.model;

public class RouteStep {
    private String instruction;
    private double distance; // meters
    private double duration; // seconds

    public RouteStep() {}

    public RouteStep(String instruction, double distance, double duration) {
        this.instruction = instruction;
        this.distance = distance;
        this.duration = duration;
    }

    public String getInstruction() {
        return instruction;
    }

    public void setInstruction(String instruction) {
        this.instruction = instruction;
    }

    public double getDistance() {
        return distance;
    }

    public void setDistance(double distance) {
        this.distance = distance;
    }

    public double getDuration() {
        return duration;
    }

    public void setDuration(double duration) {
        this.duration = duration;
    }
}
