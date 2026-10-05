package com.hog.farm.controller;

import com.hog.farm.common.ApiResponse;
import com.hog.farm.domain.House;
import com.hog.farm.domain.Pen;
import com.hog.farm.dto.HouseRequest;
import com.hog.farm.dto.HouseStatusRequest;
import com.hog.farm.dto.PenRequest;
import com.hog.farm.dto.Views.HouseStatusLogVO;
import com.hog.farm.dto.Views.HouseStatusReviewVO;
import com.hog.farm.dto.Views.HouseVO;
import com.hog.farm.dto.Views.PenVO;
import com.hog.farm.dto.Views.PigVO;
import com.hog.farm.service.HouseService;
import com.hog.farm.service.PigService;
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

/** 栋舍与栏位 */
@RestController
@RequestMapping("/api/house")
public class HouseController {

    private final HouseService service;
    private final PigService pigService;

    public HouseController(HouseService service, PigService pigService) {
        this.service = service;
        this.pigService = pigService;
    }

    @GetMapping("/list")
    public ApiResponse<List<House>> list() {
        return ApiResponse.success(service.list());
    }

    /** 栋舍平面格子图：栋舍 → 栏位格子（每格带存栏 / 容量） */
    @GetMapping("/grid")
    public ApiResponse<List<HouseVO>> grid() {
        return ApiResponse.success(service.grid());
    }

    @GetMapping("/pens")
    public ApiResponse<List<Pen>> pens(@RequestParam(required = false) Long houseId) {
        return ApiResponse.success(service.pens(houseId));
    }

    /** 点格子看存栏 */
    @GetMapping("/pen/{penId}/pigs")
    public ApiResponse<List<PigVO>> penPigs(@PathVariable Long penId) {
        return ApiResponse.success(pigService.listByPen(penId));
    }

    @GetMapping("/pen/{penId}")
    public ApiResponse<PenVO> pen(@PathVariable Long penId) {
        return ApiResponse.success(service.penVO(penId));
    }

    @GetMapping("/{id}")
    public ApiResponse<House> get(@PathVariable Long id) {
        return ApiResponse.success(service.get(id));
    }

    @PostMapping
    public ApiResponse<House> create(@RequestBody HouseRequest request) {
        return ApiResponse.success("栋舍已创建", service.create(request));
    }

    @PutMapping("/{id}")
    public ApiResponse<House> update(@PathVariable Long id, @RequestBody HouseRequest request) {
        return ApiResponse.success("栋舍已更新", service.update(id, request));
    }

    /**
     * 状态切换预检：操作员确认前看到受影响栏位、猪只清单和阻塞原因。
     * 检修立即生效、原地照护；停用先腾空（PENDING_DISABLE）再生效（DISABLED）。
     */
    @GetMapping("/{id}/status/review")
    public ApiResponse<HouseStatusReviewVO> reviewStatus(@PathVariable Long id,
                                                         @RequestParam String targetStatus) {
        return ApiResponse.success(service.reviewStatus(id, targetStatus));
    }

    /** 操作员确认后的状态切换（带操作员与幂等 requestNo） */
    @PostMapping("/{id}/status")
    public ApiResponse<HouseStatusReviewVO> changeStatus(@PathVariable Long id,
                                                         @RequestBody HouseStatusRequest request) {
        return ApiResponse.success("栋舍状态已更新", service.changeStatus(id, request));
    }

    /** 栋舍状态切换留痕（安置结论可追踪，同一次切换不会重复出现） */
    @GetMapping("/status-logs")
    public ApiResponse<List<HouseStatusLogVO>> statusLogs(@RequestParam(required = false) Long houseId) {
        return ApiResponse.success(service.statusLogs(houseId));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> remove(@PathVariable Long id) {
        service.remove(id);
        return ApiResponse.success("栋舍已删除", null);
    }

    // ---------------- 栏位 ----------------

    @PostMapping("/pen")
    public ApiResponse<Pen> createPen(@RequestBody PenRequest request) {
        return ApiResponse.success("栏位已创建", service.createPen(request));
    }

    @PutMapping("/pen/{id}")
    public ApiResponse<Pen> updatePen(@PathVariable Long id, @RequestBody PenRequest request) {
        return ApiResponse.success("栏位已更新", service.updatePen(id, request));
    }

    @DeleteMapping("/pen/{id}")
    public ApiResponse<Void> removePen(@PathVariable Long id) {
        service.removePen(id);
        return ApiResponse.success("栏位已删除", null);
    }
}
