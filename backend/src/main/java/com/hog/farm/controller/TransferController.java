package com.hog.farm.controller;

import com.hog.farm.common.ApiResponse;
import com.hog.farm.domain.FeedBatch;
import com.hog.farm.domain.House;
import com.hog.farm.domain.Pen;
import com.hog.farm.dto.FeedBatchRequest;
import com.hog.farm.dto.FeedingRequest;
import com.hog.farm.dto.TransferRequest;
import com.hog.farm.dto.Views.FeedingVO;
import com.hog.farm.dto.Views.TransferVO;
import com.hog.farm.service.HouseService;
import com.hog.farm.service.TransferService;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 转栏与饲喂：左源栏位 / 右目标栏位 / 中间搬移按钮 + 饲喂投料 */
@RestController
@RequestMapping("/api/transfer")
public class TransferController {

    private final TransferService service;
    private final HouseService houseService;

    public TransferController(TransferService service, HouseService houseService) {
        this.service = service;
        this.houseService = houseService;
    }

    @GetMapping("/list")
    public ApiResponse<List<TransferVO>> listTransfers() {
        return ApiResponse.success(service.listTransfers());
    }

    /** 左右两栏搬移 */
    @PostMapping
    public ApiResponse<List<TransferVO>> createTransfer(@RequestBody TransferRequest request) {
        List<TransferVO> created = service.createTransfer(request);
        return ApiResponse.success("转栏完成，共 " + created.size() + " 头", created);
    }

    @GetMapping("/pens")
    public ApiResponse<List<Pen>> pens(@RequestParam(required = false) Long houseId) {
        return ApiResponse.success(houseService.pens(houseId));
    }

    @GetMapping("/houses")
    public ApiResponse<List<House>> houses() {
        return ApiResponse.success(service.houses());
    }

    @GetMapping("/pen/{penId}/pigs")
    public ApiResponse<List<Long>> pigsInPen(@PathVariable Long penId) {
        return ApiResponse.success(service.pigsInPen(penId));
    }

    // ---------------- 饲喂 ----------------

    @GetMapping("/feeding")
    public ApiResponse<List<FeedingVO>> feedings() {
        return ApiResponse.success(service.listFeedings());
    }

    @PostMapping("/feeding")
    public ApiResponse<FeedingVO> createFeeding(@RequestBody FeedingRequest request) {
        return ApiResponse.success("投料已登记", service.createFeeding(request));
    }

    @GetMapping("/feed-batch")
    public ApiResponse<List<FeedBatch>> feedBatches() {
        return ApiResponse.success(service.listFeedBatches());
    }

    @PostMapping("/feed-batch")
    public ApiResponse<FeedBatch> createFeedBatch(@RequestBody FeedBatchRequest request) {
        return ApiResponse.success("饲料批次已入库", service.createFeedBatch(request));
    }
}
