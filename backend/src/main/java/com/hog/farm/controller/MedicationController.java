package com.hog.farm.controller;

import com.hog.farm.common.ApiResponse;
import com.hog.farm.dto.MedicationRequest;
import com.hog.farm.dto.MedicationRevokeRequest;
import com.hog.farm.dto.MedicationUpdateRequest;
import com.hog.farm.dto.Views.MedicationVO;
import com.hog.farm.service.MedicationService;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 用药登记、休药期纠错与撤销 */
@RestController
@RequestMapping("/api/medication")
public class MedicationController {

    private final MedicationService service;

    public MedicationController(MedicationService service) {
        this.service = service;
    }

    @GetMapping("/list")
    public ApiResponse<List<MedicationVO>> list(@RequestParam(required = false) Long pigId) {
        return ApiResponse.success(service.list(pigId));
    }

    @GetMapping("/{id}")
    public ApiResponse<MedicationVO> get(@PathVariable Long id) {
        return ApiResponse.success(service.get(id));
    }

    @PostMapping
    public ApiResponse<List<MedicationVO>> create(@RequestBody MedicationRequest request) {
        return ApiResponse.success("用药登记已保存，休药期已重算", service.create(request));
    }

    @PutMapping("/{id}")
    public ApiResponse<MedicationVO> update(@PathVariable Long id, @RequestBody MedicationUpdateRequest request) {
        return ApiResponse.success("用药记录已纠错，休药期已重算", service.update(id, request));
    }

    @PostMapping("/{id}/revoke")
    public ApiResponse<MedicationVO> revoke(@PathVariable Long id,
                                            @RequestBody(required = false) MedicationRevokeRequest request) {
        return ApiResponse.success("用药记录已撤销，休药期已重算", service.revoke(id, request));
    }
}
