package com.example.vrp.service;

import com.example.vrp.model.Delivery;
import com.example.vrp.model.Route;
import com.example.vrp.model.Vehicle;
import com.example.vrp.model.VrpRequest;
import com.example.vrp.model.VrpSolution;
import com.google.ortools.Loader;
import com.google.ortools.constraintsolver.*;
import org.springframework.stereotype.Service;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.*;

@Service
public class VrpService {

    // Nạp thư viện native của Google OR-Tools
    static {
        Loader.loadNativeLibraries();
    }

    private static final int SPEED_KM_PER_HOUR = 40; // Tốc độ trung bình 40 km/h
        private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();
        private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    /**
     * Giải bài toán VRP với Capacity Constraint, Time Window, và Cost Optimization
     * @param request Chứa danh sách xe (với capacity, cost) và danh sách đơn hàng (với weight, time window)
     * @return VrpSolution - kết quả tối ưu chi tiết
     */
    public VrpSolution solve(VrpRequest request) {
        List<Vehicle> vehicles = request.getVehicles();
        List<Delivery> deliveries = request.getDeliveries();
        double depotLat = request.getDepotLat();
        double depotLng = request.getDepotLng();

        // Validation
        if (vehicles == null || vehicles.isEmpty() || deliveries == null || deliveries.isEmpty()) {
            VrpSolution solution = new VrpSolution();
            solution.setFeasible(false);
            solution.setMessage("Danh sách xe hoặc đơn hàng trống");
            return solution;
        }

        int numDeliveries = deliveries.size();
        int numVehicles = vehicles.size();

        // 1. Tạo ma trận khoảng cách: index 0 = depot, index 1..n = deliveries
        int numLocations = numDeliveries + 1; // +1 cho depot
        RouteMetrics routeMetrics = createRoadMetrics(depotLat, depotLng, deliveries);
        long[][] distanceMatrix = routeMetrics.distanceMatrix;
        long[][] timeMatrix = routeMetrics.timeMatrix;

        // 2. Tạo mảng trọng lượng delivery
        long[] deliveryWeights = new long[numLocations];
        deliveryWeights[0] = 0; // Depot không có trọng lượng
        for (int i = 0; i < numDeliveries; i++) {
            deliveryWeights[i + 1] = (long) (deliveries.get(i).getWeight() * 1000); // Chuyển kg -> gram
        }

        // 3. Tạo mảng time window
        long[] timeWindowStarts = new long[numLocations];
        long[] timeWindowEnds = new long[numLocations];
        long[] serviceTimes = new long[numLocations];

        timeWindowStarts[0] = 0;
        timeWindowEnds[0] = 24 * 60; // Depot cả ngày
        serviceTimes[0] = 0;

        for (int i = 0; i < numDeliveries; i++) {
            Delivery delivery = deliveries.get(i);
            timeWindowStarts[i + 1] = delivery.getTimeWindowStart();
            timeWindowEnds[i + 1] = delivery.getTimeWindowEnd();
            serviceTimes[i + 1] = delivery.getServiceTime();
        }

        // 4. Khởi tạo Manager và Routing Model
        RoutingIndexManager manager = new RoutingIndexManager(numLocations, numVehicles, 0);
        RoutingModel routing = new RoutingModel(manager);

        // 5. Đăng ký Transit Callback cho khoảng cách
        final int transitCallbackIndex = routing.registerTransitCallback((long fromIndex, long toIndex) -> {
            int fromNode = manager.indexToNode(fromIndex);
            int toNode = manager.indexToNode(toIndex);
            return distanceMatrix[fromNode][toNode];
        });

        routing.setArcCostEvaluatorOfAllVehicles(transitCallbackIndex);

        // 6. Thêm Distance Dimension
        routing.addDimension(
                transitCallbackIndex,
                0,
                50000000, // Max distance
                true,
                "Distance"
        );
        RoutingDimension distanceDimension = routing.getMutableDimension("Distance");
        distanceDimension.setGlobalSpanCostCoefficient(100);

        // 7. Thêm Time Dimension (ràng buộc thời gian)
        final int timeCallbackIndex = routing.registerTransitCallback((long fromIndex, long toIndex) -> {
            int fromNode = manager.indexToNode(fromIndex);
            int toNode = manager.indexToNode(toIndex);
            return timeMatrix[fromNode][toNode] + serviceTimes[toNode];
        });

        routing.addDimension(
                timeCallbackIndex,
                30, // slack - cho phép chờ 30 phút
                24 * 60, // max time = cả ngày
                false, // không bắt đầu từ 0
                "Time"
        );
        RoutingDimension timeDimension = routing.getMutableDimension("Time");

        // Add time window constraint
        for (int i = 0; i < numLocations; i++) {
            timeDimension.cumulVar(manager.nodeToIndex(i)).setRange(timeWindowStarts[i], timeWindowEnds[i]);
        }

        // 8. Thêm Capacity Dimension (ràng buộc tải trọng)
        final int capacityCallbackIndex = routing.registerTransitCallback((long fromIndex, long toIndex) -> {
            int toNode = manager.indexToNode(toIndex);
            return deliveryWeights[toNode];
        });

        routing.addDimension(
                capacityCallbackIndex,
                0,
                9223372036854775807L,
                true,
                "Capacity"
        );
        RoutingDimension capacityDimension = routing.getMutableDimension("Capacity");

        // Set capacity cho mỗi xe
        for (int i = 0; i < numVehicles; i++) {
            long vehicleCapacity = (long) (vehicles.get(i).getCapacity() * 1000);
            capacityDimension.cumulVar(routing.start(i)).setMax(vehicleCapacity);
        }

        // 9. Thiết lập tham số tìm kiếm với ưu tiên cost optimization
        RoutingSearchParameters searchParameters =
                main.defaultRoutingSearchParameters()
                        .toBuilder()
                        .setFirstSolutionStrategy(
                                FirstSolutionStrategy.Value.PATH_CHEAPEST_ARC)
                        .setLocalSearchMetaheuristic(
                                LocalSearchMetaheuristic.Value.GUIDED_LOCAL_SEARCH)
                        .setTimeLimit(
                                com.google.protobuf.Duration.newBuilder()
                                        .setSeconds(10)
                                        .build())
                        .build();

        // 10. Giải bài toán
        Assignment solution = routing.solveWithParameters(searchParameters);

        // 11. Tổng hợp kết quả
        return buildSolution(solution, routing, manager, vehicles, deliveries, 
                           distanceMatrix, timeMatrix, deliveryWeights, serviceTimes);
    }

