package com.example.vrp.service;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.example.vrp.model.Delivery;
import com.example.vrp.model.Location;
import com.example.vrp.model.Route;
import com.example.vrp.model.RouteStep;
import com.example.vrp.model.Vehicle;
import com.example.vrp.model.VrpRequest;
import com.example.vrp.model.VrpSolution;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.ortools.Loader;
import com.google.ortools.constraintsolver.Assignment;
import com.google.ortools.constraintsolver.FirstSolutionStrategy;
import com.google.ortools.constraintsolver.LocalSearchMetaheuristic;
import com.google.ortools.constraintsolver.RoutingDimension;
import com.google.ortools.constraintsolver.RoutingIndexManager;
import com.google.ortools.constraintsolver.RoutingModel;
import com.google.ortools.constraintsolver.RoutingSearchParameters;
import com.google.ortools.constraintsolver.main;

@Service
public class VrpService {

    // Nạp thư viện native của Google OR-Tools
    static {
        Loader.loadNativeLibraries();
    }

    private static final Logger logger = LoggerFactory.getLogger(VrpService.class);

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
        String objectiveMode = normalizeObjectiveMode(request.getObjectiveMode());
        int timeLimitSeconds = normalizeTimeLimitSeconds(request.getTimeLimitSeconds());

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

        double totalDemandKg = 0;
        for (long weight : deliveryWeights) {
            totalDemandKg += weight / 1000.0;
        }
        double totalVehicleCapacityKg = 0;
        double maxVehicleCapacityKg = 0;
        for (Vehicle vehicle : vehicles) {
            double capacityKg = Math.max(0.0, vehicle.getCapacity());
            totalVehicleCapacityKg += capacityKg;
            maxVehicleCapacityKg = Math.max(maxVehicleCapacityKg, capacityKg);
        }

