package com.example.vrp.controller;

import com.example.vrp.model.VrpRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;
import com.example.vrp.service.VrpService;

import java.util.List;

@RestController
@RequestMapping("/api/vrp")
public class VrpController {

    @Autowired
    private VrpService vrpService;

    @PostMapping("/solve")
    public List<List<Integer>> solve(@RequestBody VrpRequest request) {
        return vrpService.solve(request);
    }
}
