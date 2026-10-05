package com.hog.farm.controller;

import com.hog.farm.common.ApiResponse;
import com.hog.farm.domain.TraceRecord;
import com.hog.farm.dto.TraceRequest;
import com.hog.farm.dto.TraceRequest.TargetRef;
import com.hog.farm.dto.VaccineRequest;
import com.hog.farm.dto.Views.TargetVO;
import com.hog.farm.dto.Views.TraceVO;
import com.hog.farm.dto.Views.VaccineVO;
import com.hog.farm.service.TraceService;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 防疫免疫与溯源（溯源记录走 @ManyToAny 多态来源） */
@RestController
@RequestMapping("/api/trace")
public class TraceController {

    private final TraceService service;

    public TraceController(TraceService service) {
        this.service = service;
    }

    // ---------------- 免疫 ----------------

    @GetMapping("/vaccine")
    public ApiResponse<List<VaccineVO>> vaccines() {
        return ApiResponse.success(service.listVaccines());
    }

    @PostMapping("/vaccine")
    public ApiResponse<VaccineVO> createVaccine(@RequestBody VaccineRequest request) {
        return ApiResponse.success("免疫记录已登记", service.createVaccine(request));
    }

    // ---------------- 溯源 ----------------

    /** 时间线：每个节点带多态来源标签 */
    @GetMapping("/list")
    public ApiResponse<List<TraceVO>> list(@RequestParam(required = false) String traceType) {
        return ApiResponse.success(service.listTraces(traceType));
    }

    /** 新建溯源记录：targets 里可以混搭猪只 / 批次 / 栋舍 / 饲料批次 */
    @PostMapping
    public ApiResponse<TraceVO> create(@RequestBody TraceRequest request) {
        TraceRecord record = service.createTrace(request);
        return ApiResponse.success("溯源记录已创建", service.traceVO(record.getId()));
    }

    /** 在已有溯源记录上追加一个多态来源 */
    @PostMapping("/{id}/target")
    public ApiResponse<TraceVO> attach(@PathVariable Long id, @RequestBody TargetRef ref) {
        service.attachTarget(id, ref);
        return ApiResponse.success("来源对象已追加", service.traceVO(id));
    }

    /** 候选来源对象（按类型） */
    @GetMapping("/target-options")
    public ApiResponse<List<TargetVO>> targetOptions(@RequestParam String targetType) {
        return ApiResponse.success(service.targetOptions(targetType));
    }
}
