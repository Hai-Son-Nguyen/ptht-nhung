package com.example.vrp.service;

import com.example.vrp.model.Location;
import com.example.vrp.model.VrpRequest;
import com.google.ortools.Loader;
import com.google.ortools.constraintsolver.*;
import org.springframework.stereotype.Service;
import java.util.*;

@Service
public class VrpService {

    // Nạp thư viện native của Google OR-Tools
    static {
        Loader.loadNativeLibraries();
    }

    public List<List<Integer>> solve(VrpRequest request) {
        List<Location> locations = request.getLocations();
        int vehicles = request.getVehicles();
        int size = locations.size();

        if (size < 2) return new ArrayList<>();

        // 1. Tạo ma trận khoảng cách
        long[][] distanceMatrix = new long[size][size];
        for (int i = 0; i < size; i++) {
            for (int j = 0; j < size; j++) {
                double dx = locations.get(i).getLat() - locations.get(j).getLat();
                double dy = locations.get(i).getLng() - locations.get(j).getLng();
                // Nhân với hệ số lớn vì OR-Tools làm việc với số nguyên (long)
                distanceMatrix[i][j] = (long) (Math.sqrt(dx * dx + dy * dy) * 100000);
            }
        }

        // 2. Khởi tạo Manager và Routing Model
        // 0 là chỉ số của Depot (điểm đầu tiên trong danh sách)
        RoutingIndexManager manager = new RoutingIndexManager(size, vehicles, 0);
        RoutingModel routing = new RoutingModel(manager);

        // 3. Đăng ký Transit Callback
        final int transitCallbackIndex = routing.registerTransitCallback((long fromIndex, long toIndex) -> {
            int fromNode = manager.indexToNode(fromIndex);
            int toNode = manager.indexToNode(toIndex);
            return distanceMatrix[fromNode][toNode];
        });

        routing.setArcCostEvaluatorOfAllVehicles(transitCallbackIndex);

        // 4. Thêm ràng buộc khoảng cách (để chia đều các tuyến)
        routing.addDimension(
                transitCallbackIndex,
                0,      // không có thời gian chờ (slack)
                10000000, // Tổng quãng đường tối đa của 1 xe (tăng lên để tránh lỗi)
                true,   // Bắt đầu từ 0
                "Distance"
        );
        RoutingDimension distanceDimension = routing.getMutableDimension("Distance");
        distanceDimension.setGlobalSpanCostCoefficient(100);

        // 5. Thiết lập tham số tìm kiếm
        RoutingSearchParameters searchParameters =
                main.defaultRoutingSearchParameters() // <--- Lấy mặc định trước
                        .toBuilder() // <--- Sau đó mới tạo Builder để sửa
                        .setFirstSolutionStrategy(
                                FirstSolutionStrategy.Value.PATH_CHEAPEST_ARC)
                        .setLocalSearchMetaheuristic(
                                LocalSearchMetaheuristic.Value.GUIDED_LOCAL_SEARCH)
                        .setTimeLimit(
                                com.google.protobuf.Duration.newBuilder()
                                        .setSeconds(5)
                                        .build())
                        .build();

        // 6. Giải bài toán
        Assignment solution = routing.solveWithParameters(searchParameters);

        // 7. Tổng hợp kết quả trả về
        List<List<Integer>> routes = new ArrayList<>();
        if (solution != null) {
            for (int i = 0; i < vehicles; i++) {
                List<Integer> route = new ArrayList<>();
                long index = routing.start(i);
                while (!routing.isEnd(index)) {
                    route.add(manager.indexToNode(index));
                    index = solution.value(routing.nextVar(index));
                }
                route.add(manager.indexToNode(index)); // Thêm điểm kết thúc (Depot)

                // Chỉ trả về tuyến đường nếu xe đó có di chuyển qua các điểm giao
                if (route.size() > 2) {
                    routes.add(route);
                }
            }
        }
        return routes;
    }
}