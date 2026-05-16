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
import com.example.vrp.model.RouteStep;
import com.example.vrp.model.Location;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
            distanceMatrix, timeMatrix, deliveryWeights, serviceTimes, depotLat, depotLng);
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
                                      long[] serviceTimes, double depotLat, double depotLng) {
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
            // prepare route coordinates for directions
            List<double[]> routeCoordsLatLng = new ArrayList<>();
            routeCoordsLatLng.add(new double[]{depotLat, depotLng});
            while (!routing.isEnd(index)) {
                int nodeIndex = manager.indexToNode(index);
                
                if (nodeIndex > 0) {
                    int deliveryIdx = nodeIndex - 1;
                    deliveryIds.add(deliveryIdx);
                    routeWeight += deliveryWeights[nodeIndex];
                    numDelivered++;
                    // append delivery coords
                    Delivery d = deliveries.get(deliveryIdx);
                    routeCoordsLatLng.add(new double[]{d.getLat(), d.getLng()});
                }

                long nextIndex = solution.value(routing.nextVar(index));
                routeDistance += distanceMatrix[manager.indexToNode(index)][manager.indexToNode(nextIndex)];
                routeTime += timeMatrix[manager.indexToNode(index)][manager.indexToNode(nextIndex)] +
                            serviceTimes[manager.indexToNode(nextIndex)];
                index = nextIndex;
            }

            // add depot at end
            routeCoordsLatLng.add(new double[]{depotLat, depotLng});

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
        } catch (Exception ex) {
            logger.warn("Error fetching directions from OSRM: {}", ex.toString());
            return null;
        }
    }
}