package com.hog.farm.controller;

import com.hog.farm.common.ApiResponse;
import com.hog.farm.domain.Pig;
import com.hog.farm.dto.PigRequest;
import com.hog.farm.dto.Views.PigVO;
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

/** 猪只个体与耳标 */
@RestController
@RequestMapping("/api/pig")
public class PigController {

    private final PigService service;

    public PigController(PigService service) {
        this.service = service;
    }

    @GetMapping("/list")
    public ApiResponse<List<PigVO>> list(@RequestParam(required = false) String status,
                                         @RequestParam(required = false) String breed,
                                         @RequestParam(required = false) Long penId,
                                         @RequestParam(required = false) String keyword) {
        return ApiResponse.success(service.list(status, breed, penId, keyword));
    }

    /** 下拉候选猪只 */
    @GetMapping("/options")
    public ApiResponse<List<PigVO>> options() {
        return ApiResponse.success(service.options());
    }

    @GetMapping("/{id}")
    public ApiResponse<Pig> get(@PathVariable Long id) {
        return ApiResponse.success(service.get(id));
    }

    @PostMapping
    public ApiResponse<Pig> create(@RequestBody PigRequest request) {
        return ApiResponse.success("猪只已入栏", service.create(request));
    }

    @PutMapping("/{id}")
    public ApiResponse<Pig> update(@PathVariable Long id, @RequestBody PigRequest request) {
        return ApiResponse.success("猪只已更新", service.update(id, request));
    }

    /** 单头状态流转 */
    @PostMapping("/{id}/status")
    public ApiResponse<Pig> changeStatus(@PathVariable Long id, @RequestParam String status) {
        return ApiResponse.success("猪只状态已更新", service.changeStatus(id, status));
    }

    /** 侧滑批量操作：勾选多头一次推进状态 */
    @PostMapping("/batch/status")
    public ApiResponse<Integer> batchChangeStatus(@RequestBody BatchStatusRequest request) {
        int count = service.batchChangeStatus(request.getIds(), request.getStatus());
        return ApiResponse.success("批量操作完成，共 " + count + " 头", count);
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> remove(@PathVariable Long id) {
        service.remove(id);
        return ApiResponse.success("猪只已删除", null);
    }

    /** 批量操作的请求体 */
    public static class BatchStatusRequest {
        private List<Long> ids;
        private String status;

        public List<Long> getIds() {
            return ids;
        }

        public void setIds(List<Long> ids) {
            this.ids = ids;
        }

        public String getStatus() {
            return status;
        }

        public void setStatus(String status) {
            this.status = status;
        }
    }
}