    /**
     * Tạo ma trận khoảng cách và thời gian theo đường đi thực tế.
     * Ưu tiên dùng OSRM Table API, nếu lỗi sẽ fallback sang Haversine.
     */
    private RouteMetrics createRoadMetrics(double depotLat, double depotLng, List<Delivery> deliveries) {
        int size = deliveries.size() + 1;
        long[][] distanceMatrix = new long[size][size];
        long[][] timeMatrix = new long[size][size];

        try {
            StringBuilder coordinates = new StringBuilder();
            coordinates.append(depotLng).append(',').append(depotLat);
            for (Delivery delivery : deliveries) {
                coordinates.append(';').append(delivery.getLng()).append(',').append(delivery.getLat());
            }

            String osrmUrl = "https://router.project-osrm.org/table/v1/driving/" + coordinates +
                    "?annotations=distance,duration";

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(osrmUrl))
                    .timeout(Duration.ofSeconds(10))
                    .GET()
                    .build();

            HttpResponse<String> response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                JsonNode root = OBJECT_MAPPER.readTree(response.body());
                if ("Ok".equalsIgnoreCase(root.path("code").asText())) {
                    JsonNode distances = root.path("distances");
                    JsonNode durations = root.path("durations");

                    for (int i = 0; i < size; i++) {
                        for (int j = 0; j < size; j++) {
                            double distanceMeters = distances.path(i).path(j).asDouble(-1);
                            double durationSeconds = durations.path(i).path(j).asDouble(-1);

                            if (distanceMeters >= 0) {
                                distanceMatrix[i][j] = Math.round(distanceMeters);
                            }
                            if (durationSeconds >= 0) {
                                timeMatrix[i][j] = Math.max(1, Math.round(durationSeconds / 60.0));
                            }
                        }
                    }

                    return new RouteMetrics(distanceMatrix, timeMatrix, "OSRM");
                }
            }
        } catch (Exception ignored) {
            // Fallback bên dưới
        }

        for (int j = 0; j < deliveries.size(); j++) {
            Delivery delivery = deliveries.get(j);
            double distanceKm = haversineDistance(depotLat, depotLng, delivery.getLat(), delivery.getLng());
            long distanceMeters = Math.round(distanceKm * 1000 * 1.2); // hệ số bù đường bộ khi fallback
            distanceMatrix[0][j + 1] = distanceMeters;
            distanceMatrix[j + 1][0] = distanceMeters;

            long timeMinutes = Math.max(1, Math.round((distanceKm * 1.2 / SPEED_KM_PER_HOUR) * 60));
            timeMatrix[0][j + 1] = timeMinutes;
            timeMatrix[j + 1][0] = timeMinutes;
        }

        for (int i = 0; i < deliveries.size(); i++) {
            for (int j = 0; j < deliveries.size(); j++) {
                if (i == j) {
                    distanceMatrix[i + 1][j + 1] = 0;
                    timeMatrix[i + 1][j + 1] = 0;
                } else {
                    Delivery from = deliveries.get(i);
                    Delivery to = deliveries.get(j);
                    double distanceKm = haversineDistance(from.getLat(), from.getLng(), to.getLat(), to.getLng());
                    long distanceMeters = Math.round(distanceKm * 1000 * 1.2);
                    distanceMatrix[i + 1][j + 1] = distanceMeters;

                    long timeMinutes = Math.max(1, Math.round((distanceKm * 1.2 / SPEED_KM_PER_HOUR) * 60));
                    timeMatrix[i + 1][j + 1] = timeMinutes;
                }
            }
        }

        return new RouteMetrics(distanceMatrix, timeMatrix, "HaversineFallback");
    }

    /**
     * Công thức Haversine để tính khoảng cách chính xác giữa 2 điểm (lat/lng)
     */
    private double haversineDistance(double lat1, double lng1, double lat2, double lng2) {
        final int EARTH_RADIUS_KM = 6371;

        double dLat = Math.toRadians(lat2 - lat1);
        double dLng = Math.toRadians(lng2 - lng1);

        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
                   Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
                   Math.sin(dLng / 2) * Math.sin(dLng / 2);

        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
        return EARTH_RADIUS_KM * c;
    }

    /**
     * Xây dựng đối tượng VrpSolution từ kết quả tối ưu
     */
    private VrpSolution buildSolution(Assignment solution, RoutingModel routing,
                                      RoutingIndexManager manager, List<Vehicle> vehicles,
                                      List<Delivery> deliveries, long[][] distanceMatrix,
                                      long[][] timeMatrix, long[] deliveryWeights,
                                      long[] serviceTimes) {
        VrpSolution vrpSolution = new VrpSolution();
        List<Route> routes = new ArrayList<>();

        if (solution == null) {
            vrpSolution.setFeasible(false);
            vrpSolution.setMessage("Không tìm được lời giải trong thời gian cho phép");
            vrpSolution.setRoutes(routes);
            return vrpSolution;
        }

        double totalDistance = 0;
        double totalWeight = 0;
        double totalCost = 0;
        long totalTime = 0;
        int numDelivered = 0;

        RoutingDimension distanceDimension = routing.getMutableDimension("Distance");
        RoutingDimension timeDimension = routing.getMutableDimension("Time");

        for (int vehicleIdx = 0; vehicleIdx < vehicles.size(); vehicleIdx++) {
            Vehicle vehicle = vehicles.get(vehicleIdx);
            List<Integer> deliveryIds = new ArrayList<>();
            long routeDistance = 0;
            long routeTime = 0;
            long routeWeight = 0;

            long index = routing.start(vehicleIdx);
            while (!routing.isEnd(index)) {
                int nodeIndex = manager.indexToNode(index);
                
                if (nodeIndex > 0) {
                    int deliveryIdx = nodeIndex - 1;
                    deliveryIds.add(deliveryIdx);
                    routeWeight += deliveryWeights[nodeIndex];
                    numDelivered++;
                }

                long nextIndex = solution.value(routing.nextVar(index));
                routeDistance += distanceMatrix[manager.indexToNode(index)][manager.indexToNode(nextIndex)];
                routeTime += timeMatrix[manager.indexToNode(index)][manager.indexToNode(nextIndex)] +
                            serviceTimes[manager.indexToNode(nextIndex)];
                index = nextIndex;
            }

            // Chỉ thêm route nếu xe có giao hàng
            if (!deliveryIds.isEmpty()) {
                double routeDistanceKm = routeDistance / 1000.0;
                double routeWeightKg = routeWeight / 1000.0;
                double routeCost = (routeDistanceKm * vehicle.getCostPerKm()) + vehicle.getFixedCost();

                Route route = new Route(
                    vehicleIdx,
                    vehicle.getName() != null ? vehicle.getName() : "Xe " + (vehicleIdx + 1),
                    deliveryIds,
                    routeWeightKg,
                    vehicle.getCapacity(),
                    routeDistanceKm,
                    routeTime,
                    vehicle.getCostPerKm(),
                    vehicle.getFixedCost()
                );
                routes.add(route);

                totalDistance += routeDistanceKm;
                totalWeight += routeWeightKg;
                totalCost += routeCost;
                totalTime += routeTime;
            }
        }

        vrpSolution.setRoutes(routes);
        vrpSolution.setTotalDistance(totalDistance);
        vrpSolution.setTotalWeight(totalWeight);
        vrpSolution.setTotalCost(totalCost);
        vrpSolution.setTotalTime(totalTime);
        vrpSolution.setFeasible(numDelivered == deliveries.size());
        
        if (numDelivered < deliveries.size()) {
            vrpSolution.setMessage("Cảnh báo: Chỉ giao được " + numDelivered + "/" + deliveries.size() + 
                                 " đơn hàng. Xe không đủ sức chứa hoặc thời gian không phù hợp!");
        } else {
            vrpSolution.setMessage("✅ Tối ưu hóa thành công! Tất cả " + numDelivered + " đơn hàng đều được giao.");
        }

        return vrpSolution;
    }

    private static class RouteMetrics {
        private final long[][] distanceMatrix;
        private final long[][] timeMatrix;
        private final String source;

        private RouteMetrics(long[][] distanceMatrix, long[][] timeMatrix, String source) {
            this.distanceMatrix = distanceMatrix;
            this.timeMatrix = timeMatrix;
            this.source = source;
        }
    }
}