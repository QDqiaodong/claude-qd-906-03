package com.hog.farm.controller;

import com.hog.farm.common.ApiResponse;
import com.hog.farm.domain.Batch;
import com.hog.farm.dto.BatchRequest;
import com.hog.farm.dto.ShipmentRequest;
import com.hog.farm.dto.Views.BatchVO;
import com.hog.farm.dto.Views.ShipmentReviewVO;
import com.hog.farm.service.BatchService;
import com.hog.farm.service.ShipmentService;
import java.util.List;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 出栏批次：卡片流水线，一张卡片一个推进按钮 + 登记出栏 */
@RestController
@RequestMapping("/api/batch")
public class BatchController {

    private final BatchService service;
    private final ShipmentService shipmentService;

    public BatchController(BatchService service, ShipmentService shipmentService) {
        this.service = service;
        this.shipmentService = shipmentService;
    }

    @GetMapping("/list")
    public ApiResponse<List<BatchVO>> list() {
        return ApiResponse.success(service.list());
    }

    @GetMapping("/{id}")
    public ApiResponse<Batch> get(@PathVariable Long id) {
        return ApiResponse.success(service.get(id));
    }

    @PostMapping
    public ApiResponse<Batch> create(@RequestBody BatchRequest request) {
        return ApiResponse.success("出栏批次已创建", service.create(request));
    }

    @PutMapping("/{id}")
    public ApiResponse<Batch> update(@PathVariable Long id, @RequestBody BatchRequest request) {
        return ApiResponse.success("出栏批次已更新", service.update(id, request));
    }

    /** 卡片底部单按钮：按 在建 → 在养 → 出栏中 → 已清栏 顺序推进 */
    @PostMapping("/{id}/advance")
    public ApiResponse<BatchVO> advance(@PathVariable Long id) {
        return ApiResponse.success("批次已推进", service.toVO(service.advance(id)));
    }

    /** 提交前预检：明确展示放行、休药期拦截、状态异常跳过三类猪只 */
    @PostMapping("/{id}/shipment/preview")
    public ApiResponse<ShipmentReviewVO> previewShipment(@PathVariable Long id,
                                                         @RequestBody(required = false) ShipmentRequest request) {
        ShipmentReviewVO result = shipmentService.preview(id, request);
        return ApiResponse.success(result.getMessage(), result);
    }

    /** 猪只级放行：事务内更新猪只、批次、栏位与溯源事件，requestNo 幂等防重 */
    @PostMapping("/{id}/shipment/confirm")
    public ApiResponse<ShipmentReviewVO> confirmShipment(@PathVariable Long id,
                                                         @RequestBody ShipmentRequest request) {
        ShipmentReviewVO result = shipmentService.confirm(id, request);
        return ApiResponse.success(result.getMessage(), result);
    }

    /** 历史兼容：仅供台账中没有未终态猪只的老批次继续按汇总头数登记 */
    @PostMapping("/{id}/ship")
    public ApiResponse<BatchVO> ship(@PathVariable Long id, @RequestParam Integer count) {
        return ApiResponse.success("出栏已登记", service.toVO(service.ship(id, count)));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> remove(@PathVariable Long id) {
        service.remove(id);
        return ApiResponse.success("出栏批次已删除", null);
    }
}
