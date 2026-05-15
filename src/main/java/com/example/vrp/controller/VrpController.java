package com.example.vrp.controller;

import com.example.vrp.model.VrpRequest;
import com.example.vrp.model.VrpSolution;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;
import com.example.vrp.service.VrpService;

@RestController
@RequestMapping("/api/vrp")
public class VrpController {

    @Autowired
    private VrpService vrpService;

    /**
     * API endpoint để giải bài toán VRP
     * @param request Chứa danh sách xe (capacity), danh sách đơn hàng (weight), depot location
     * @return VrpSolution - kết quả tối ưu chi tiết (routes, distance, weight, feasibility)
     */
    @PostMapping("/solve")
    public VrpSolution solve(@RequestBody VrpRequest request) {
        return vrpService.solve(request);
    }
}