        if (totalDemandKg > totalVehicleCapacityKg) {
            VrpSolution overflowSolution = buildOverCapacitySolution(vehicles, totalDemandKg, totalVehicleCapacityKg);
            return overflowSolution;
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

        // 5. Đăng ký transit cost theo chế độ được chọn
        for (int vehicleIdx = 0; vehicleIdx < numVehicles; vehicleIdx++) {
            final Vehicle vehicle = vehicles.get(vehicleIdx);
            final double vehicleCapacityKg = Math.max(1.0, vehicle.getCapacity());
            final double maxCapacityKg = Math.max(1.0, maxVehicleCapacityKg);
            final double baseFuelRate = Math.max(1.0, vehicle.getCostPerKm() > 0 ? vehicle.getCostPerKm() : 5000.0);

            final int costCallbackIndex;
            if (isDistanceObjective(objectiveMode)) {
                costCallbackIndex = routing.registerTransitCallback((long fromIndex, long toIndex) -> {
                    int fromNode = manager.indexToNode(fromIndex);
                    int toNode = manager.indexToNode(toIndex);
                    return distanceMatrix[fromNode][toNode];
                });
            } else {
                // Fuel-based: xe nhỏ hơn hoặc đơn nặng hơn sẽ có chi phí lớn hơn, giúp ưu tiên xe tải lớn.
                costCallbackIndex = routing.registerTransitCallback((long fromIndex, long toIndex) -> {
                    int fromNode = manager.indexToNode(fromIndex);
                    int toNode = manager.indexToNode(toIndex);

                    double distanceKm = distanceMatrix[fromNode][toNode] / 1000.0;
                    double nodeWeightKg = deliveryWeights[toNode] / 1000.0;

                    double capacityPenalty = (maxCapacityKg / vehicleCapacityKg) * 0.25;
                    double loadPenalty = (nodeWeightKg / vehicleCapacityKg) * 0.75;
                    double fuelMultiplier = 1.0 + capacityPenalty + loadPenalty;

                    long fuelCost = Math.round(distanceKm * baseFuelRate * fuelMultiplier);
                    return Math.max(1L, fuelCost);
                });
            }

            routing.setArcCostEvaluatorOfVehicle(costCallbackIndex, vehicleIdx);
        }

        // 6. Thêm Distance Dimension
        final int distanceCallbackIndex = routing.registerTransitCallback((long fromIndex, long toIndex) -> {
            int fromNode = manager.indexToNode(fromIndex);
            int toNode = manager.indexToNode(toIndex);
            return distanceMatrix[fromNode][toNode];
        });

        routing.addDimension(distanceCallbackIndex, 0, 50000000, true, "Distance");
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
        timeDimension.setGlobalSpanCostCoefficient(25);

        // Add time window constraint with validation and safe fallback to avoid OR-Tools failures
        final long MAX_DAY_MINUTES = 24 * 60;
        for (int i = 0; i < numLocations; i++) {
            int node = i;
            long idx = manager.nodeToIndex(node);
            long start = timeWindowStarts[i];
            long end = timeWindowEnds[i];

            if (start < 0) {
                logger.warn("Time window start negative for node {}: {} -> clamped to 0", node, start);
                start = 0;
            }
            if (end < 0) {
                logger.warn("Time window end negative for node {}: {} -> clamped to 0", node, end);
                end = 0;
            }
            if (start > MAX_DAY_MINUTES) {
                logger.warn("Time window start exceeds day for node {}: {} -> clamped to {}", node, start, MAX_DAY_MINUTES);
                start = MAX_DAY_MINUTES;
            }
            if (end > MAX_DAY_MINUTES) {
                logger.warn("Time window end exceeds day for node {}: {} -> clamped to {}", node, end, MAX_DAY_MINUTES);
                end = MAX_DAY_MINUTES;
            }

            if (end < start) {
                // Nếu end < start, mở rộng end thêm serviceTime hoặc đặt bằng start
                long fallbackEnd = Math.min(start + Math.max(1, serviceTimes[i]) + 60, MAX_DAY_MINUTES);
                logger.warn("Time window end < start for node {}: start={} end={} -> adjusted end={}", node, start, end, fallbackEnd);
                end = fallbackEnd;
            }

            try {
                logger.debug("Setting time window for node {} (index {}) -> [{}, {}]", node, idx, start, end);
                timeDimension.cumulVar(idx).setRange(start, end);
            } catch (Exception ex) {
                logger.error("Failed to set time window for node {} (index {}) with range [{}, {}]: {}. Applying wide fallback.", node, idx, start, end, ex.toString());
                // Áp dụng fallback rộng để tránh crash OR-Tools
                timeDimension.cumulVar(idx).setRange(0, MAX_DAY_MINUTES);
            }
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
            capacityDimension.cumulVar(routing.start(i)).setRange(0, 0);
            capacityDimension.cumulVar(routing.end(i)).setMax(vehicleCapacity);
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
                        .setSeconds(timeLimitSeconds)
                                        .build())
                        .build();

        // 10. Giải bài toán
        Assignment solution = routing.solveWithParameters(searchParameters);

        // 11. Tổng hợp kết quả
        return buildSolution(solution, routing, manager, vehicles, deliveries,
            distanceMatrix, timeMatrix, deliveryWeights, serviceTimes, depotLat, depotLng, objectiveMode);
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

                    return new RouteMetrics(distanceMatrix, timeMatrix);
                }
            }
        } catch (IOException | InterruptedException ignored) {
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

        return new RouteMetrics(distanceMatrix, timeMatrix);
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
                                      long[] serviceTimes, double depotLat, double depotLng,
                                      String objectiveMode) {
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
                double routeCost = isDistanceObjective(objectiveMode)
                    ? estimateDistanceRouteCost(vehicle, routeDistanceKm)
                    : estimateFuelRouteCost(vehicle, deliveryIds, deliveries, distanceMatrix, deliveryWeights);

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

        // After building routes, fetch geometry and steps for each route from OSRM
        for (int i = 0; i < routes.size(); i++) {
            Route r = routes.get(i);
            try {
                // build coordinate string from depot + deliveries
                List<double[]> coords = new ArrayList<>();
                coords.add(new double[]{depotLat, depotLng});
                for (Integer didx : r.getDeliveryIds()) {
                    Delivery d = deliveries.get(didx);
                    coords.add(new double[]{d.getLat(), d.getLng()});
                }
                coords.add(new double[]{depotLat, depotLng});

                DirectionsResult dr = fetchRouteDirections(coords);
                if (dr != null) {
                    r.setGeometry(dr.geometry);
                    r.setSteps(dr.steps);
                }
            } catch (Exception ex) {
                logger.warn("Failed to fetch route directions for route {}: {}", i, ex.toString());
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

    private VrpSolution buildOverCapacitySolution(List<Vehicle> vehicles,
                                                  double totalDemandKg, double totalVehicleCapacityKg) {
        VrpSolution solution = new VrpSolution();
        solution.setRoutes(Collections.emptyList());
        solution.setTotalDistance(0);
        solution.setTotalWeight(totalDemandKg);
        solution.setTotalCost(0);
        solution.setTotalTime(0);
        solution.setFeasible(false);

        if (vehicles == null || vehicles.isEmpty()) {
            solution.setMessage("Không có xe để kiểm tra tải trọng.");
            return solution;
        }

        List<Vehicle> sortedVehicles = new ArrayList<>(vehicles);
        sortedVehicles.sort(Comparator.comparingDouble(Vehicle::getCapacity).reversed());

        StringBuilder message = new StringBuilder();
        message.append("Tổng tải đơn hàng ")
                .append(String.format(Locale.US, "%.1f", totalDemandKg))
                .append(" kg vượt tổng sức chứa đội xe ")
                .append(String.format(Locale.US, "%.1f", totalVehicleCapacityKg))
                .append(" kg. ");
        message.append("Nếu vẫn phải giao hết toàn bộ đơn, các xe sau sẽ phải vượt tải theo phân bổ tỷ lệ sức chứa: ");

        for (Vehicle vehicle : sortedVehicles) {
            double vehicleCapacityKg = Math.max(1.0, vehicle.getCapacity());
            double proportionalLoad = totalDemandKg * (vehicleCapacityKg / Math.max(1.0, totalVehicleCapacityKg));
            double overloadKg = Math.max(0.0, proportionalLoad - vehicleCapacityKg);
            message.append(vehicle.getName() != null ? vehicle.getName() : ("Xe " + (vehicle.getId() + 1)))
                    .append(" (+")
                    .append(String.format(Locale.US, "%.1f", overloadKg))
                    .append(" kg); ");
        }

        solution.setMessage(message.toString());
        return solution;
    }

    private boolean isDistanceObjective(String objectiveMode) {
        return "distance".equalsIgnoreCase(normalizeObjectiveMode(objectiveMode));
    }

    private String normalizeObjectiveMode(String objectiveMode) {
        if (objectiveMode == null || objectiveMode.isBlank()) {
            return "fuel";
        }
        String normalized = objectiveMode.trim().toLowerCase(Locale.ROOT);
        return "distance".equals(normalized) ? "distance" : "fuel";
    }

    private int normalizeTimeLimitSeconds(Integer requestedSeconds) {
        if (requestedSeconds == null || requestedSeconds <= 0) {
            return 10;
        }
        return Math.min(Math.max(requestedSeconds, 1), 120);
    }

    private double estimateDistanceRouteCost(Vehicle vehicle, double routeDistanceKm) {
        double costPerKm = Math.max(1.0, vehicle.getCostPerKm() > 0 ? vehicle.getCostPerKm() : 5000.0);
        return routeDistanceKm * costPerKm + Math.max(0.0, vehicle.getFixedCost());
    }

    private double estimateFuelRouteCost(Vehicle vehicle, List<Integer> deliveryIds, List<Delivery> deliveries,
                                         long[][] distanceMatrix, long[] deliveryWeights) {
        double capacityKg = Math.max(1.0, vehicle.getCapacity());
        double baseFuelRate = Math.max(1.0, vehicle.getCostPerKm() > 0 ? vehicle.getCostPerKm() : 5000.0);
        double maxCapacityKg = Math.max(1.0, capacityKg);

        double remainingLoadKg = 0.0;
        for (Integer deliveryIdx : deliveryIds) {
            remainingLoadKg += deliveryWeights[deliveryIdx + 1] / 1000.0;
        }

        double totalCost = 0.0;
        int prevNode = 0;
        for (Integer deliveryIdx : deliveryIds) {
            int node = deliveryIdx + 1;
            double distanceKm = distanceMatrix[prevNode][node] / 1000.0;
            double capacityPenalty = (maxCapacityKg / capacityKg) * 0.20;
            double loadPenalty = (remainingLoadKg / capacityKg) * 0.80;
            double multiplier = 1.0 + capacityPenalty + loadPenalty;
            totalCost += distanceKm * baseFuelRate * multiplier;
            remainingLoadKg -= deliveries.get(deliveryIdx).getWeight();
            prevNode = node;
        }

        double distanceKm = distanceMatrix[prevNode][0] / 1000.0;
        double multiplier = 1.0 + (maxCapacityKg / capacityKg) * 0.20;
        totalCost += distanceKm * baseFuelRate * multiplier;

        // Nếu routeDistanceKm được tính từ solver và bị khác do làm tròn, vẫn giữ estimate theo tuyến thực tế.
        return totalCost;
    }

    private static class RouteMetrics {
        private final long[][] distanceMatrix;
        private final long[][] timeMatrix;

        private RouteMetrics(long[][] distanceMatrix, long[][] timeMatrix) {
            this.distanceMatrix = distanceMatrix;
            this.timeMatrix = timeMatrix;
        }
    }

    private static class DirectionsResult {
        List<Location> geometry;
        List<RouteStep> steps;

        DirectionsResult(List<Location> geometry, List<RouteStep> steps) {
            this.geometry = geometry;
            this.steps = steps;
        }
    }

    private DirectionsResult fetchRouteDirections(List<double[]> coordsLatLng) {
        if (coordsLatLng == null || coordsLatLng.size() < 2) return null;
        try {
            StringBuilder coordStr = new StringBuilder();
            for (int i = 0; i < coordsLatLng.size(); i++) {
                double[] p = coordsLatLng.get(i);
                if (i > 0) coordStr.append(';');
                coordStr.append(p[1]).append(',').append(p[0]); // lng,lat
            }

            String url = "https://router.project-osrm.org/route/v1/driving/" + coordStr.toString() + "?overview=full&geometries=geojson&steps=true";
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(10))
                    .GET()
                    .build();

            HttpResponse<String> response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) return null;

            JsonNode root = OBJECT_MAPPER.readTree(response.body());
            if (!"Ok".equalsIgnoreCase(root.path("code").asText())) return null;

            JsonNode route = root.path("routes").path(0);
            JsonNode geometry = route.path("geometry").path("coordinates");
            List<Location> geom = new ArrayList<>();
            if (geometry.isArray()) {
                for (JsonNode coord : geometry) {
                    double lng = coord.path(0).asDouble();
                    double lat = coord.path(1).asDouble();
                    geom.add(new Location(lat, lng));
                }
            }

            List<RouteStep> steps = new ArrayList<>();
            JsonNode legs = route.path("legs");
            if (legs.isArray()) {
                for (JsonNode leg : legs) {
                    JsonNode sarr = leg.path("steps");
                    if (sarr.isArray()) {
                        for (JsonNode step : sarr) {
                            String name = step.path("name").asText();
                            JsonNode maneuver = step.path("maneuver");
                            String type = maneuver.path("type").asText();
                            String modifier = maneuver.path("modifier").asText();
                            String instr = (type != null ? type : "") + (modifier != null && !modifier.isEmpty() ? " " + modifier : "") + (name != null && !name.isEmpty() ? " vào " + name : "");
                            double dist = step.path("distance").asDouble(0.0);
                            double dur = step.path("duration").asDouble(0.0);
                            steps.add(new RouteStep(instr, dist, dur));
                        }
                    }
                }
            }

            return new DirectionsResult(geom, steps);
        } catch (IOException | InterruptedException ex) {
            logger.warn("Error fetching directions from OSRM: {}", ex.toString());
            return null;
        }
    }
}